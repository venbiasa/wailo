package com.venbiasa.wailo.shared.format

/** A syntactic role a run of characters plays on a JSON line, mapped to a theme color by the editor. */
internal enum class JsonToken { Key, StringValue, Number, Keyword, Punctuation }

/** A colored run `[start, end)` on a single line; characters outside any span use the default ink. */
internal data class JsonSpan(val start: Int, val end: Int, val token: JsonToken)

/**
 * Tokenizes ONE line of JSON into colored spans, statelessly. This is safe per-line because a JSON string
 * literal can never contain a raw newline (it must be `\n`-escaped), so no token straddles a line — which
 * is exactly what lets the editor highlight only the visible lines (viewport-bounded, size-independent).
 *
 * It is a lenient lexer, not a validator: it colors whatever shape it sees (a string is a Key when the next
 * non-space char is `:`, else a value; unterminated strings run to end-of-line), so a half-typed line still
 * highlights sensibly. Uncovered ranges (whitespace, stray identifiers) fall back to the default color.
 */
internal fun jsonHighlightSpans(line: String): List<JsonSpan> {
    if (line.isEmpty()) return emptyList()
    val spans = ArrayList<JsonSpan>()
    val n = line.length
    var i = 0
    while (i < n) {
        when (line[i]) {
            '"' -> {
                val start = i
                i++ // opening quote
                while (i < n) {
                    val ch = line[i]
                    when {
                        ch == '\\' -> i += 2 // skip the escaped char (may run one past EOL; loop re-checks)
                        ch == '"' -> { i++; break }
                        else -> i++
                    }
                }
                val end = i.coerceAtMost(n)
                var j = end
                while (j < n && (line[j] == ' ' || line[j] == '\t')) j++
                val token = if (j < n && line[j] == ':') JsonToken.Key else JsonToken.StringValue
                spans.add(JsonSpan(start, end, token))
            }
            '-', in '0'..'9' -> {
                val start = i
                i++
                while (i < n && (line[i] in '0'..'9' || line[i] == '.' ||
                        line[i] == 'e' || line[i] == 'E' || line[i] == '+' || line[i] == '-')
                ) {
                    i++
                }
                spans.add(JsonSpan(start, i, JsonToken.Number))
            }
            't', 'f', 'n' -> {
                val kw = when {
                    line.startsWith("true", i) -> "true"
                    line.startsWith("false", i) -> "false"
                    line.startsWith("null", i) -> "null"
                    else -> null
                }
                if (kw != null) {
                    spans.add(JsonSpan(i, i + kw.length, JsonToken.Keyword))
                    i += kw.length
                } else {
                    i++
                }
            }
            '{', '}', '[', ']', ':', ',' -> {
                spans.add(JsonSpan(i, i + 1, JsonToken.Punctuation))
                i++
            }
            else -> i++
        }
    }
    return spans
}
