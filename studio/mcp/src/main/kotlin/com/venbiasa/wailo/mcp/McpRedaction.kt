package com.venbiasa.wailo.mcp

import com.venbiasa.wailo.protocol.Header
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Removes credentials from captured traffic on its way to an AI agent (ADR-0059).
 *
 * The threat is not the agent being hostile, it is the transcript: a bearer token pasted into a model
 * context ends up in a provider's logs and in the user's chat history, where revoking it is nobody's job.
 * So redaction happens here, at the last hop before the data leaves the machine, rather than in `engine` —
 * Studio's own UI must keep showing real values, because reading them is the point of a proxy.
 *
 * Values are replaced, never dropped: an agent that can see `authorization: <wailo:redacted>` can still
 * reason about the request having been authenticated, which an omitted header would hide.
 *
 * Known gap: a binary body can carry a token we cannot locate, so only textual bodies are rewritten.
 */
internal const val REDACTED_VALUE = "<wailo:redacted>"

// A body that claims a structure we cannot parse (usually truncated at the source) but mentions a
// sensitive key: withheld whole rather than passed through, since there is no way to rewrite just the
// secret out of it.
private const val REDACTED_BODY = "<wailo:redacted body>"

// Header names that are credentials by definition, independent of the key heuristic below (which would
// miss "cookie" and would fire on any header merely mentioning a token).
private val CREDENTIAL_HEADERS = setOf(
    "authorization",
    "proxy-authorization",
    "www-authenticate",
    "proxy-authenticate",
    "authentication",
)

private val COOKIE_HEADERS = setOf("cookie", "set-cookie", "cookie2", "set-cookie2")

// Matched against names stripped to letters and digits, so `access_token`, `access-token`, and
// `accessToken` are one case. Deliberately narrow: substrings like "auth" would redact `author`.
private val SENSITIVE_NAME_FRAGMENTS = listOf(
    "password",
    "passwd",
    "passphrase",
    "secret",
    "token",
    "apikey",
    "authorization",
    "credential",
    "privatekey",
    "accesskey",
    "sessionid",
    "signature",
)

private val json = Json { ignoreUnknownKeys = true }

internal fun isSensitiveName(name: String): Boolean {
    val normalized = name.lowercase().filter(Char::isLetterOrDigit)
    return SENSITIVE_NAME_FRAGMENTS.any(normalized::contains)
}

internal fun redactHeaders(headers: List<Header>): List<Header> = headers.map { header ->
    val name = header.name.lowercase()
    when {
        name in COOKIE_HEADERS -> Header(name = header.name, value_ = redactCookie(header.value_))
        name in CREDENTIAL_HEADERS || isSensitiveName(header.name) ->
            Header(name = header.name, value_ = REDACTED_VALUE)
        else -> header
    }
}

// Set-Cookie mixes one cookie with attributes that look exactly like cookies (`Path=/`), so the
// attribute names are what tells them apart. Keeping them is the point: an expiry or a missing HttpOnly
// is often the bug.
private val COOKIE_ATTRIBUTES = setOf(
    "expires",
    "path",
    "domain",
    "max-age",
    "samesite",
    "secure",
    "httponly",
    "priority",
    "partitioned",
    "version",
)

/**
 * Keeps cookie names and attributes and redacts only the values: which cookies were sent is usually the
 * answer to the debugging question, and the value never is.
 */
private fun redactCookie(value: String): String = value.split(';').joinToString(";") { part ->
    val separator = part.indexOf('=')
    if (separator <= 0 || part.substring(0, separator).trim().lowercase() in COOKIE_ATTRIBUTES) {
        part
    } else {
        part.substring(0, separator + 1) + REDACTED_VALUE
    }
}

/** Redacts sensitive query values but keeps the path and parameter names, which identify the call. */
internal fun redactUrl(url: String): String {
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return url
    val afterQuery = url.substring(queryStart + 1)
    val fragmentStart = afterQuery.indexOf('#')
    val query = if (fragmentStart < 0) afterQuery else afterQuery.substring(0, fragmentStart)
    val fragment = if (fragmentStart < 0) "" else afterQuery.substring(fragmentStart)
    return url.substring(0, queryStart + 1) + redactFormEncoded(query) + fragment
}

private fun redactFormEncoded(value: String): String = value.split('&').joinToString("&") { pair ->
    val separator = pair.indexOf('=')
    if (separator <= 0 || !isSensitiveName(pair.substring(0, separator))) {
        pair
    } else {
        pair.substring(0, separator + 1) + REDACTED_VALUE
    }
}

internal fun redactBodyText(text: String, contentType: String): String = when {
    text.isEmpty() -> text
    contentType.contains("x-www-form-urlencoded") -> redactFormEncoded(text)
    looksLikeJson(text) -> redactJsonText(text)
    else -> redactLooseText(text)
}

private fun looksLikeJson(text: String): Boolean =
    text.trimStart().firstOrNull()?.let { it == '{' || it == '[' } == true

private fun redactJsonText(text: String): String {
    val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        ?: return if (mentionsSensitiveKey(text)) REDACTED_BODY else text
    return json.encodeToString(JsonElement.serializer(), redactJson(parsed))
}

private fun redactJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.mapValues { (key, value) ->
            // A sensitive key hides its whole subtree: `"credentials": { "token": … }` must not survive
            // just because the secret is one level down under an innocuous name.
            when {
                !isSensitiveName(key) -> redactJson(value)
                value is JsonNull -> value
                else -> JsonPrimitive(REDACTED_VALUE)
            }
        },
    )
    is JsonArray -> JsonArray(element.map(::redactJson))
    else -> element
}

// XML, plain text, and JSON too truncated to parse: rewrite the assignment shapes a secret arrives in
// rather than passing the body through, at the cost of missing formats not shaped like these.
private val LOOSE_ASSIGNMENTS = listOf(
    Regex("""("([\w.\-]+)"\s*:\s*)"(?:\\.|[^"\\])*"""") to 2,
    Regex("""(<([\w.\-]+)>)[^<]*""") to 2,
    Regex("""\b(([\w.\-]+)\s*[=:]\s*)("[^"]*"|[^\s&;,}\]]+)""") to 2,
)

private fun redactLooseText(text: String): String =
    LOOSE_ASSIGNMENTS.fold(text) { current, (pattern, nameGroup) ->
        pattern.replace(current) { match ->
            val name = match.groupValues[nameGroup]
            if (isSensitiveName(name)) match.groupValues[1] + REDACTED_VALUE else match.value
        }
    }

private fun mentionsSensitiveKey(text: String): Boolean =
    Regex("""[\w.\-]+""").findAll(text).any { isSensitiveName(it.value) }

/**
 * Treats the redaction marker as "unchanged" when an edited hold comes back. `resume_hold` replaces the
 * whole header list, so an agent that read a redacted request and echoed its headers would otherwise
 * send `<wailo:redacted>` as the real Authorization value and break the call it was debugging.
 */
internal fun List<Header>.restoreRedacted(original: List<Header>): List<Header> {
    if (none { it.value_.contains(REDACTED_VALUE) }) return this
    val byName = original.groupBy { it.name.lowercase() }
    val consumed = mutableMapOf<String, Int>()
    return map { header ->
        if (!header.value_.contains(REDACTED_VALUE)) return@map header
        val key = header.name.lowercase()
        val index = consumed.getOrDefault(key, 0)
        consumed[key] = index + 1
        // A partially redacted value (a cookie jar) cannot be reassembled piecewise, so the original
        // header is restored whole.
        byName[key]?.getOrNull(index)?.let { Header(name = header.name, value_ = it.value_) } ?: header
    }
}
