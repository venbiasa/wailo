package com.venbiasa.wailo.mcp

import com.venbiasa.wailo.daemon.DaemonClient
import com.venbiasa.wailo.engine.BodyRef
import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.engine.ConnectedDevice
import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.host.HeadlessHost
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.HostSeed
import com.venbiasa.wailo.protocol.CaptureFilter
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

internal interface McpBackend {
    /** Whether the user currently permits AI tools to reach this capture at all (ADR-0059). */
    val mcpAccess: Boolean

    /** Whether credentials are stripped from what tools return. */
    val redactSecrets: Boolean

    /**
     * Brings [mcpAccess] and [redactSecrets] up to date before they are enforced, so revoking access
     * refuses the next tool call rather than a poll interval later.
     */
    suspend fun refreshSettings()

    val capturePort: Int
    val listening: Boolean
    val lanAddress: String
    val capturing: Boolean
    val maxRetained: Int
    val exchanges: List<CapturedExchange>
    val connectedDevices: StateFlow<List<ConnectedDevice>>
    val holds: List<PausedExchange>
    val mapLocalEnabled: Boolean
    val mapLocalRules: List<HostMapLocalRule>
    val breakpointsEnabled: Boolean
    val breakpointRules: List<HostBreakpointRule>
    val seedsEnabled: Boolean

    /** The authored library, in priority order. */
    val seeds: List<HostSeed>

    /** What is still armed and unspent, which is what decides how the next hold is answered. */
    val seedQueue: List<HostSeed>
    val captureFilter: CaptureFilter

    fun searchTraffic(
        urlContains: String?,
        urlPattern: String?,
        method: String?,
        statusCode: Int?,
        appId: String?,
    ): List<CapturedExchange>

    fun findExchangeById(id: String): CapturedExchange?
    fun findHold(id: String): PausedExchange?

    /**
     * Read the first [limit] bytes behind a captured body's handle. Bodies do not travel with rows
     * (ADR-0069), and an agent has a body budget anyway, so a tool fetches exactly the prefix it is
     * about to render.
     */
    suspend fun readBody(ref: BodyRef, limit: Int): ByteArray
    suspend fun waitForExchange(timeout: Duration, predicate: (CapturedExchange) -> Boolean): CapturedExchange?
    suspend fun waitForHold(timeout: Duration, predicate: (PausedExchange) -> Boolean): PausedExchange?
    suspend fun clear()
    suspend fun setCapturing(enabled: Boolean)
    suspend fun setMaxRetained(value: Int)
    suspend fun upsertMapLocalRule(rule: HostMapLocalRule)
    suspend fun removeMapLocalRule(id: String): Boolean
    suspend fun setMapLocalEnabled(enabled: Boolean)
    suspend fun updateCaptureFilter(
        allowlistEnabled: Boolean,
        allowPatterns: List<String>,
        blocklistEnabled: Boolean,
        blockPatterns: List<String>,
    )
    suspend fun upsertBreakpointRule(rule: HostBreakpointRule)
    suspend fun removeBreakpointRule(id: String): Boolean
    suspend fun setBreakpointsEnabled(enabled: Boolean)
    suspend fun upsertSeed(seed: HostSeed)
    suspend fun removeSeed(id: String): Boolean
    suspend fun setSeedsEnabled(enabled: Boolean)

    /** Arms the enabled library and sweeps the waiting holds; returns how many seeds are left armed. */
    suspend fun fillSeeds(): Int
    suspend fun clearSeedQueue()
    suspend fun resumeHold(id: String, request: HttpRequest?, response: HttpResponse?): Boolean
    suspend fun abortHold(id: String): Boolean
}

internal class DaemonMcpBackend(
    private val daemon: DaemonClient,
) : McpBackend {
    // Read per call, never cached: the user can revoke access from Studio or the CLI while an agent is
    // mid-conversation, and the next tool call has to see that.
    override val mcpAccess get() = daemon.mcpAccess.value
    override val redactSecrets get() = daemon.mcpRedactSecrets.value
    override suspend fun refreshSettings() = daemon.refreshMcpSettings()
    override val capturePort get() = daemon.capturePort.value
    override val listening get() = daemon.listening.value
    override val lanAddress get() = daemon.lanAddress.value
    override val capturing get() = daemon.capturing.value
    override val maxRetained get() = daemon.maxRetained.value
    override val exchanges get() = daemon.exchanges.value
    override val connectedDevices = daemon.connectedDevices
    override val holds get() = daemon.pausedExchanges.value
    override val mapLocalEnabled get() = daemon.mapLocalEnabled.value
    override val mapLocalRules get() = daemon.mapLocalRules.value
    override val breakpointsEnabled get() = daemon.breakpointsEnabled.value
    override val breakpointRules get() = daemon.breakpointRules.value
    override val seedsEnabled get() = daemon.seedsEnabled.value
    override val seeds get() = daemon.seeds.value
    override val seedQueue get() = daemon.seedQueue.value
    override val captureFilter get() = daemon.captureFilter.value

    override fun searchTraffic(
        urlContains: String?,
        urlPattern: String?,
        method: String?,
        statusCode: Int?,
        appId: String?,
    ) = daemon.searchTraffic(urlContains, urlPattern, method, statusCode, appId)

    override fun findExchangeById(id: String) = daemon.findExchangeById(id)
    override fun findHold(id: String) = daemon.findHold(id)
    override suspend fun readBody(ref: BodyRef, limit: Int) = daemon.readBody(ref, length = limit)
    override suspend fun waitForExchange(timeout: Duration, predicate: (CapturedExchange) -> Boolean) =
        daemon.waitForExchange(timeout, predicate)
    override suspend fun waitForHold(timeout: Duration, predicate: (PausedExchange) -> Boolean) =
        daemon.waitForHold(timeout, predicate)
    override suspend fun clear() = daemon.clear()
    override suspend fun setCapturing(enabled: Boolean) = daemon.setCapturing(enabled)
    override suspend fun setMaxRetained(value: Int) = daemon.setMaxRetained(value)
    override suspend fun upsertMapLocalRule(rule: HostMapLocalRule) = daemon.upsertMapLocalRule(rule)
    override suspend fun removeMapLocalRule(id: String) = daemon.removeMapLocalRule(id)
    override suspend fun setMapLocalEnabled(enabled: Boolean) = daemon.setMapLocalEnabled(enabled)
    override suspend fun updateCaptureFilter(
        allowlistEnabled: Boolean,
        allowPatterns: List<String>,
        blocklistEnabled: Boolean,
        blockPatterns: List<String>,
    ) = daemon.updateCaptureFilter(allowlistEnabled, allowPatterns, blocklistEnabled, blockPatterns)
    override suspend fun upsertBreakpointRule(rule: HostBreakpointRule) = daemon.upsertBreakpointRule(rule)
    override suspend fun removeBreakpointRule(id: String) = daemon.removeBreakpointRule(id)
    override suspend fun setBreakpointsEnabled(enabled: Boolean) = daemon.setBreakpointsEnabled(enabled)
    override suspend fun upsertSeed(seed: HostSeed) = daemon.upsertSeed(seed)
    override suspend fun removeSeed(id: String) = daemon.removeSeed(id)
    override suspend fun setSeedsEnabled(enabled: Boolean) = daemon.setSeedsEnabled(enabled)
    override suspend fun fillSeeds() = daemon.fillSeeds()
    override suspend fun clearSeedQueue() = daemon.clearSeedQueue()
    override suspend fun resumeHold(id: String, request: HttpRequest?, response: HttpResponse?) =
        daemon.resumeHold(id, request, response)
    override suspend fun abortHold(id: String) = daemon.abortHold(id)
}

internal class LocalMcpBackend(
    private val host: HeadlessHost,
    override val capturePort: Int,
    override val mcpAccess: Boolean = true,
    override val redactSecrets: Boolean = true,
) : McpBackend {
    // Nothing to refresh: this backend is the authority for its own two flags.
    override suspend fun refreshSettings() = Unit

    override val listening get() = host.engine.listening.value
    override val lanAddress get() = host.engine.lanAddress.value
    override val capturing get() = host.engine.capturing.value
    override val maxRetained get() = host.engine.maxRetained.value
    override val exchanges get() = host.listExchanges()
    override val connectedDevices = host.engine.connectedDevices
    override val holds get() = host.listHolds()
    override val mapLocalEnabled get() = host.isMapLocalEnabled()
    override val mapLocalRules get() = host.mapLocalRules.value
    override val breakpointsEnabled get() = host.areBreakpointsEnabled()
    override val breakpointRules get() = host.breakpointRules.value
    override val seedsEnabled get() = host.areSeedsEnabled()
    override val seeds get() = host.seeds.value
    override val seedQueue get() = host.seedQueue.value
    override val captureFilter get() = host.engine.captureFilter.value

    override fun searchTraffic(
        urlContains: String?,
        urlPattern: String?,
        method: String?,
        statusCode: Int?,
        appId: String?,
    ) = host.searchTraffic(urlContains, urlPattern, method, statusCode, appId)

    override fun findExchangeById(id: String) = host.findExchangeById(id)
    override fun findHold(id: String) = host.queries.findHold(id)
    override suspend fun readBody(ref: BodyRef, limit: Int) = host.readBody(ref, offset = 0, length = limit)
    override suspend fun waitForExchange(timeout: Duration, predicate: (CapturedExchange) -> Boolean) =
        host.queries.waitForExchange(timeout = timeout, predicate = predicate)
    override suspend fun waitForHold(timeout: Duration, predicate: (PausedExchange) -> Boolean) =
        host.waitForHold(timeout, predicate)
    override suspend fun clear() = host.clear()
    override suspend fun setCapturing(enabled: Boolean) = host.setCapturing(enabled)
    override suspend fun setMaxRetained(value: Int) = host.setMaxRetained(value)
    override suspend fun upsertMapLocalRule(rule: HostMapLocalRule) = host.upsertMapLocalRule(rule)
    override suspend fun removeMapLocalRule(id: String) = host.removeMapLocalRule(id)
    override suspend fun setMapLocalEnabled(enabled: Boolean) = host.setMapLocalEnabled(enabled)
    override suspend fun updateCaptureFilter(
        allowlistEnabled: Boolean,
        allowPatterns: List<String>,
        blocklistEnabled: Boolean,
        blockPatterns: List<String>,
    ) = host.updateCaptureFilter(allowlistEnabled, allowPatterns, blocklistEnabled, blockPatterns)
    override suspend fun upsertBreakpointRule(rule: HostBreakpointRule) = host.upsertBreakpointRule(rule)
    override suspend fun removeBreakpointRule(id: String) = host.removeBreakpointRule(id)
    override suspend fun setBreakpointsEnabled(enabled: Boolean) = host.setBreakpointsEnabled(enabled)
    override suspend fun upsertSeed(seed: HostSeed) = host.upsertSeed(seed)
    override suspend fun removeSeed(id: String) = host.removeSeed(id)
    override suspend fun setSeedsEnabled(enabled: Boolean) = host.setSeedsEnabled(enabled)
    override suspend fun fillSeeds() = host.fillSeeds()
    override suspend fun clearSeedQueue() = host.clearSeedQueue()
    override suspend fun resumeHold(id: String, request: HttpRequest?, response: HttpResponse?) =
        host.resumeHold(id, request, response)
    override suspend fun abortHold(id: String) = host.abortHold(id)
}
