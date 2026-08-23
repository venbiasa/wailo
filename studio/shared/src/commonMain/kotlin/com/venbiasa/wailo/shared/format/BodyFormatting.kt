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

/** A way of rendering a body. The detail panel offers the applicable ones per response. */
internal enum class PreviewKind { Json, Html, Xml, Form, Text, Image, Hex }

/** Image container detected by magic bytes, independent of a possibly wrong/absent Content-Type. */
internal enum class ImageFormat { Png, Jpeg, Gif, Webp, Bmp }

/**
 * The set of previewers that fit a body, best-first, plus the [default] to open with. Purely a
 * classification of *what* the body is; deciding *how* to draw each kind is the UI's job. [imageFormat]
 * is non-null only when image magic bytes were found (and the body wasn't truncated mid-capture).
 */
internal data class BodyAnalysis(
    val isEmpty: Boolean,
    val previewers: List<PreviewKind>,
    val default: PreviewKind,
    val imageFormat: ImageFormat?,
)

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

/**
 * Classifies [body] into the previewers that fit it, best-first. Detection is intentionally cheap
 * (content-type, magic bytes, and a short decoded prefix) so it can run on every selection change;
 * the heavy work (pretty-printing, hex formatting, image decoding) is deferred to the chosen
 * previewer. Hex is always offered as the last resort for a non-empty body, and text-shaped bodies
 * keep a plain-Text fallback so a mis-sniff is never a dead end. A [truncated] body can't be a valid
 * image, so image detection is skipped and it falls through to the byte/text path.
 */
internal fun analyzeBody(body: ByteString, contentType: String?, truncated: Boolean): BodyAnalysis {
    if (body.size == 0) {
        return BodyAnalysis(isEmpty = true, previewers = emptyList(), default = PreviewKind.Text, imageFormat = null)
    }
    val imageFormat = if (truncated) null else sniffImageFormat(body)
    if (imageFormat != null) {
        return BodyAnalysis(false, listOf(PreviewKind.Image, PreviewKind.Hex), PreviewKind.Image, imageFormat)
    }
    if (isBinaryContentType(contentType) || !isProbablyText(body)) {
        return BodyAnalysis(false, listOf(PreviewKind.Hex), PreviewKind.Hex, null)
    }
    // Sniff a decoded prefix rather than the whole payload: shape checks only look at the leading
    // characters, so a few KB is enough to distinguish JSON/HTML/XML from plain text.
    val prefix = body.substring(0, minOf(body.size, 4096)).utf8()
    val kind = when {
        isFormContentType(contentType) -> PreviewKind.Form
        isLikelyJson(contentType, prefix) -> PreviewKind.Json
        isHtml(contentType, prefix) -> PreviewKind.Html
        isXml(contentType, prefix) -> PreviewKind.Xml
        else -> PreviewKind.Text
    }
    val previewers = when (kind) {
        // JSON gets the read-only code editor only — no Text/Hex toggle. The full raw bytes
        // are still one click away on the Raw tab, so nothing is lost.
        PreviewKind.Json -> listOf(PreviewKind.Json)
        PreviewKind.Text -> listOf(PreviewKind.Text, PreviewKind.Hex)
        else -> listOf(kind, PreviewKind.Text, PreviewKind.Hex)
    }
    return BodyAnalysis(false, previewers, kind, null)
}

/**
 * Detects an image container from its leading magic bytes. This is the source of truth for the image
 * previewer (not the Content-Type header), so a mislabeled or header-less image still previews.
 */
internal fun sniffImageFormat(body: ByteString): ImageFormat? {
    if (body.size < 4) return null
    fun b(i: Int): Int = body[i].toInt() and 0xFF
    return when {
        b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> ImageFormat.Png
        b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> ImageFormat.Jpeg
        b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 -> ImageFormat.Gif
        b(0) == 0x42 && b(1) == 0x4D -> ImageFormat.Bmp
        body.size >= 12 && b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 &&
            b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50 -> ImageFormat.Webp
        else -> null
    }
}

private fun isFormContentType(contentType: String?): Boolean =
    contentType?.lowercase()?.startsWith("application/x-www-form-urlencoded") == true

private fun isHtml(contentType: String?, text: String): Boolean {
    if (contentType?.lowercase()?.contains("html") == true) return true
    val head = text.trimStart().lowercase()
    return head.startsWith("<!doctype html") || head.startsWith("<html")
}

private fun isXml(contentType: String?, text: String): Boolean {
    val ct = contentType?.lowercase()
    if (ct != null && (ct.contains("xml") || ct.endsWith("+xml"))) return true
    val head = text.trimStart()
    // Reached only after the HTML check, so a leading '<' that isn't HTML reads as generic markup.
    return head.startsWith("<?xml") || head.startsWith("<")
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

/** A parsed JSON value, preserving object key order for a faithful, collapsible tree view. */
internal sealed interface JsonNode {
    data class Obj(val entries: List<Entry>) : JsonNode
    data class Arr(val items: List<JsonNode>) : JsonNode

    /** A string with its escapes already decoded; the renderer re-escapes for display. */
    data class Str(val value: String) : JsonNode

    /** A number kept as its source text so precision/format survive round-tripping. */
    data class Num(val text: String) : JsonNode
    data class Bool(val value: Boolean) : JsonNode
    data object Null : JsonNode

    data class Entry(val key: String, val value: JsonNode)
}

/**
 * Parses [text] into a [JsonNode] tree, or null if it isn't well-formed JSON (the caller then falls
 * back to raw text). Hand-rolled to match the module's dependency-light stance — same reason
 * [prettyPrintJson] is hand-rolled rather than pulling in a JSON library.
 */
internal fun parseJson(text: String): JsonNode? {
    val parser = JsonParser(text)
    return try {
        parser.skipWhitespace()
        val node = parser.parseValue()
        parser.skipWhitespace()
        if (parser.atEnd()) node else null
    } catch (_: JsonParseException) {
        null
    }
}

/**
 * Re-serializes a [JsonNode] tree with two-space indentation, optionally sorting each object's keys.
 *
 * Sorting is what makes two JSON bodies comparable line by line: a server is free to emit its keys in any
 * order, and a serializer that changes that order between two captures would otherwise show up as a diff
 * across the whole payload. Ordering is by the raw key string so it is stable regardless of locale.
 */
internal fun canonicalJson(node: JsonNode, sortKeys: Boolean, indentUnit: String = "  "): String {
    val out = StringBuilder()
    writeJson(node, sortKeys, indentUnit, 0, out)
    return out.toString()
}

private fun writeJson(node: JsonNode, sortKeys: Boolean, indentUnit: String, depth: Int, out: StringBuilder) {
    fun newline(level: Int) {
        out.append('\n')
        repeat(level) { out.append(indentUnit) }
    }
    when (node) {
        is JsonNode.Obj -> {
            if (node.entries.isEmpty()) {
                out.append("{}")
                return
            }
            out.append('{')
            val entries = if (sortKeys) node.entries.sortedBy { it.key } else node.entries
            entries.forEachIndexed { index, entry ->
                if (index > 0) out.append(',')
                newline(depth + 1)
                writeJsonString(entry.key, out)
                out.append(": ")
                writeJson(entry.value, sortKeys, indentUnit, depth + 1, out)
            }
            newline(depth)
            out.append('}')
        }
        is JsonNode.Arr -> {
            if (node.items.isEmpty()) {
                out.append("[]")
                return
            }
            out.append('[')
            node.items.forEachIndexed { index, item ->
                if (index > 0) out.append(',')
                newline(depth + 1)
                writeJson(item, sortKeys, indentUnit, depth + 1, out)
            }
            newline(depth)
            out.append(']')
        }
        is JsonNode.Str -> writeJsonString(node.value, out)
        is JsonNode.Num -> out.append(node.text)
        is JsonNode.Bool -> out.append(if (node.value) "true" else "false")
        JsonNode.Null -> out.append("null")
    }
}

// Re-escapes a decoded string literal. Control characters below 0x20 without a short escape take the \\uXXXX
// form, which is the only representation JSON allows for them.
private fun writeJsonString(value: String, out: StringBuilder) {
    out.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else -> if (ch < ' ') {
                out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            } else {
                out.append(ch)
            }
        }
    }
    out.append('"')
}

/**
 * Validates [text] as JSON for the body editor: null when it's well-formed (or blank — an empty body
 * is allowed), otherwise a short message with the 1-based line/column of the failure. Reuses the same
 * hand-rolled parser as [parseJson] so the editor's verdict matches how a body is actually parsed.
 */
internal fun jsonErrorMessage(text: String): String? {
    if (text.isBlank()) return null
    val parser = JsonParser(text)
    return try {
        parser.skipWhitespace()
        parser.parseValue()
        parser.skipWhitespace()
        if (parser.atEnd()) null else "Unexpected trailing content at ${lineCol(text, parser.pos())}"
    } catch (_: JsonParseException) {
        "Invalid JSON at ${lineCol(text, parser.pos())}"
    }
}

// 1-based line/column for a character offset, for the editor's validation hint.
private fun lineCol(text: String, index: Int): String {
    val end = index.coerceIn(0, text.length)
    var line = 1
    var col = 1
    for (k in 0 until end) {
        if (text[k] == '\n') {
            line++
            col = 1
        } else {
            col++
        }
    }
    return "line $line, column $col"
}

private class JsonParseException : Exception()

private class JsonParser(private val s: String) {
    private var i = 0

    // The scan position, exposed so the editor's validation can report where parsing failed.
    fun pos(): Int = i

    fun atEnd(): Boolean = i >= s.length

    fun skipWhitespace() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    fun parseValue(): JsonNode {
        skipWhitespace()
        if (atEnd()) throw JsonParseException()
        return when (s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonNode.Str(parseString())
            't' -> { literal("true"); JsonNode.Bool(true) }
            'f' -> { literal("false"); JsonNode.Bool(false) }
            'n' -> { literal("null"); JsonNode.Null }
            else -> parseNumber()
        }
    }

    private fun parseObject(): JsonNode {
        i++ // consume '{'
        skipWhitespace()
        val entries = ArrayList<JsonNode.Entry>()
        if (!atEnd() && s[i] == '}') {
            i++
            return JsonNode.Obj(entries)
        }
        while (true) {
            skipWhitespace()
            if (atEnd() || s[i] != '"') throw JsonParseException()
            val key = parseString()
            skipWhitespace()
            if (atEnd() || s[i] != ':') throw JsonParseException()
            i++
            entries.add(JsonNode.Entry(key, parseValue()))
            skipWhitespace()
            if (atEnd()) throw JsonParseException()
            when (s[i++]) {
                ',' -> Unit
                '}' -> return JsonNode.Obj(entries)
                else -> throw JsonParseException()
            }
        }
    }

    private fun parseArray(): JsonNode {
        i++ // consume '['
        skipWhitespace()
        val items = ArrayList<JsonNode>()
        if (!atEnd() && s[i] == ']') {
            i++
            return JsonNode.Arr(items)
        }
        while (true) {
            items.add(parseValue())
            skipWhitespace()
            if (atEnd()) throw JsonParseException()
            when (s[i++]) {
                ',' -> Unit
                ']' -> return JsonNode.Arr(items)
                else -> throw JsonParseException()
            }
        }
    }

    private fun parseString(): String {
        i++ // consume opening quote
        val sb = StringBuilder()
        while (true) {
            if (atEnd()) throw JsonParseException()
            when (val c = s[i++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (atEnd()) throw JsonParseException()
                    when (val esc = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) throw JsonParseException()
                            val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw JsonParseException()
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> throw JsonParseException()
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): JsonNode {
        val start = i
        if (!atEnd() && s[i] == '-') i++
        when {
            atEnd() -> throw JsonParseException()
            s[i] == '0' -> i++
            s[i] in '1'..'9' -> while (!atEnd() && s[i] in '0'..'9') i++
            else -> throw JsonParseException()
        }
        if (!atEnd() && s[i] == '.') {
            i++
            if (atEnd() || s[i] !in '0'..'9') throw JsonParseException()
            while (!atEnd() && s[i] in '0'..'9') i++
        }
        if (!atEnd() && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (!atEnd() && (s[i] == '+' || s[i] == '-')) i++
            if (atEnd() || s[i] !in '0'..'9') throw JsonParseException()
            while (!atEnd() && s[i] in '0'..'9') i++
        }
        return JsonNode.Num(s.substring(start, i))
    }

    private fun literal(word: String) {
        if (i + word.length > s.length || s.substring(i, i + word.length) != word) throw JsonParseException()
        i += word.length
    }
}

/**
 * Parses an `application/x-www-form-urlencoded` body into ordered name/value pairs, percent- and
 * `+`-decoded. Order is preserved (forms are positional) and a key without `=` yields an empty value.
 */
internal fun parseFormUrlEncoded(text: String): List<Pair<String, String>> =
    text.split('&').mapNotNull { pair ->
        if (pair.isEmpty()) return@mapNotNull null
        val eq = pair.indexOf('=')
        if (eq < 0) {
            formDecode(pair) to ""
        } else {
            formDecode(pair.substring(0, eq)) to formDecode(pair.substring(eq + 1))
        }
    }

// Percent-decodes a single form token: `+` -> space, `%XX` -> that byte, other chars pass through as
// their UTF-8 bytes; the accumulated bytes are then read back as UTF-8 (so multi-byte escapes join).
private fun formDecode(token: String): String {
    if ('%' !in token && '+' !in token) return token
    val bytes = ArrayList<Byte>(token.length)
    var i = 0
    while (i < token.length) {
        val c = token[i]
        when {
            c == '+' -> {
                bytes.add(0x20)
                i++
            }
            c == '%' && i + 2 < token.length && token[i + 1].isHexDigit() && token[i + 2].isHexDigit() -> {
                bytes.add(((token[i + 1].hexValue() shl 4) or token[i + 2].hexValue()).toByte())
                i += 3
            }
            else -> {
                val next = if (c.isHighSurrogate() && i + 1 < token.length) i + 2 else i + 1
                token.substring(i, next).encodeToByteArray().forEach { bytes.add(it) }
                i = next
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun Char.hexValue(): Int = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    else -> this - 'A' + 10
}

/**
 * Renders one 16-byte row of a hex dump: `offset  hex bytes  ascii`, with non-printable bytes shown
 * as `.`. [limit] caps the readable range so a windowed viewer never reads past what it means to show.
 */
internal fun hexDumpLine(body: ByteString, start: Int, limit: Int = body.size): String {
    val end = minOf(start + 16, limit)
    val sb = StringBuilder()
    sb.append(start.toString(16).padStart(8, '0')).append("  ")
    for (col in 0 until 16) {
        val i = start + col
        if (i < end) sb.append((body[i].toInt() and 0xFF).toString(16).padStart(2, '0')) else sb.append("  ")
        sb.append(' ')
        if (col == 7) sb.append(' ')
    }
    sb.append(' ')
    for (i in start until end) {
        val byte = body[i].toInt() and 0xFF
        sb.append(if (byte in 0x20..0x7E) byte.toChar() else '.')
    }
    return sb.toString()
}
