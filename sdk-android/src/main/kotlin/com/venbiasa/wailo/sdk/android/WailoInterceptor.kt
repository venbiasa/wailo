package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.protocol.MapLocalRule
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
 * Order per request: the capture filter (ADR-0029) decides whether the whole exchange is captured at all.
 * If no breakpoint matches, Map Local (ADR-0019) serves a matching request locally and short-circuits. If
 * a breakpoint (ADR-0027) matches, it *owns* the exchange (ADR-0033, superseding ADR-0027/0032): the
 * request phase can pause before the request is sent (edit/abort/resume); the response is then sourced from
 * Map Local when a rule matches the outgoing request — that mocked value *becomes* the breakpoint's
 * response — otherwise from the real network; and the response phase can pause on it before the app sees
 * it. So Map Local supplies the breakpoint's response rather than bypassing it; only with no breakpoint
 * does it short-circuit. Any Map Local fetch failure (no authority, timeout, rule gone) falls open to the
 * network.
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

        // No breakpoint: Map Local (if it matches) serves and short-circuits; otherwise the real network.
        // When a breakpoint matches it instead *owns* the exchange (below) and Map Local sources the response
        // it shows rather than short-circuiting (ADR-0033).
        if (breakpoint == null) {
            if (mapRule != null) {
                serveMapLocal(mapRule, request, shouldCapture, startedAtEpochMs, startNs)?.let { return it }
            }
            val response = proceedToNetwork(chain, request, requestEdited = false, shouldCapture, startedAtEpochMs, startNs)
            if (shouldCapture) {
                emit(startedAtEpochMs, elapsedMs(startNs), captureRequest(request), captureResponse(response))
            }
            return response
        }

        // Breakpoint owns the exchange (ADR-0033). Request phase: pause before the response is sourced.
        // Abort fails the call; an edited request replaces the original; a disconnect falls open (proceed
        // with the original).
        var outgoing = request
        var requestEdited = false
        if (breakpoint.onRequest) {
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

        // Source the response. Map Local — matched against the (possibly edited) outgoing request — supplies
        // it when a rule matches, so the mocked value *becomes* the breakpoint's response (ADR-0033);
        // otherwise the real network does, and a Map Local fetch miss falls open to it. Matching the outgoing
        // request also subsumes the old edited-request re-check (ADR-0032).
        val mapped = WailoRuleStore.match(outgoing.url.toString(), outgoing.method)
            ?.let { mapLocalResponse(it, outgoing) }
        val servedFromMapLocal = mapped != null
        val response = mapped
            ?: proceedToNetwork(chain, outgoing, requestEdited, shouldCapture, startedAtEpochMs, startNs)
        // The exchange is a modification of the real one when the request was edited or the response came
        // from Map Local; a response-phase edit flags it too, inside the handler.
        val baseEdited = requestEdited || servedFromMapLocal

        // Response phase: pause before the app sees it, on the sourced response (Map Local- or network-based).
        // Needs the full body to show and maybe replace, so it consumes the response and rebuilds one.
        if (breakpoint.onResponse && WailoControlChannel.breakpointGate != null) {
            return handleResponseBreakpoint(outgoing, response, breakpoint.ruleId, baseEdited, startedAtEpochMs, startNs, shouldCapture)
        }

        // No response phase: capture without consuming (peekBody) and hand the sourced response back.
        if (shouldCapture) {
            emit(startedAtEpochMs, elapsedMs(startNs), captureRequest(outgoing), captureResponse(response), edited = baseEdited)
        }
        return response
    }

    // Proceed on the real-network path, mirroring an IOException as a captured (error) exchange before
    // rethrowing so a network failure still shows up. [requestEdited] flags the row when a request-phase
    // breakpoint had already rewritten the request.
    private fun proceedToNetwork(
        chain: Interceptor.Chain,
        request: Request,
        requestEdited: Boolean,
        shouldCapture: Boolean,
        startedAtEpochMs: Long,
        startNs: Long,
    ): Response =
        try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (shouldCapture) {
                emit(startedAtEpochMs, elapsedMs(startNs), captureRequest(request), null, error = e.toString(), edited = requestEdited)
            }
            throw e
        }

    // Pause the response phase: the desktop can edit it, abort the call, or resume it unchanged. The body
    // is read in full (consuming the stream) so it can be shown and swapped; the returned response is
    // rebuilt from those bytes (original or edited). A null gate (disconnected) already short-circuited.
    // [baseEdited] is the exchange's edited state before this phase (request edited and/or Map Local-sourced,
    // ADR-0033), used when the response is resumed unchanged; an edit or abort flags it edited regardless.
    private fun handleResponseBreakpoint(
        request: Request,
        response: Response,
        ruleId: String,
        baseEdited: Boolean,
        startedAtEpochMs: Long,
        startNs: Long,
        shouldCapture: Boolean,
    ): Response {
        val gate = WailoControlChannel.breakpointGate ?: run {
            if (shouldCapture) {
                emit(startedAtEpochMs, elapsedMs(startNs), captureRequest(request), captureResponse(response), edited = baseEdited)
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
                        emit(startedAtEpochMs, elapsedMs(startNs), requestContext, currentResponse, edited = baseEdited)
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

    // Fetch a Map Local body from the desktop and build a synthetic response — WITHOUT mirroring it. The
    // caller decides how it's captured, since a response-phase breakpoint may still pause/edit it before the
    // app sees it (ADR-0033). Returns null when the fetch can't complete (no authority, timeout, rule gone)
    // so the caller falls open to the real network.
    private fun mapLocalResponse(rule: MapLocalRule, request: Request): Response? {
        val mapped = WailoControlChannel.bodyFetcher
            ?.fetchBody(rule.id, request.url.toString(), request.method) ?: return null
        return buildResponse(request, mapped.code, "", mapped.headers, mapped.body)
    }

    // Serve [request] from Map Local [rule] on the no-breakpoint path: build the response and mirror the
    // served exchange (flagged edited) when capturing. Returns null when the fetch can't complete so the
    // caller falls open to the real network. When a breakpoint owns the exchange the response is instead
    // sourced via [mapLocalResponse], so it can flow through the response phase (ADR-0033).
    private fun serveMapLocal(
        rule: MapLocalRule,
        request: Request,
        shouldCapture: Boolean,
        startedAtEpochMs: Long,
        startNs: Long,
    ): Response? {
        val response = mapLocalResponse(rule, request) ?: return null
        if (shouldCapture) {
            emit(startedAtEpochMs, elapsedMs(startNs), captureRequest(request), captureResponse(response), edited = true)
        }
        return response
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
