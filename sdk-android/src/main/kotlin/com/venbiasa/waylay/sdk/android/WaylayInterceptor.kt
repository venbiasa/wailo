package com.venbiasa.waylay.sdk.android

import com.venbiasa.waylay.core.CaptureSink
import com.venbiasa.waylay.protocol.Header
import com.venbiasa.waylay.protocol.HttpExchange
import com.venbiasa.waylay.protocol.HttpRequest
import com.venbiasa.waylay.protocol.HttpResponse
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.UUID

/**
 * OkHttp [Interceptor] that copies each exchange to a [CaptureSink] without
 * consuming the real bodies (request buffered, response via peekBody), capped at [maxBodyBytes].
 */
class WaylayInterceptor internal constructor(
    private val sink: CaptureSink,
    private val maxBodyBytes: Long,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val capturedRequest = captureRequest(request)
        val startedAtEpochMs = System.currentTimeMillis()
        val startNs = System.nanoTime()

        val response: Response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            sink.onExchange(
                HttpExchange(
                    id = newId(),
                    started_at_epoch_ms = startedAtEpochMs,
                    duration_ms = elapsedMs(startNs),
                    request = capturedRequest,
                    error = e.toString(),
                ),
            )
            throw e
        }

        sink.onExchange(
            HttpExchange(
                id = newId(),
                started_at_epoch_ms = startedAtEpochMs,
                duration_ms = elapsedMs(startNs),
                request = capturedRequest,
                response = captureResponse(response),
            ),
        )
        return response
    }

    private fun captureRequest(request: Request): HttpRequest {
        val body = request.body
        var captured = ByteString.EMPTY
        var truncated = false
        // Reading a one-shot or duplex body here would corrupt the real request.
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

    private fun captureResponse(response: Response): HttpResponse {
        val declaredSize = response.body?.contentLength() ?: -1L
        val captured = response.peekBody(maxBodyBytes).bytes()
        val truncated =
            if (declaredSize >= 0) declaredSize > maxBodyBytes else captured.size.toLong() >= maxBodyBytes
        return HttpResponse(
            code = response.code,
            message = response.message,
            headers = response.headers.toModel(),
            body = captured.toByteString(),
            body_size = declaredSize,
            body_truncated = truncated,
        )
    }

    private fun Headers.toModel(): List<Header> =
        (0 until size).map { i -> Header(name = name(i), value_ = value(i)) }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000
}
