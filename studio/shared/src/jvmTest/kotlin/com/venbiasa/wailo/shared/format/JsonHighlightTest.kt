package com.venbiasa.wailo.shared.format

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonHighlightTest {

    private fun tokensOf(line: String): List<Pair<String, JsonToken>> =
        jsonHighlightSpans(line).map { line.substring(it.start, it.end) to it.token }

    @Test
    fun emptyLineHasNoSpans() {
        assertEquals(emptyList(), jsonHighlightSpans(""))
    }

    @Test
    fun stringBeforeColonIsAKey() {
        assertEquals(
            listOf("\"name\"" to JsonToken.Key, ":" to JsonToken.Punctuation, "\"bob\"" to JsonToken.StringValue),
            tokensOf("\"name\": \"bob\""),
        )
    }

    @Test
    fun keyDetectionSkipsSpacesBeforeColon() {
        assertEquals(JsonToken.Key, jsonHighlightSpans("\"k\"   :").first().token)
        // A string not followed by a colon is a value.
        assertEquals(JsonToken.StringValue, jsonHighlightSpans("\"k\"  ,").first().token)
    }

    @Test
    fun numbersBooleansAndNull() {
        assertEquals(
            listOf(
                "-12.5e3" to JsonToken.Number,
                "," to JsonToken.Punctuation,
                "true" to JsonToken.Keyword,
                "," to JsonToken.Punctuation,
                "null" to JsonToken.Keyword,
            ),
            tokensOf("-12.5e3,true,null"),
        )
    }

    @Test
    fun bracketsAndBracesArePunctuation() {
        assertEquals(
            listOf("{" to JsonToken.Punctuation, "[" to JsonToken.Punctuation, "]" to JsonToken.Punctuation, "}" to JsonToken.Punctuation),
            tokensOf("{[]}"),
        )
    }

    @Test
    fun escapedQuoteDoesNotTerminateString() {
        // The escaped quote is inside the string; the whole literal is one span.
        assertEquals(listOf("\"a\\\"b\"" to JsonToken.StringValue), tokensOf("\"a\\\"b\""))
    }

    @Test
    fun unterminatedStringRunsToEndOfLine() {
        assertEquals(listOf("\"oops" to JsonToken.StringValue), tokensOf("\"oops"))
    }

    @Test
    fun trueFalseNullPrefixIdentifiersAreNotKeywords() {
        // "truthy" starts with "true" but isn't the keyword; it must not be colored as one.
        assertEquals(emptyList(), jsonHighlightSpans("truthy"))
    }
}
