package com.venbiasa.wailo.host

import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpResponse
import okio.ByteString.Companion.toByteString

/**
 * A canned response that answers a held exchange on the host (ADR-0041). UI-free twin of the desktop
 * `SeedRuleDef`: match on a URL wildcard + optional method, answer with status / headers / body. Never
 * pushed to a device — a hold is already a desktop round-trip, so the seed is resolved here.
 *
 * The bytes ride along rather than being fetched through a callback, because the daemon owns the seed
 * library now and every frontend reads it from there (ADR-0067) — and holds the only copy of them
 * (ADR-0085), so a seed always has a body to serve, even if it is an empty one the author chose.
 */
class HostSeed(
    val id: String,
    val enabled: Boolean = true,
    val urlPattern: String = "",
    val method: String = "",
    val statusCode: Int = 200,
    headers: List<Header> = emptyList(),
    body: ByteArray = ByteArray(0),
) {
    val headers: List<Header> = headers.toList()
    private val bodyBytes = body.copyOf()

    val bodySize: Int
        get() = bodyBytes.size

    fun bodyCopy(): ByteArray = bodyBytes.copyOf()

    internal fun toResponse() = HttpResponse(
        code = statusCode,
        headers = normalizedHeaders(headers, bodyBytes.size),
        body = bodyBytes.toByteString(),
        body_size = bodyBytes.size.toLong(),
        body_truncated = false,
    )
}

/**
 * The first seed in this (already ordered) list that answers [url]/[method], or null if none does.
 * List order is both match priority and the sequence in which repeated requests are answered.
 */
fun List<HostSeed>.firstMatch(url: String, method: String): HostSeed? = firstOrNull { seed ->
    methodPatternMatches(seed.method, method) && urlPatternMatches(seed.urlPattern, url)
}

/** The queue with [seed] spent: removed by id, everything else left in order. */
fun List<HostSeed>.consume(seed: HostSeed): List<HostSeed> = filterNot { it.id == seed.id }

/**
 * Answer [hold] with the first matching seed in [queue], or return null and leave both untouched.
 *
 * Two ways to answer nothing, both of them a hold the caller has to take: a request-phase hold (the
 * wire honours an edited response only on a RESPONSE-phase hit), or no seed matching the URL/method.
 */
suspend fun spendSeedOn(
    engine: WailoEngine,
    queue: List<HostSeed>,
    hold: PausedExchange,
): List<HostSeed>? = spendSeedOn(queue, hold) { correlationId, response ->
    engine.resumeBreakpoint(correlationId, null, response)
}

suspend fun spendSeedOn(
    queue: List<HostSeed>,
    hold: PausedExchange,
    resume: suspend (correlationId: String, response: HttpResponse) -> Boolean,
): List<HostSeed>? {
    if (hold.phase != BreakpointPhase.BREAKPOINT_PHASE_RESPONSE) return null
    val seed = queue.firstMatch(hold.request?.url.orEmpty(), hold.request?.method.orEmpty()) ?: return null
    if (!resume(hold.correlationId, seed.toResponse())) return null
    return queue.consume(seed)
}
