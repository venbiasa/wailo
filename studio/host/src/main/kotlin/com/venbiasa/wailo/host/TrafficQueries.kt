package com.venbiasa.wailo.host

import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.engine.WailoEngine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Query helpers over captured exchanges and breakpoint holds for headless frontends (CLI, MCP,
 * Appium). The engine only publishes raw flows; waiting and summarizing live here so every frontend
 * shares one predicate language rather than reimplementing timeouts against StateFlow.
 */
class TrafficQueries(
    private val exchanges: StateFlow<List<CapturedExchange>>,
    private val pausedExchanges: StateFlow<List<PausedExchange>>,
) {
    constructor(engine: WailoEngine) : this(engine.exchanges, engine.pausedExchanges)

    fun findExchanges(predicate: (CapturedExchange) -> Boolean): List<CapturedExchange> =
        exchanges.value.filter(predicate)

    fun findExchangeById(id: String): CapturedExchange? =
        exchanges.value.firstOrNull { it.exchange.id == id }

    fun findExchangesByUrl(
        urlContains: String? = null,
        urlPattern: String? = null,
        method: String? = null,
        statusCode: Int? = null,
        appId: String? = null,
    ): List<CapturedExchange> = findExchanges { row ->
        matches(row, urlContains, urlPattern, method, statusCode) &&
            (appId == null || row.appId == appId)
    }

    /**
     * Suspend until an exchange matching [predicate] appears, or return null when [timeout] elapses.
     * Already-captured exchanges count — a late waiter still sees traffic that arrived before the call.
     */
    suspend fun waitForExchange(
        timeout: Duration = 30.seconds,
        predicate: (CapturedExchange) -> Boolean,
    ): CapturedExchange? = withTimeoutOrNull(timeout) {
        exchanges.first { rows -> rows.any(predicate) }.first(predicate)
    }

    suspend fun waitForExchange(
        timeout: Duration = 30.seconds,
        urlContains: String? = null,
        urlPattern: String? = null,
        method: String? = null,
        statusCode: Int? = null,
    ): CapturedExchange? = waitForExchange(timeout) { row ->
        matches(row, urlContains, urlPattern, method, statusCode)
    }

    fun listHolds(): List<PausedExchange> = pausedExchanges.value

    fun findHold(correlationId: String): PausedExchange? =
        pausedExchanges.value.firstOrNull { it.correlationId == correlationId }

    suspend fun waitForHold(
        timeout: Duration = 30.seconds,
        predicate: (PausedExchange) -> Boolean = { true },
    ): PausedExchange? = withTimeoutOrNull(timeout) {
        pausedExchanges.first { holds -> holds.any(predicate) }.first(predicate)
    }

    /** One-line-per-exchange summary for agent context windows and CLI stdout. */
    fun summarizeExchanges(limit: Int = 50): String = summarizeExchanges(exchanges.value, limit)

    fun summarizeHolds(): String {
        val holds = pausedExchanges.value
        if (holds.isEmpty()) return "(no holds)"
        return holds.joinToString("\n") { hold ->
            val phase = hold.phase.name.removePrefix("BREAKPOINT_PHASE_")
            "${hold.correlationId}\t$phase\t${hold.request?.method.orEmpty()}\t${hold.request?.url.orEmpty()}\t${hold.appId}"
        }
    }

    private fun matches(
        row: CapturedExchange,
        urlContains: String?,
        urlPattern: String?,
        method: String?,
        statusCode: Int?,
    ): Boolean {
        val req = row.exchange.request
        val url = req?.url.orEmpty()
        val matchesUrl = when {
            urlPattern != null -> urlPatternMatches(urlPattern, url)
            urlContains != null -> url.contains(urlContains, ignoreCase = true)
            else -> true
        }
        return matchesUrl &&
            (method == null || req?.method.equals(method, ignoreCase = true)) &&
            (statusCode == null || row.exchange.response?.code == statusCode)
    }
}

/** Summarize an arbitrary result set with the same stable format as the full capture buffer. */
fun summarizeExchanges(rows: List<CapturedExchange>, limit: Int = 50): String {
    val selected = rows.takeLast(limit)
    if (selected.isEmpty()) return "(no exchanges)"
    return selected.joinToString("\n") { row ->
        val req = row.exchange.request
        val code = row.exchange.response?.code?.toString()
            ?: row.exchange.error.takeIf { it.isNotBlank() }
            ?: "-"
        "${row.exchange.id}\t${req?.method.orEmpty()}\t$code\t${req?.url.orEmpty()}\t${row.appId}"
    }
}
