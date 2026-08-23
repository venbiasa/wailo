package com.venbiasa.wailo.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpTextContentTest {
    @Test
    fun aListOfRowsReachesTheClientAsTextAndNotOnlyAsACount() {
        val response = McpToolResponse(
            "2 exchange(s)",
            mapOf(
                "exchanges" to listOf(
                    mapOf("id" to "a1", "method" to "GET", "url" to "https://example.test/one", "status_code" to 200),
                    mapOf("id" to "b2", "method" to "POST", "url" to "https://example.test/two", "status_code" to 500),
                ),
            ),
        )

        assertEquals(
            """
            2 exchange(s)
            exchanges:
              - id: a1
                method: GET
                url: https://example.test/one
                status_code: 200
              - id: b2
                method: POST
                url: https://example.test/two
                status_code: 500
            """.trimIndent(),
            mcpTextContent(response),
        )
    }

    @Test
    fun aBodyKeepsItsLineBreaksInsteadOfCollapsingToOneLine() {
        val text = renderMcpData(mapOf("body" to mapOf("encoding" to "utf8", "text" to "{\n  \"a\": 1\n}")))

        assertEquals(
            """
            body:
              encoding: utf8
              text: |
                {
                  "a": 1
                }
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun nullsAndEmptyCollectionsAreLeftOutRatherThanPrintedAsNoise() {
        val text = renderMcpData(
            mapOf(
                "id" to "a1",
                "error" to null,
                "headers" to emptyList<Any>(),
                "request" to emptyMap<String, Any>(),
                "status_code" to 204,
            ),
        )

        assertEquals("id: a1\nstatus_code: 204", text)
    }

    @Test
    fun aRowWhoseFieldsAreAllEmptyStillPrintsAsARowSoTheCountMatches() {
        val text = renderMcpData(mapOf("rows" to listOf(mapOf("a" to null), mapOf("b" to 1))))

        assertEquals("rows:\n  - {}\n  - b: 1", text)
    }

    @Test
    fun nestedStructureUnderAListRowStaysUnderThatRow() {
        val text = renderMcpData(
            mapOf(
                "exchanges" to listOf(
                    mapOf(
                        "id" to "a1",
                        "request" to mapOf("method" to "GET", "headers" to listOf(mapOf("name" to "Accept"))),
                    ),
                ),
            ),
        )

        assertEquals(
            """
            exchanges:
              - id: a1
                request:
                  method: GET
                  headers:
                    - name: Accept
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun aFailureSaysItsMessageOnceRatherThanTwice() {
        val message = "Wailo AI tool access is turned off."
        val response = McpToolResponse(message, mapOf("error" to message), isError = true)

        assertEquals(message, mcpTextContent(response))
    }

    @Test
    fun aSummaryWithNoPayloadIsUnchanged() {
        assertEquals("cleared", mcpTextContent(McpToolResponse("cleared", emptyMap())))
    }

    @Test
    fun redactedValuesStayRedactedBecauseTheTextComesFromTheSamePayload() {
        val response = McpToolResponse(
            "1 exchange(s)",
            mapOf("exchanges" to listOf(mapOf("url" to "https://example.test/x?token=<wailo:redacted>"))),
        )

        val text = mcpTextContent(response)

        assertTrue(text.contains("<wailo:redacted>"))
        assertFalse(text.contains("token=s3cret"))
    }
}
