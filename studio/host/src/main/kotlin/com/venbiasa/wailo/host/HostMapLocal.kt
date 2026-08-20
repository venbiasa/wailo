package com.venbiasa.wailo.host

import com.venbiasa.wailo.engine.ServedBody
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.MapLocalRule

/**
 * One in-memory Map Local fixture for headless use. Definitions and bodies deliberately stay on the
 * host; devices receive only [toProtocolRule]'s match metadata and fetch [serve] lazily (ADR-0019).
 */
class HostMapLocalRule(
    val id: String,
    /**
     * Studio's author-facing label, carried here only so headless frontends can name a rule whose id is
     * an opaque generated string. Never part of matching, and blank for rules created without one.
     */
    val name: String = "",
    val enabled: Boolean = true,
    val urlPattern: String,
    methods: List<String> = emptyList(),
    val statusCode: Int = 200,
    headers: List<Header> = emptyList(),
    body: ByteArray = ByteArray(0),
    val bodyAvailable: Boolean = true,
) {
    val methods: List<String> = methods.toList()
    val headers: List<Header> = headers.toList()
    private val bodyBytes = body.copyOf()

    val bodySize: Int
        get() = bodyBytes.size

    fun bodyCopy(): ByteArray = bodyBytes.copyOf()

    internal fun toProtocolRule() = MapLocalRule(
        id = id,
        enabled = enabled,
        url_pattern = urlPattern,
        methods = methods,
    )

    internal fun serve(url: String, method: String): ServedBody? {
        if (!enabled || !bodyAvailable || !urlPatternMatches(urlPattern, url)) return null
        if (methods.isNotEmpty() && methods.none { it.equals(method, ignoreCase = true) }) return null
        return ServedBody(
            code = statusCode,
            headers = normalizedHeaders(headers, bodyBytes.size),
            body = bodyBytes.copyOf(),
        )
    }
}

/**
 * Shared with Seed's breakpoint responses: both answer with an authored rule's headers, so both owe the
 * client the same correction — Content-Length is the host's, recomputed from the bytes it is actually
 * sending, since an authored one that drifts truncates or hangs the response.
 */
internal fun normalizedHeaders(headers: List<Header>, bodySize: Int): List<Header> = buildList {
    headers
        .filter { it.name.isNotBlank() && !it.name.equals("Content-Length", ignoreCase = true) }
        .forEach(::add)
    add(Header(name = "Content-Length", value_ = bodySize.toString()))
}
