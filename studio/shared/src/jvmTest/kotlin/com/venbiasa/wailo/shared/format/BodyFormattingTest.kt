package com.venbiasa.wailo.shared.format

import com.venbiasa.wailo.protocol.Header
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyFormattingTest {

    // Builds a ByteString from unsigned byte values written as Ints (so 0x89 etc. read naturally).
    private fun bytes(vararg values: Int): ByteString =
        ByteArray(values.size) { values[it].toByte() }.toByteString()

    @Test
    fun contentTypeLookupIsCaseInsensitive() {
        val headers = listOf(
            Header(name = "Accept", value_ = "*/*"),
            Header(name = "content-type", value_ = "application/json; charset=utf-8"),
        )
        assertEquals("application/json; charset=utf-8", headers.contentType())
        assertNull(emptyList<Header>().contentType())
    }

    @Test
    fun emptyBodyIsEmpty() {
        assertEquals(BodyContent.Empty, bodyContent(ByteString.EMPTY, "application/json"))
    }

    @Test
    fun jsonBodyIsPrettyPrinted() {
        val content = bodyContent("""{"a":1,"b":[2,3]}""".encodeUtf8(), "application/json")
        assertTrue(content is BodyContent.Text)
        assertTrue(content.json)
        assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    2,\n    3\n  ]\n}", content.text)
    }

    @Test
    fun jsonDetectedByShapeWithoutContentType() {
        val content = bodyContent("[1,2]".encodeUtf8(), contentType = null)
        assertTrue(content is BodyContent.Text)
        assertTrue(content.json)
    }

    @Test
    fun plainTextStaysRaw() {
        val content = bodyContent("hello world".encodeUtf8(), "text/plain")
        assertTrue(content is BodyContent.Text)
        assertEquals("hello world", content.text)
        assertTrue(!content.json)
    }

    @Test
    fun binaryContentTypeIsNotDecoded() {
        val bytes = "not actually a png".encodeUtf8()
        val content = bodyContent(bytes, "image/png")
        assertEquals(BodyContent.Binary(bytes.size), content)
    }

    @Test
    fun nulBytesAreTreatedAsBinary() {
        // A NUL byte in the sample marks the payload as non-text regardless of content-type.
        val bytes = "a\u0000b".encodeUtf8()
        assertEquals(BodyContent.Binary(bytes.size), bodyContent(bytes, contentType = null))
    }

    @Test
    fun prettyPrintJsonHandlesEmptyContainersAndStrings() {
        assertEquals("{}", prettyPrintJson("{}"))
        assertEquals("[]", prettyPrintJson("[ ]"))
        // Structural characters inside a string literal must be left untouched.
        assertEquals("{\n  \"m\": \"a,b:{}\"\n}", prettyPrintJson("""{"m":"a,b:{}"}"""))
        // Escaped quote does not end the string.
        assertEquals("{\n  \"a\": \"x\\\"y\"\n}", prettyPrintJson("""{"a":"x\"y"}"""))
    }

    @Test
    fun prettyPrintJsonRejectsNonJson() {
        assertNull(prettyPrintJson("just a string"))
        assertNull(prettyPrintJson("42"))
        assertNull(prettyPrintJson(""))
    }

    @Test
    fun sniffImageFormatReadsMagicBytes() {
        assertEquals(ImageFormat.Png, sniffImageFormat(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(ImageFormat.Jpeg, sniffImageFormat(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(ImageFormat.Gif, sniffImageFormat(bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)))
        assertEquals(ImageFormat.Bmp, sniffImageFormat(bytes(0x42, 0x4D, 0x00, 0x00)))
        assertEquals(
            ImageFormat.Webp,
            sniffImageFormat(bytes(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)),
        )
        assertNull(sniffImageFormat("just text".encodeUtf8()))
        assertNull(sniffImageFormat(bytes(0x89))) // too short to have a signature
    }

    @Test
    fun imageBodyOffersOnlyTheImage() {
        // A recognized image needs no hex dump: the bytes are already identified, and the Raw tab has
        // them verbatim for anyone who wants them.
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
        val analysis = analyzeBody(png, "image/png", truncated = false)
        assertFalse(analysis.isEmpty)
        assertEquals(ImageFormat.Png, analysis.imageFormat)
        assertEquals(PreviewKind.Image, analysis.default)
        assertEquals(listOf(PreviewKind.Image), analysis.previewers)
    }

    @Test
    fun truncatedImageFallsBackToHex() {
        // A body cut off mid-capture can't be decoded, so image detection is skipped and the raw
        // bytes (a NUL makes them non-text) resolve to Hex.
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
        val analysis = analyzeBody(png, contentType = null, truncated = true)
        assertNull(analysis.imageFormat)
        assertFalse(analysis.previewers.contains(PreviewKind.Image))
        assertEquals(listOf(PreviewKind.Hex), analysis.previewers)
    }

    @Test
    fun jsonBodyOffersOnlyTheTree() {
        // JSON is shown as an interactive tree with no Text/Hex toggle (Raw tab still has the bytes).
        val analysis = analyzeBody("""{"a":1}""".encodeUtf8(), "application/json", truncated = false)
        assertEquals(PreviewKind.Json, analysis.default)
        assertEquals(listOf(PreviewKind.Json), analysis.previewers)
        assertNull(analysis.imageFormat)
    }

    @Test
    fun htmlBodyDetectedByContentTypeOrDoctype() {
        assertEquals(
            listOf(PreviewKind.Html, PreviewKind.Text),
            analyzeBody("<p>hi</p>".encodeUtf8(), "text/html; charset=utf-8", truncated = false).previewers,
        )
        assertEquals(
            PreviewKind.Html,
            analyzeBody("<!DOCTYPE html><html></html>".encodeUtf8(), contentType = null, truncated = false).default,
        )
    }

    @Test
    fun xmlBodyOffersXmlThenText() {
        // Markup keeps the plain-text escape (a Content-Type can lie) but not hex — it is text either way.
        val analysis = analyzeBody("""<?xml version="1.0"?><a/>""".encodeUtf8(), "application/xml", truncated = false)
        assertEquals(PreviewKind.Xml, analysis.default)
        assertEquals(listOf(PreviewKind.Xml, PreviewKind.Text), analysis.previewers)
    }

    @Test
    fun formBodyOffersFormThenText() {
        val analysis = analyzeBody(
            "a=1&b=2".encodeUtf8(),
            "application/x-www-form-urlencoded",
            truncated = false,
        )
        assertEquals(PreviewKind.Form, analysis.default)
        assertEquals(listOf(PreviewKind.Form, PreviewKind.Text), analysis.previewers)
    }

    @Test
    fun plainTextOffersOnlyText() {
        val analysis = analyzeBody("hello world".encodeUtf8(), "text/plain", truncated = false)
        assertEquals(PreviewKind.Text, analysis.default)
        assertEquals(listOf(PreviewKind.Text), analysis.previewers)
    }

    @Test
    fun contentEncodingReportsOnlyARealCoding() {
        assertEquals("br", listOf(Header(name = "content-encoding", value_ = " br ")).contentEncoding())
        assertNull(listOf(Header(name = "Content-Encoding", value_ = "identity")).contentEncoding())
        assertNull(emptyList<Header>().contentEncoding())
    }

    @Test
    fun anUndecodedBodyIsOnlyEverADump() {
        // Compressed JSON is not JSON, and a short enough payload can even pass the text sniff — so the
        // encoded flag has to override what the bytes look like, not merely add a caveat to it.
        val analysis =
            analyzeBody("looks like text".encodeUtf8(), "application/json", truncated = false, encoded = true)
        assertEquals(listOf(PreviewKind.Hex), analysis.previewers)
        assertEquals(PreviewKind.Hex, analysis.default)
        assertNull(analysis.imageFormat)
    }

    @Test
    fun aSavedBodyIsNamedForWhatTheBytesAre() {
        val json = """{"a":1}""".encodeUtf8()
        assertEquals("search.json", bodyFileName("search", json, "application/json", false, null))
        // The path already agreed, so it is not said twice.
        assertEquals("search.json", bodyFileName("search.json", json, "application/json", false, null))
        // The magic bytes outvote both the path and the header, and the disagreement stays visible.
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals("logo.jpg.png", bodyFileName("logo.jpg", png, "image/jpeg", false, null))
        // Nothing identified it, so the name claims nothing either.
        assertEquals("blob.bin", bodyFileName("blob", bytes(0x00, 0x01, 0x02, 0x03), null, false, null))
    }

    @Test
    fun anUndecodedBodyIsNamedForItsCoding() {
        // The file holds compressed bytes; calling it .json would name the thing inside the archive.
        val json = """{"a":1}""".encodeUtf8()
        assertEquals("search.br", bodyFileName("search", json, "application/json", false, "br"))
        assertEquals("search.json", bodyFileName("search", json, "application/json", false, null))
        // Every layer is named, and the punctuation that separates them cannot reach the file name.
        assertEquals("search.gzip-br", bodyFileName("search", json, "application/json", false, "gzip, br"))
    }

    @Test
    fun binaryBodyOffersOnlyHex() {
        val analysis = analyzeBody("a\u0000b".encodeUtf8(), contentType = null, truncated = false)
        assertEquals(listOf(PreviewKind.Hex), analysis.previewers)
        assertEquals(PreviewKind.Hex, analysis.default)
    }

    @Test
    fun emptyBodyAnalyzesAsEmpty() {
        val analysis = analyzeBody(ByteString.EMPTY, "application/json", truncated = false)
        assertTrue(analysis.isEmpty)
        assertTrue(analysis.previewers.isEmpty())
    }

    @Test
    fun parseFormUrlEncodedDecodesPairs() {
        assertEquals(
            listOf("a" to "1", "b" to "hello world", "c" to "é"),
            parseFormUrlEncoded("a=1&b=hello+world&c=%C3%A9"),
        )
        // A bare key (no '=') yields an empty value; empty segments are dropped.
        assertEquals(listOf("flag" to ""), parseFormUrlEncoded("flag"))
        assertTrue(parseFormUrlEncoded("").isEmpty())
    }

    @Test
    fun hexDumpLineFormatsOffsetBytesAndAscii() {
        val line = hexDumpLine(bytes(0x41, 0x42, 0x00, 0xFF), start = 0)
        assertTrue(line.startsWith("00000000  41 42 00 ff"), "unexpected line: $line")
        assertTrue(line.trimEnd().endsWith("AB.."), "unexpected ascii gutter: $line")
    }

    @Test
    fun parseJsonBuildsOrderedTree() {
        val node = parseJson("""{"b":1,"a":[true,null,"x"],"n":-2.5e3}""")
        assertTrue(node is JsonNode.Obj)
        // Object key order is preserved (b before a) for a faithful view.
        assertEquals(listOf("b", "a", "n"), node.entries.map { it.key })
        assertEquals(JsonNode.Num("1"), node.entries[0].value)

        val arr = node.entries[1].value
        assertTrue(arr is JsonNode.Arr)
        assertEquals(listOf(JsonNode.Bool(true), JsonNode.Null, JsonNode.Str("x")), arr.items)

        assertEquals(JsonNode.Num("-2.5e3"), node.entries[2].value)
    }

    @Test
    fun parseJsonDecodesStringEscapes() {
        val node = parseJson("""{"k":"a\"b\n\u00e9"}""")
        assertTrue(node is JsonNode.Obj)
        assertEquals(JsonNode.Str("a\"b\né"), node.entries[0].value)
    }

    @Test
    fun parseJsonHandlesEmptyContainers() {
        assertEquals(JsonNode.Obj(emptyList()), parseJson("{}"))
        assertEquals(JsonNode.Arr(emptyList()), parseJson("[]"))
    }

    @Test
    fun parseJsonRejectsMalformedInput() {
        assertNull(parseJson("""{"a":}"""))
        assertNull(parseJson("""{"a":1,}"""))
        assertNull(parseJson("[1,2")) // unterminated
        assertNull(parseJson("""{"a":1}trailing"""))
        assertNull(parseJson(""))
    }

    @Test
    fun jsonErrorMessageAcceptsWellFormedAndBlank() {
        // The editor treats a blank body as "no error" (an empty body is allowed).
        assertNull(jsonErrorMessage(""))
        assertNull(jsonErrorMessage("   \n  "))
        assertNull(jsonErrorMessage("""{"a":1,"b":[2,3]}"""))
        assertNull(jsonErrorMessage("[]"))
    }

    @Test
    fun jsonErrorMessageReportsFailureWithLineAndColumn() {
        val missingValue = jsonErrorMessage("""{"a":}""")
        assertTrue(missingValue != null && missingValue.contains("line 1"), "expected a line reference: $missingValue")

        // The failure position tracks newlines, so a later line is reported as such.
        val multiline = jsonErrorMessage("{\n  \"a\": 1,\n  \"b\": ,\n}")
        assertTrue(multiline != null && multiline.contains("line 3"), "expected line 3: $multiline")

        // Extra content after a complete value is flagged rather than silently accepted.
        assertTrue(jsonErrorMessage("""{"a":1} garbage""") != null)
    }

    @Test
    fun canonicalJsonSortsKeysSoKeyOrderIsNotADifference() {
        val one = parseJson("""{"b":1,"a":{"z":true,"y":null}}""")!!
        val other = parseJson("""{"a":{"y":null,"z":true},"b":1}""")!!
        assertEquals(canonicalJson(one, sortKeys = true), canonicalJson(other, sortKeys = true))
    }

    @Test
    fun canonicalJsonPreservesSourceOrderWhenSortingIsOff() {
        val node = parseJson("""{"b":1,"a":2}""")!!
        assertEquals("{\n  \"b\": 1,\n  \"a\": 2\n}", canonicalJson(node, sortKeys = false))
    }

    @Test
    fun canonicalJsonKeepsArrayOrderAndEmptyContainersInline() {
        // Array position is meaning, not formatting, so sorting keys must never reach inside one.
        val node = parseJson("""{"items":[3,1,2],"none":{},"empty":[]}""")!!
        assertEquals(
            "{\n  \"empty\": [],\n  \"items\": [\n    3,\n    1,\n    2\n  ],\n  \"none\": {}\n}",
            canonicalJson(node, sortKeys = true),
        )
    }

    @Test
    fun canonicalJsonRoundTripsThroughTheParser() {
        val source = """{"s":"a \"quoted\" \n line","n":-1.5e3,"t":true,"z":null}"""
        val once = canonicalJson(parseJson(source)!!, sortKeys = true)
        // Re-parsing the output has to yield the same text, or a diff of two canonical bodies would be
        // comparing something that is no longer valid JSON.
        assertEquals(once, canonicalJson(parseJson(once)!!, sortKeys = true))
        // The number keeps its source spelling rather than being reformatted through a Double.
        assertTrue(once.contains("-1.5e3"))
    }
}
