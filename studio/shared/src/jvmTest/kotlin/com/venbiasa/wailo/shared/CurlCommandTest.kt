package com.venbiasa.wailo.shared

import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

class CurlCommandTest {

    @Test
    fun includesMethodHeadersAndReadableBodyWithShellSafeQuoting() {
        val request = HttpRequest(
            method = "POST",
            url = "https://api.example.com/o'reilly",
            headers = listOf(
                Header(name = "Authorization", value_ = "Bearer user's-token"),
                Header(name = "Content-Type", value_ = "application/json"),
            ),
            body = """{"name":"O'Reilly"}""".encodeUtf8(),
        )

        assertEquals(
            """
            curl 'https://api.example.com/o'"'"'reilly' \
              -X 'POST' \
              -H 'Authorization: Bearer user'"'"'s-token' \
              -H 'Content-Type: application/json' \
              -d '{"name":"O'"'"'Reilly"}'
            """.trimIndent(),
            request.toCurlCommand(request.body),
        )
    }

    @Test
    fun emptyBodyAddsNoDataOption() {
        val request = HttpRequest(
            method = "GET",
            url = "https://api.example.com/users",
        )

        assertEquals(
            """
            curl 'https://api.example.com/users' \
              -X 'GET'
            """.trimIndent(),
            request.toCurlCommand(request.body),
        )
    }

    @Test
    fun leadingAtBodyUsesDataRawInsteadOfTreatingItAsAFile() {
        val request = HttpRequest(
            method = "POST",
            url = "https://api.example.com/messages",
            body = "@literal".encodeUtf8(),
        )

        assertEquals(
            """
            curl 'https://api.example.com/messages' \
              -X 'POST' \
              --data-raw '@literal'
            """.trimIndent(),
            request.toCurlCommand(request.body),
        )
    }

    @Test
    fun binaryBodyIsPipedWithoutLosingBytes() {
        val request = HttpRequest(
            method = "PUT",
            url = "https://api.example.com/blob",
            body = byteArrayOf(0, 0x27, 0xff.toByte()).toByteString(),
        )

        assertEquals(
            """
            printf '%b' '\0000\0047\0377' | curl 'https://api.example.com/blob' \
              -X 'PUT' \
              --data-binary @-
            """.trimIndent(),
            request.toCurlCommand(request.body),
        )
    }
}
