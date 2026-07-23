package com.venbiasa.wailo.shared.format

import okio.ByteString.Companion.decodeBase64
import kotlin.math.round

/** How an HTTP outcome should be categorized for coloring and labeling in the list. */
internal enum class StatusKind { Success, Redirect, ClientError, ServerError, Failed, Pending }

internal fun statusKind(code: Int?, hasError: Boolean): StatusKind = when {
    hasError -> StatusKind.Failed
    code == null || code <= 0 -> StatusKind.Pending
    code in 200..299 -> StatusKind.Success
    code in 300..399 -> StatusKind.Redirect
    code in 400..499 -> StatusKind.ClientError
    code in 500..599 -> StatusKind.ServerError
    else -> StatusKind.Pending
}

/** Short badge for the status column: the code, `ERR` for a transport failure, `—` while pending. */
internal fun statusLabel(code: Int?, hasError: Boolean): String = when {
    hasError -> "ERR"
    code == null || code <= 0 -> "—"
    else -> code.toString()
}

/** Word label for the traffic list's Status column (the colored, at-a-glance outcome). */
internal fun statusText(kind: StatusKind): String = when (kind) {
    StatusKind.Success -> "Success"
    StatusKind.Redirect -> "Redirect"
    StatusKind.ClientError -> "Client Error"
    StatusKind.ServerError -> "Server Error"
    StatusKind.Failed -> "Failed"
    StatusKind.Pending -> "Pending"
}

/** Raw HTTP code for the Code column; `—` when there is no response code yet (or a transport failure). */
internal fun codeText(code: Int?): String =
    if (code == null || code <= 0) "—" else code.toString()

/**
 * The detail panel's status chip: `<code> <reason>` (e.g. `200 OK`, `404 Not Found`), preferring the
 * server-sent [message] and falling back to a standard [reasonPhrase] for HTTP/2 (no reason line) or
 * when the SDK omits it. Transport failures read `Failed` and unanswered requests read `Pending`, so
 * the chip's text agrees with its status color.
 */
internal fun statusChipText(code: Int?, message: String, hasError: Boolean): String = when {
    hasError -> "Failed"
    code == null || code <= 0 -> "Pending"
    else -> {
        val reason = message.ifBlank { reasonPhrase(code) }
        if (reason.isBlank()) code.toString() else "$code $reason"
    }
}

/** Standard reason phrase for the common HTTP codes; blank when unknown (callers show the bare code). */
internal fun reasonPhrase(code: Int): String = when (code) {
    200 -> "OK"
    201 -> "Created"
    202 -> "Accepted"
    204 -> "No Content"
    206 -> "Partial Content"
    301 -> "Moved Permanently"
    302 -> "Found"
    303 -> "See Other"
    304 -> "Not Modified"
    307 -> "Temporary Redirect"
    308 -> "Permanent Redirect"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    406 -> "Not Acceptable"
    408 -> "Request Timeout"
    409 -> "Conflict"
    410 -> "Gone"
    413 -> "Payload Too Large"
    415 -> "Unsupported Media Type"
    422 -> "Unprocessable Entity"
    429 -> "Too Many Requests"
    500 -> "Internal Server Error"
    501 -> "Not Implemented"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    504 -> "Gateway Timeout"
    else -> ""
}

/**
 * Decodes an `Authorization: Basic <base64>` value to its `user:password` form for the Auth view;
 * null for any other header or scheme (Bearer and opaque tokens are shown verbatim by the caller).
 */
internal fun basicAuthDecoded(name: String, value: String): String? {
    if (!name.equals("Authorization", ignoreCase = true)) return null
    val token = value.trim()
    if (!token.startsWith("Basic ", ignoreCase = true)) return null
    return token.substring(6).trim().decodeBase64()?.utf8()
}

/** HTTP verb families, used only to pick a method color. */
internal enum class MethodKind { Read, Create, Update, Delete, Other }

internal fun methodKind(method: String): MethodKind = when (method.trim().uppercase()) {
    "GET", "HEAD", "OPTIONS" -> MethodKind.Read
    "POST" -> MethodKind.Create
    "PUT", "PATCH" -> MethodKind.Update
    "DELETE" -> MethodKind.Delete
    else -> MethodKind.Other
}

internal data class UrlParts(val host: String, val pathAndQuery: String)

/** Splits a URL into host and path+query for the two-tone list row, tolerant of malformed input. */
internal fun splitUrl(url: String): UrlParts {
    val schemeIdx = url.indexOf("://")
    if (schemeIdx < 0) return UrlParts(host = "", pathAndQuery = url)
    val afterScheme = schemeIdx + 3
    val slash = url.indexOf('/', afterScheme)
    return if (slash < 0) {
        UrlParts(host = url.substring(afterScheme), pathAndQuery = "/")
    } else {
        UrlParts(host = url.substring(afterScheme, slash), pathAndQuery = url.substring(slash))
    }
}

/** A colorable slice of a URL for the detail panel's syntax-highlighted address. */
internal enum class UrlPart { Scheme, Separator, Host, Port, Path, Query, Fragment }

internal data class UrlSegment(val text: String, val part: UrlPart)

/**
 * Tokenizes a URL into colorable [UrlSegment]s (scheme, host, port, path, query, fragment), tagging
 * the punctuation between them [UrlPart.Separator]. Concatenating the segments' text reproduces [url]
 * exactly, so the result is safe to render as one selectable string. Tolerant of relative or
 * malformed input: anything without a recognizable `scheme://authority` is emitted as [UrlPart.Path].
 */
internal fun urlSegments(url: String): List<UrlSegment> {
    if (url.isEmpty()) return emptyList()
    val out = mutableListOf<UrlSegment>()
    fun emit(text: String, part: UrlPart) {
        if (text.isNotEmpty()) out += UrlSegment(text, part)
    }

    // Peel the fragment then the query off the end so a stray '#'/'?' elsewhere can't be misread.
    var rest = url
    var fragment: String? = null
    val hash = rest.indexOf('#')
    if (hash >= 0) {
        fragment = rest.substring(hash + 1)
        rest = rest.substring(0, hash)
    }
    var query: String? = null
    val question = rest.indexOf('?')
    if (question >= 0) {
        query = rest.substring(question + 1)
        rest = rest.substring(0, question)
    }

    val schemeSep = rest.indexOf("://")
    val hasScheme = schemeSep > 0 &&
        rest[0].isLetter() &&
        rest.substring(0, schemeSep).all { it.isLetterOrDigit() || it == '+' || it == '.' || it == '-' }
    if (hasScheme) {
        emit(rest.substring(0, schemeSep), UrlPart.Scheme)
        emit("://", UrlPart.Separator)
        val afterScheme = rest.substring(schemeSep + 3)
        val slash = afterScheme.indexOf('/')
        val authority = if (slash >= 0) afterScheme.substring(0, slash) else afterScheme
        val path = if (slash >= 0) afterScheme.substring(slash) else ""
        val portColon = authority.lastIndexOf(':')
        if (portColon in 0 until authority.length - 1 &&
            authority.substring(portColon + 1).all { it.isDigit() }
        ) {
            emit(authority.substring(0, portColon), UrlPart.Host)
            emit(":", UrlPart.Separator)
            emit(authority.substring(portColon + 1), UrlPart.Port)
        } else {
            emit(authority, UrlPart.Host)
        }
        emit(path, UrlPart.Path)
    } else {
        emit(rest, UrlPart.Path)
    }

    if (query != null) {
        emit("?", UrlPart.Separator)
        emit(query, UrlPart.Query)
    }
    if (fragment != null) {
        emit("#", UrlPart.Separator)
        emit(fragment, UrlPart.Fragment)
    }
    return out
}

/**
 * The request URL's host without any port (empty when the URL has no recognizable authority). This
 * is both the bookmark key and the traffic filter's comparison value, so both sides derive the host
 * the same way via [urlSegments] and can never disagree.
 */
internal fun requestHost(url: String): String =
    urlSegments(url).firstOrNull { it.part == UrlPart.Host }?.text ?: ""

/**
 * Whether [host] matches a capture-allowlist [pattern]: `*` matches any run of characters, everything
 * else is literal, compared case-insensitively against the whole host. Mirrors the device-side matcher
 * (WailoRuleStore / WailoCaptureConfigStore) so the desktop's lock indicator agrees with what the SDK
 * actually captures. An empty pattern or host never matches.
 */
internal fun hostMatchesPattern(pattern: String, host: String): Boolean {
    if (pattern.isEmpty() || host.isEmpty()) return false
    val regex = buildString {
        append('^')
        pattern.split('*').forEachIndexed { index, literal ->
            if (index > 0) append(".*")
            append(Regex.escape(literal))
        }
        append('$')
    }
    return Regex(regex, RegexOption.IGNORE_CASE).matches(host)
}

/** Whether any allowlist [patterns] entry unlocks [host] for body capture. */
internal fun isHostUnlocked(patterns: List<String>, host: String): Boolean =
    patterns.any { hostMatchesPattern(it, host) }

/**
 * Whether [pattern] is a plausible capture-allowlist entry, gating the manager's manual "Unlock" input
 * so a stray token (e.g. a lone "s") can't be added. Accepts a domain/IP (has a dot), an explicit
 * wildcard pattern (contains `*`), or the well-known single-label dev host `localhost`; only host
 * characters are allowed (letters, digits, `.`, `-`, `*`), with no leading/trailing dot or hyphen and no
 * empty labels. Hosts unlocked from a real traffic row bypass this — they're already concrete hosts.
 */
internal fun isValidHostPattern(pattern: String): Boolean {
    val host = pattern.trim()
    if (host.isEmpty()) return false
    if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '*' }) return false
    if (host.startsWith('.') || host.endsWith('.') || host.startsWith('-') || host.endsWith('-')) return false
    if (host.contains("..")) return false
    return host.contains('.') || host.contains('*') || host.equals("localhost", ignoreCase = true)
}

/** Human-readable byte size (`—` for empty/unknown, `340 B`, `1.2 KB`, `3.4 MB`, …). */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val scaled = round(value * 10).toLong()
    return "${scaled / 10}.${scaled % 10} ${units[unit]}"
}

/**
 * Wall-clock `HH:mm:ss.SSS` for a *local* epoch-milliseconds value (caller adds the zone offset).
 * Pure so it works in commonMain; `java.time` is unavailable here.
 */
internal fun formatClockTime(epochMsLocal: Long): String {
    val dayMs = 86_400_000L
    val msOfDay = ((epochMsLocal % dayMs) + dayMs) % dayMs
    val ms = msOfDay % 1000
    val totalSeconds = msOfDay / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = (totalSeconds / 3600) % 24
    return "${pad2(hours)}:${pad2(minutes)}:${pad2(seconds)}.${pad3(ms)}"
}

private fun pad2(value: Long): String = if (value < 10) "0$value" else "$value"

private fun pad3(value: Long): String = when {
    value < 10 -> "00$value"
    value < 100 -> "0$value"
    else -> "$value"
}
