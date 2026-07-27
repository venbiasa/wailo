package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.UUID

/**
 * OkHttp [Interceptor] that mirrors each exchange into a [CaptureSink] and applies the desktop's
 * device-bound features, the Android analog of the iOS `WailoURLProtocol`. It reads the process-global
 * stores + control channel ([WailoRuleStore] / [WailoBreakpointStore] / [WailoCaptureFilterStore] /
 * [WailoControlChannel]) the [WailoClient] keeps in sync, so it needs no per-client wiring beyond its sink.
 *
 * Where the iOS `URLProtocol` is asynchronous, an OkHttp interceptor is **blocking**: it runs on a
 * background call thread and must return a [Response]. So the two control round-trips block that thread
 * — a Map Local body fetch with a timeout, a breakpoint hold indefinitely (a human decides) — and both
 * fail open if the desktop is (or goes) away, so a held call never wedges.
 *
 * Order per request: the capture filter (ADR-0029) decides whether the whole exchange is captured at
 * all; Map Local (ADR-0019) matches locally, fetches the body from the desktop and serves it, taking
 * precedence over breakpoints and never broken (ADR-0027 precedence v1); otherwise, on the real-network
 * path, a breakpoint (ADR-0027) can pause the request before it is sent and/or the response before the
 * app sees it, to edit, abort, or resume it.
 */
class WailoInterceptor internal constructor(
    private val sink: CaptureSink,
    private val maxBodyBytes: Long,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val method = request.method
        val host = request.url.host
        val startedAtEpochMs = System.currentTimeMillis()
        val startNs = System.nanoTime()

        val shouldCapture = WailoCaptureFilterStore.shouldCapture(host)
        val mapRule = WailoRuleStore.match(url, method)
        val breakpoint = WailoBreakpointStore.match(url, method)

        // Filtered host with nothing to intercept: pass straight through — don't buffer the body or emit.
        if (!shouldCapture && mapRule == null && breakpoint == null) {
            return chain.proceed(request)
        }

        // Map Local takes precedence and is never broken: match locally, fetch the body from the desktop,
        // and serve it. Any failure (no authority, timeout, rule gone) falls open to the real network.
        if (mapRule != null) {
            val mapped = WailoControlChannel.bodyFetcher?.fetchBody(mapRule.id, url, method)
            if (mapped != null) {
                if (shouldCapture) {
                    emit(
                        startedAtEpochMs, elapsedMs(startNs),
                        captureRequest(request),
                        responseModel(mapped.code, "", mapped.headers, mapped.body),
                        edited = true,
                    )
                }
                return buildResponse(request, mapped.code, "", mapped.headers, mapped.body)
            }
        }

        // Breakpoint request phase: pause before sending. Abort fails the call; an edited request is sent
        // instead of the original; a disconnect falls open (proceed with the original).
        var outgoing = request
        var requestEdited = false
        if (breakpoint != null && breakpoint.onRequest) {
            val gate = WailoControlChannel.breakpointGate
            if (gate != null) {
                when (val decision = gate.pauseRequest(breakpoint.ruleId, captureRequest(request))) {
                    WailoRequestDecision.Abort -> abort(captureRequest(request), startedAtEpochMs, startNs, shouldCapture)
                    is WailoRequestDecision.Proceed -> decision.edited?.let {
                        outgoing = applyEdited(request, it)
                        requestEdited = true
                    }
                }
            }
        }

        val response = try {
            chain.proceed(outgoing)
        } catch (e: IOException) {
            if (shouldCapture) {
                emit(startedAtEpochMs, elapsedMs(startNs), captureRequest(outgoing), null, error = e.toString(), edited = requestEdited)
            }
            throw e
        }

        // Breakpoint response phase: pause before the app sees it. Needs the full body to show and maybe
        // replace, so it consumes the response and rebuilds one for the caller.
        if (breakpoint != null && breakpoint.onResponse && WailoControlChannel.breakpointGate != null) {
            return handleResponseBreakpoint(outgoing, response, breakpoint.ruleId, requestEdited, startedAtEpochMs, startNs, shouldCapture)
        }

        // Normal path: capture without consuming (peekBody) and hand the real response back untouched.
        if (shouldCapture) {
            emit(startedAtEpochMs, elapsedMs(startNs), captureRequest(outgoing), captureResponse(response), edited = requestEdited)
        }
        return response
    }

    // Pause the response phase: the desktop can edit it, abort the call, or resume it unchanged. The body
    // is read in full (consuming the stream) so it can be shown and swapped; the returned response is
    // rebuilt from those bytes (original or edited). A null gate (disconnected) already short-circuited.
    private fun handleResponseBreakpoint(
        request: Request,
        response: Response,
        ruleId: String,
        requestEdited: Boolean,
        startedAtEpochMs: Long,
        startNs: Long,
        shouldCapture: Boolean,
    ): Response {
        val gate = WailoControlChannel.breakpointGate ?: run {
            if (shouldCapture) {
                emit(startedAtEpochMs, elapsedMs(startNs), captureRequest(request), captureResponse(response), edited = requestEdited)
            }
            return response
        }
        val contentType = response.body?.contentType()
        val declaredSize = response.body?.contentLength() ?: -1L
        val fullBody = (response.body?.bytes() ?: ByteArray(0)).toByteString()
        val requestContext = captureRequest(request)
        val currentResponse = responseModelFrom(response, fullBody, declaredSize)

        return when (val decision = gate.pauseResponse(ruleId, requestContext, currentResponse)) {
            WailoResponseDecision.Abort -> {
                if (shouldCapture) {
                    emit(startedAtEpochMs, elapsedMs(startNs), requestContext, currentResponse, error = "aborted by breakpoint", edited = true)
                }
                throw IOException("Wailo breakpoint aborted the request")
            }
            is WailoResponseDecision.Proceed -> {
                val edited = decision.edited
                if (edited != null) {
                    if (shouldCapture) {
                        emit(startedAtEpochMs, elapsedMs(startNs), requestContext, responseModel(edited.code, edited.message, edited.headers, edited.body), edited = true)
                    }
                    buildResponse(request, edited.code, edited.message, edited.headers, edited.body)
                } else {
                    if (shouldCapture) {
                        emit(startedAtEpochMs, elapsedMs(startNs), requestContext, currentResponse, edited = requestEdited)
                    }
                    response.newBuilder().body(fullBody.toResponseBody(contentType)).build()
                }
            }
        }
    }

    // Fail the app's call as a breakpoint Abort. Flagged `edited` so the desktop marks the exchange as
    // intercepted rather than a genuine network failure.
    private fun abort(request: HttpRequest, startedAtEpochMs: Long, startNs: Long, shouldCapture: Boolean): Nothing {
        if (shouldCapture) {
            emit(startedAtEpochMs, elapsedMs(startNs), request, null, error = "aborted by breakpoint", edited = true)
        }
        throw IOException("Wailo breakpoint aborted the request")
    }

    // Build a synthetic OkHttp response from desktop-supplied parts (a Map Local body or an edited
    // response). code 0 -> 200 so a decision that omits the status still yields a valid response.
    private fun buildResponse(request: Request, code: Int, message: String, headers: List<Header>, body: ByteString): Response {
        val okHeaders = headers.toOkHeaders()
        val contentType = okHeaders["Content-Type"]?.toMediaTypeOrNull()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (code == 0) 200 else code)
            .message(message)
            .headers(okHeaders)
            .body(body.toResponseBody(contentType))
            .build()
    }

    // Rebuild the outgoing request from a breakpoint's edited request: new method/url/headers/body.
    private fun applyEdited(original: Request, edited: HttpRequest): Request {
        val okHeaders = edited.headers.toOkHeaders()
        val contentType = okHeaders["Content-Type"]?.toMediaTypeOrNull()
        val requestBody = when {
            edited.body.size > 0 -> edited.body.toByteArray().toRequestBody(contentType)
            requiresRequestBody(edited.method) -> ByteArray(0).toRequestBody(contentType)
            else -> null
        }
        return original.newBuilder()
            .url(edited.url)
            .method(edited.method, requestBody)
            .headers(okHeaders)
            .build()
    }

    private fun captureRequest(request: Request): HttpRequest {
        val body = request.body
        var captured = ByteString.EMPTY
        var truncated = false
        // Reading a one-shot or duplex body here would corrupt the real request; a normal body can be
        // written to a buffer repeatedly, so this doesn't consume it for the actual send.
        if (body != null && !body.isDuplex() && !body.isOneShot()) {
            try {
                val buffer = Buffer()
                body.writeTo(buffer)
                truncated = buffer.size > maxBodyBytes
                captured = buffer.readByteString(minOf(buffer.size, maxBodyBytes))
            } catch (_: IOException) {
                // Body not reproducible for capture; leave it empty.
            }
        }
        return HttpRequest(
            method = request.method,
            url = request.url.toString(),
            headers = request.headers.toModel(),
            body = captured,
            body_size = body?.contentLength() ?: 0L,
            body_truncated = truncated,
        )
    }

    // Non-consuming capture for the normal path: peekBody copies up to the cap without draining the real
    // body, so the caller still receives the full response.
    private fun captureResponse(response: Response): HttpResponse {
        val declaredSize = response.body?.contentLength() ?: -1L
        val captured = response.peekBody(maxBodyBytes).bytes().toByteString()
        val truncated =
            if (declaredSize >= 0) declaredSize > maxBodyBytes else captured.size.toLong() >= maxBodyBytes
        return HttpResponse(
            code = response.code,
            message = response.message,
            headers = response.headers.toModel(),
            body = captured,
            body_size = declaredSize,
            body_truncated = truncated,
        )
    }

    // Capture from a response whose body we've already read in full (the breakpoint path), applying the
    // size cap to the mirrored copy while [declaredSize] keeps the size column honest.
    private fun responseModelFrom(response: Response, fullBody: ByteString, declaredSize: Long): HttpResponse {
        val (body, truncated) = capBody(fullBody)
        return HttpResponse(
            code = response.code,
            message = response.message,
            headers = response.headers.toModel(),
            body = body,
            body_size = if (declaredSize >= 0) declaredSize else fullBody.size.toLong(),
            body_truncated = truncated,
        )
    }

    // Build a captured HttpResponse from desktop-supplied parts (Map Local / edited response).
    private fun responseModel(code: Int, message: String, headers: List<Header>, fullBody: ByteString): HttpResponse {
        val (body, truncated) = capBody(fullBody)
        return HttpResponse(
            code = code,
            message = message,
            headers = headers,
            body = body,
            body_size = fullBody.size.toLong(),
            body_truncated = truncated,
        )
    }

    private fun capBody(full: ByteString): Pair<ByteString, Boolean> {
        val cap = maxBodyBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return if (full.size > cap) full.substring(0, cap) to true else full to false
    }

    private fun Headers.toModel(): List<Header> =
        (0 until size).map { i -> Header(name = name(i), value_ = value(i)) }

    // addUnsafeNonAscii tolerates non-ASCII header values the desktop may supply (a strict add() would
    // reject them); names are still validated.
    private fun List<Header>.toOkHeaders(): Headers {
        val builder = Headers.Builder()
        for (header in this) builder.addUnsafeNonAscii(header.name, header.value_)
        return builder.build()
    }

    private fun requiresRequestBody(method: String): Boolean = method.uppercase() in REQUIRES_BODY_METHODS

    private fun emit(
        startedAtEpochMs: Long,
        durationMs: Long,
        request: HttpRequest?,
        response: HttpResponse?,
        error: String = "",
        edited: Boolean = false,
    ) {
        sink.onExchange(
            HttpExchange(
                id = newId(),
                started_at_epoch_ms = startedAtEpochMs,
                duration_ms = durationMs,
                request = request,
                response = response,
                error = error,
                edited = edited,
            ),
        )
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000

    private companion object {
        // Methods OkHttp requires to carry a request body; an edited request for one of these with an
        // empty body still needs a (zero-length) body rather than null.
        val REQUIRES_BODY_METHODS = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
    }
}
