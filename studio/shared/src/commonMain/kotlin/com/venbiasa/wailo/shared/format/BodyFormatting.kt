package com.venbiasa.wailo.shared.format

import com.venbiasa.wailo.protocol.Header
import okio.ByteString

/** How a request/response body should be presented in the detail panel. */
internal sealed interface BodyContent {
    data object Empty : BodyContent

    /** Decoded text; [json] is true when it was recognized and pretty-printed as JSON. */
    data class Text(val text: String, val json: Boolean) : BodyContent

    /** Non-text payload we don't render inline; [size] is the number of captured bytes. */
    data class Binary(val size: Int) : BodyContent
}

internal fun List<Header>.contentType(): String? =
    firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value_

/**
 * Decides how to show [body]: empty, binary (by content-type or byte sniffing), or decoded text
 * (pretty-printed when it looks like JSON). Long text is capped at [maxTextChars] to keep the
 * viewer responsive on large payloads.
 */
internal fun bodyContent(body: ByteString, contentType: String?, maxTextChars: Int = 500_000): BodyContent {
    if (body.size == 0) return BodyContent.Empty
    if (isBinaryContentType(contentType) || !isProbablyText(body)) return BodyContent.Binary(body.size)
    val raw = body.utf8()
    val text = if (raw.length > maxTextChars) raw.substring(0, maxTextChars) else raw
    val pretty = if (isLikelyJson(contentType, text)) prettyPrintJson(text) else null
    return BodyContent.Text(pretty ?: text, json = pretty != null)
}

private fun isBinaryContentType(contentType: String?): Boolean {
    val value = contentType?.lowercase() ?: return false
    return value.startsWith("image/") ||
        value.startsWith("audio/") ||
        value.startsWith("video/") ||
        value.startsWith("font/") ||
        value.startsWith("application/octet-stream") ||
        value.startsWith("application/pdf") ||
        value.startsWith("application/zip") ||
        value.startsWith("application/gzip") ||
        value.startsWith("application/x-protobuf") ||
        value.startsWith("application/grpc") ||
        value.startsWith("application/wasm")
}

// Sniff the leading bytes: a NUL byte or a high ratio of control characters means "not text".
private fun isProbablyText(body: ByteString): Boolean {
    val sample = minOf(body.size, 4096)
    if (sample == 0) return true
    var control = 0
    for (i in 0 until sample) {
        val b = body[i].toInt() and 0xFF
        if (b == 0) return false
        if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) control++
    }
    return control * 100 / sample <= 10
}

private fun isLikelyJson(contentType: String?, text: String): Boolean {
    if (contentType != null && contentType.lowercase().contains("json")) return true
    val trimmed = text.trimStart()
    return trimmed.startsWith("{") || trimmed.startsWith("[")
}

/**
 * Reformats valid JSON with two-space indentation. Returns null when [raw] isn't a JSON object or
 * array. It tracks string/escape state so braces, colons, and commas inside string literals are
 * left untouched; it reformats rather than validates, so malformed input yields best-effort output
 * (callers fall back to raw text).
 */
internal fun prettyPrintJson(raw: String, indentUnit: String = "  "): String? {
    val source = raw.trim()
    if (source.isEmpty()) return null
    if (source[0] != '{' && source[0] != '[') return null

    val out = StringBuilder(source.length + source.length / 4)
    var indent = 0
    var index = 0
    var inString = false
    var escaped = false

    fun newline() {
        out.append('\n')
        repeat(indent) { out.append(indentUnit) }
    }

    fun nextNonWhitespace(from: Int): Int {
        var j = from
        while (j < source.length && source[j].isWhitespace()) j++
        return if (j < source.length) j else -1
    }

    while (index < source.length) {
        val c = source[index]
        if (inString) {
            out.append(c)
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            index++
            continue
        }
        when (c) {
            '"' -> {
                inString = true
                out.append(c)
            }
            '{', '[' -> {
                out.append(c)
                val next = nextNonWhitespace(index + 1)
                if (next != -1 && (source[next] == '}' || source[next] == ']')) {
                    out.append(source[next])
                    index = next
                } else {
                    indent++
                    newline()
                }
            }
            '}', ']' -> {
                if (indent > 0) indent--
                newline()
                out.append(c)
            }
            ',' -> {
                out.append(c)
                newline()
            }
            ':' -> out.append(": ")
            ' ', '\t', '\n', '\r' -> Unit
            else -> out.append(c)
        }
        index++
    }
    return out.toString()
}
