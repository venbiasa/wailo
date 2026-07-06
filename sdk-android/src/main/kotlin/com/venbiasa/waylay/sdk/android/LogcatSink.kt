package com.venbiasa.waylay.sdk.android

import android.util.Log
import com.venbiasa.waylay.core.CaptureSink
import com.venbiasa.waylay.protocol.HttpExchange
import com.venbiasa.waylay.protocol.HttpRequest
import com.venbiasa.waylay.protocol.HttpResponse
import okio.ByteString

/** [CaptureSink] that prints exchanges to Logcat — the M1 verification surface. */
class LogcatSink(
    private val tag: String = DEFAULT_TAG,
    private val maxBodyPreviewChars: Int = DEFAULT_MAX_BODY_PREVIEW_CHARS,
) : CaptureSink {

    override fun onExchange(exchange: HttpExchange) {
        Log.d(tag, summaryLine(exchange))
        exchange.request?.let(::logRequest)
        exchange.response?.let(::logResponse)
    }

    private fun summaryLine(exchange: HttpExchange): String = buildString {
        val request = exchange.request
        append(request?.method ?: "?")
        append(' ')
        append(request?.url ?: "?")
        exchange.response?.let { response ->
            append(" -> ").append(response.code)
            if (response.message.isNotEmpty()) append(' ').append(response.message)
        }
        if (exchange.error.isNotEmpty()) append(" -> FAILED: ").append(exchange.error)
        append(" (").append(exchange.duration_ms).append("ms)")
    }

    private fun logRequest(request: HttpRequest) {
        for (header in request.headers) Log.d(tag, "  > ${header.name}: ${header.value_}")
        bodyPreview(request.body, request.body_truncated)?.let { Log.d(tag, "  > body: $it") }
    }

    private fun logResponse(response: HttpResponse) {
        for (header in response.headers) Log.d(tag, "  < ${header.name}: ${header.value_}")
        bodyPreview(response.body, response.body_truncated)?.let { Log.d(tag, "  < body: $it") }
    }

    private fun bodyPreview(body: ByteString, truncated: Boolean): String? {
        if (body.size == 0) return null
        if (!body.isProbablyText()) return "<binary ${body.size} bytes>"
        val text = body.utf8()
        val clipped =
            if (text.length > maxBodyPreviewChars) text.take(maxBodyPreviewChars) + "…" else text
        return if (truncated) "$clipped … (truncated)" else clipped
    }

    /** Cheap guard so binary payloads are summarized instead of dumped as mojibake. */
    private fun ByteString.isProbablyText(): Boolean {
        val prefix = minOf(size, TEXT_SNIFF_BYTES)
        for (i in 0 until prefix) {
            val c = this[i].toInt() and 0xff
            val isDisallowedControl = c == 0x00 || c < 0x09 || c in 0x0e..0x1f
            if (isDisallowedControl) return false
        }
        return true
    }

    companion object {
        const val DEFAULT_TAG: String = "Waylay"
        const val DEFAULT_MAX_BODY_PREVIEW_CHARS: Int = 4000
        private const val TEXT_SNIFF_BYTES: Int = 64
    }
}
