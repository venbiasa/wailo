package com.venbiasa.wailo.host

import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpResponse

/**
 * A canned response that answers a held exchange on the host (ADR-0041). UI-free twin of the desktop
 * `SeedRuleDef`: match on a URL wildcard + optional method, answer with status / headers / a body the
 * caller supplies through [SeedResponseProvider]. Never pushed to a device.
 */
data class HostSeed(
    val id: String,
    val urlPattern: String = "",
    val method: String = "",
    val statusCode: Int = 200,
    val headers: List<Header> = emptyList(),
)

/** Resolves a matched seed to the response bytes. Null means the seed cannot answer (missing body). */
fun interface SeedResponseProvider {
    suspend fun serve(seed: HostSeed): HttpResponse?
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
 * Three ways to answer nothing, all of them a hold the caller has to take: a request-phase hold (the
 * wire honours an edited response only on a RESPONSE-phase hit); no seed matching the URL/method; or
 * a seed whose body the [responses] provider cannot serve, where resolving with an empty body would
 * be a silent, wrong success.
 */
suspend fun spendSeedOn(
    engine: WailoEngine,
    queue: List<HostSeed>,
    hold: PausedExchange,
    responses: SeedResponseProvider,
): List<HostSeed>? = spendSeedOn(queue, hold, responses) { correlationId, response ->
    engine.resumeBreakpoint(correlationId, null, response)
}

suspend fun spendSeedOn(
    queue: List<HostSeed>,
    hold: PausedExchange,
    responses: SeedResponseProvider,
    resume: suspend (correlationId: String, response: HttpResponse) -> Boolean,
): List<HostSeed>? {
    if (hold.phase != BreakpointPhase.BREAKPOINT_PHASE_RESPONSE) return null
    val seed = queue.firstMatch(hold.request?.url.orEmpty(), hold.request?.method.orEmpty()) ?: return null
    val response = responses.serve(seed) ?: return null
    if (!resume(hold.correlationId, response)) return null
    return queue.consume(seed)
}
