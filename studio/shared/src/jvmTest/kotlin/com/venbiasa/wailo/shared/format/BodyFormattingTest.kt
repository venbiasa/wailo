package com.venbiasa.wailo.shared.format

import com.venbiasa.wailo.protocol.Header
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyFormattingTest {

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
}
