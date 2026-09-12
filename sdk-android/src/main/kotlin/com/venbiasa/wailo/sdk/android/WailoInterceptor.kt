package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.protocol.MapLocalRule
import com.venbiasa.wailo.protocol.ScriptPhase
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
import okio.ForwardingSink
import okio.buffer
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
        val original = chain.request()
        val url = original.url.toString()
        val method = original.method
        val startedAtEpochMs = System.currentTimeMillis()
        val startNs = System.nanoTime()

        // The filter is deliberately decided before Scripts can rewrite the URL.
        val shouldCapture = WailoCaptureFilterStore.shouldCapture(original.url.host)
        val hasScript = WailoScriptStore.matches(url, method, ScriptPhase.SCRIPT_PHASE_REQUEST) ||
            WailoScriptStore.matches(url, method, ScriptPhase.SCRIPT_PHASE_RESPONSE)
        if (
            !shouldCapture &&
            !hasScript &&
            WailoRuleStore.match(url, method) == null &&
            WailoBreakpointStore.match(url, method) == null
        ) {
            return chain.proceed(original)
        }

        val requestPhase = transformRequest(original)
        var outgoing = requestPhase.request
        var requestEdited = requestPhase.edited
        val breakpoint = WailoBreakpointStore.match(outgoing.url.toString(), outgoing.method)

        if (breakpoint?.onRequest == true) {
            val gate = WailoControlChannel.breakpointGate
            if (gate != null) {
                when (val decision = gate.pauseRequest(breakpoint.ruleId, captureRequest(outgoing))) {
                    WailoRequestDecision.Abort ->
                        abort(captureRequest(outgoing), startedAtEpochMs, startNs, shouldCapture)
                    is WailoRequestDecision.Proceed -> decision.edited?.let {
                        outgoing = applyEdited(outgoing, it)
                        requestEdited = true
                    }
                }
            }
        }

        val mapped = WailoRuleStore.match(outgoing.url.toString(), outgoing.method)
            ?.let { mapLocalResponse(it, outgoing) }
        val servedFromMapLocal = mapped != null
        val sourced = mapped
            ?: proceedToNetwork(chain, outgoing, requestEdited, shouldCapture, startedAtEpochMs, startNs)
        val responsePhase = transformResponse(outgoing, sourced)
        val response = responsePhase.response
        val baseEdited = requestEdited || servedFromMapLocal || responsePhase.edited

        if (breakpoint?.onResponse == true && WailoControlChannel.breakpointGate != null) {
            val delivered = handleResponseBreakpoint(
                outgoing,
                response,
                breakpoint.ruleId,
                baseEdited,
                startedAtEpochMs,
                startNs,
                shouldCapture,
            )
            waitForScriptDelay(responsePhase.delayMillis)
            return delivered
        }

        waitForScriptDelay(responsePhase.delayMillis)
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

    private data class RequestPhase(val request: Request, val edited: Boolean)

    private data class ScriptRequestInput(val model: HttpRequest, val replayable: Boolean)

    private data class ResponsePhase(
        val response: Response,
        val edited: Boolean,
        val delayMillis: Int,
    )

    private fun transformRequest(request: Request): RequestPhase {
        if (
            !WailoScriptStore.matches(
                request.url.toString(),
                request.method,
                ScriptPhase.SCRIPT_PHASE_REQUEST,
            )
        ) {
            return RequestPhase(request, edited = false)
        }
        val transformer = WailoControlChannel.scriptTransformer ?: return RequestPhase(request, false)
        val input = scriptRequest(request)
        val result = transformer.transform(
            phase = ScriptPhase.SCRIPT_PHASE_REQUEST,
            request = input.model,
            requestBodyReplayable = input.replayable,
        ) ?: return RequestPhase(request, false)
        val transformed = result.request ?: return RequestPhase(request, false)
        if (transformed == input.model) return RequestPhase(request, false)
        val applied = runCatching {
            applyScriptRequest(request, transformed, result.request_body_replaced)
        }.getOrNull() ?: return RequestPhase(request, false)
        return RequestPhase(applied, edited = true)
    }

    private fun transformResponse(request: Request, response: Response): ResponsePhase {
        if (
            !WailoScriptStore.matches(
                request.url.toString(),
                request.method,
                ScriptPhase.SCRIPT_PHASE_RESPONSE,
            )
        ) {
            return ResponsePhase(response, edited = false, delayMillis = 0)
        }
        val transformer = WailoControlChannel.scriptTransformer ?: return ResponsePhase(response, false, 0)
        val requestInput = scriptRequest(request)
        val materialized = materializeResponse(response)
        val result = transformer.transform(
            phase = ScriptPhase.SCRIPT_PHASE_RESPONSE,
            request = requestInput.model,
            response = materialized.model,
            requestBodyReplayable = requestInput.replayable,
        ) ?: return ResponsePhase(materialized.response, false, 0)
        val transformed = result.response ?: return ResponsePhase(materialized.response, false, 0)
        if (result.response_body_replaced && !materialized.bodyAvailable) {
            return ResponsePhase(materialized.response, false, 0)
        }
        val applied = runCatching {
            applyScriptResponse(
                materialized.response,
                transformed,
                result.response_body_replaced,
            )
        }.getOrNull() ?: return ResponsePhase(materialized.response, false, 0)
        return ResponsePhase(
            response = applied,
            edited = transformed != materialized.model || result.delay_ms != 0,
            delayMillis = result.delay_ms,
        )
    }

    private fun scriptRequest(request: Request): ScriptRequestInput {
        val body = request.body
        val declared = runCatching { body?.contentLength() ?: 0L }.getOrDefault(-1L)
        val canReplay = body == null ||
            (!body.isDuplex() && !body.isOneShot() && declared <= MAX_SCRIPT_BODY_BYTES)
        if (!canReplay) {
            return ScriptRequestInput(
                HttpRequest(
                    method = request.method,
                    url = request.url.toString(),
                    headers = request.headers.toModel(),
                    body_size = declared,
                    body_truncated = true,
                ),
                replayable = false,
            )
        }
        val bytes = if (body == null) {
            ByteString.EMPTY
        } else {
            runCatching {
                val buffer = Buffer()
                var written = 0L
                val bounded = object : ForwardingSink(buffer) {
                    override fun write(source: Buffer, byteCount: Long) {
                        if (written + byteCount > MAX_SCRIPT_BODY_BYTES) {
                            throw IOException("Script body limit")
                        }
                        super.write(source, byteCount)
                        written += byteCount
                    }
                }.buffer()
                body.writeTo(bounded)
                bounded.flush()
                buffer.readByteString()
            }.getOrElse {
                return ScriptRequestInput(
                    HttpRequest(
                        method = request.method,
                        url = request.url.toString(),
                        headers = request.headers.toModel(),
                        body_size = declared,
                        body_truncated = true,
                    ),
                    replayable = false,
                )
            }
        }
        return ScriptRequestInput(
            HttpRequest(
                method = request.method,
                url = request.url.toString(),
                headers = request.headers.toModel(),
                body = bytes,
                body_size = if (declared >= 0) declared else bytes.size.toLong(),
            ),
            replayable = true,
        )
    }

    private data class MaterializedResponse(
        val response: Response,
        val model: HttpResponse,
        val bodyAvailable: Boolean,
    )

    private fun materializeResponse(response: Response): MaterializedResponse {
        val body = response.body
        val declared = runCatching { body?.contentLength() ?: 0L }.getOrDefault(-1L)
        if (body != null && (declared < 0 || declared > MAX_SCRIPT_BODY_BYTES)) {
            return MaterializedResponse(
                response = response,
                model = HttpResponse(
                    code = response.code,
                    message = response.message,
                    headers = response.headers.toModel(),
                    body_size = declared,
                    body_truncated = true,
                ),
                bodyAvailable = false,
            )
        }
        val contentType = body?.contentType()
        val peeked = if (body == null) {
            ByteArray(0)
        } else {
            response.peekBody(MAX_SCRIPT_BODY_BYTES + 1).bytes()
        }
        if (peeked.size > MAX_SCRIPT_BODY_BYTES) {
            return MaterializedResponse(
                response = response,
                model = HttpResponse(
                    code = response.code,
                    message = response.message,
                    headers = response.headers.toModel(),
                    body_size = declared,
                    body_truncated = true,
                ),
                bodyAvailable = false,
            )
        }
        body?.close()
        val bytes = peeked.toByteString()
        val rebuilt = response.newBuilder().body(bytes.toResponseBody(contentType)).build()
        return MaterializedResponse(
            response = rebuilt,
            model = HttpResponse(
                code = response.code,
                message = response.message,
                headers = response.headers.toModel(),
                body = bytes,
                body_size = bytes.size.toLong(),
            ),
            bodyAvailable = true,
        )
    }

    private fun applyScriptRequest(original: Request, edited: HttpRequest, bodyReplaced: Boolean): Request {
        val headers = if (bodyReplaced) edited.headers.withBodyLength(edited.body.size) else edited.headers
        val okHeaders = headers.toOkHeaders()
        val requestBody = if (bodyReplaced) {
            val contentType = okHeaders["Content-Type"]?.toMediaTypeOrNull()
            when {
                edited.body.size > 0 -> edited.body.toByteArray().toRequestBody(contentType)
                requiresRequestBody(edited.method) -> ByteArray(0).toRequestBody(contentType)
                else -> null
            }
        } else {
            original.body
        }
        return original.newBuilder()
            .url(edited.url)
            .method(edited.method, requestBody)
            .headers(okHeaders)
            .build()
    }

    private fun applyScriptResponse(original: Response, edited: HttpResponse, bodyReplaced: Boolean): Response {
        val headers = if (bodyReplaced) edited.headers.withBodyLength(edited.body.size) else edited.headers
        val okHeaders = headers.toOkHeaders()
        val body = if (bodyReplaced) {
            edited.body.toResponseBody(okHeaders["Content-Type"]?.toMediaTypeOrNull())
        } else {
            original.body
        }
        return original.newBuilder()
            .code(edited.code)
            .headers(okHeaders)
            .body(body)
            .build()
    }

    private fun List<Header>.withBodyLength(size: Int): List<Header> =
        filterNot {
            it.name.equals("Content-Length", true) ||
                it.name.equals("Transfer-Encoding", true) ||
                it.name.equals("Content-Encoding", true)
        } + Header("Content-Length", size.toString())

    private fun waitForScriptDelay(delayMillis: Int) {
        if (delayMillis <= 0) return
        try {
            Thread.sleep(delayMillis.toLong())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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
        const val MAX_SCRIPT_BODY_BYTES = 8L * 1024L * 1024L
    }
}
