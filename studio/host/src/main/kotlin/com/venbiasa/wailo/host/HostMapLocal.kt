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
    /**
     * The one HTTP method this rule matches, or blank for any (ADR-0087). Scalar because every authoring
     * surface is — a model that could hold two only ever got them silently rewritten.
     */
    val method: String = "",
    val statusCode: Int = 200,
    val delayMillis: Int = 0,
    headers: List<Header> = emptyList(),
    body: ByteArray = ByteArray(0),
    /**
     * What the daemon holds for this rule, described rather than carried (ADR-0086). The daemon passes
     * the real [body] and these simply agree with it; a frontend receives the rule without its bytes and
     * these are all it knows about them — the size a panel shows, and the digest an open editor keys its
     * fetch on. Never consulted when serving, which always uses the bytes themselves.
     */
    bodySize: Int = body.size,
    val bodyHash: String = "",
) {
    val headers: List<Header> = headers.toList()
    private val bodyBytes = body.copyOf()

    val bodySize: Int = bodySize

    fun bodyCopy(): ByteArray = bodyBytes.copyOf()

    internal fun toProtocolRule() = MapLocalRule(
        id = id,
        enabled = enabled,
        url_pattern = urlPattern,
        // The wire field stays repeated as a pure fold target (ADR-0087): one method is a one-element
        // list, any is an empty one. Narrowing it would reach Wire's Swift codegen and both SDKs.
        methods = listOfNotNull(method.takeIf { it.isNotBlank() }),
    )

    internal fun serve(url: String, method: String): ServedBody? {
        if (!enabled || !urlPatternMatches(urlPattern, url)) return null
        if (!methodPatternMatches(this.method, method)) return null
        return ServedBody(
            code = statusCode,
            headers = normalizedHeaders(headers, bodyBytes.size),
            body = bodyBytes.copyOf(),
            delayMillis = delayMillis,
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
