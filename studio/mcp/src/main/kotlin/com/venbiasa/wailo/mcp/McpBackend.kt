package com.venbiasa.wailo.mcp

import com.venbiasa.wailo.daemon.DaemonClient
import com.venbiasa.wailo.daemon.DaemonRuleGroup
import com.venbiasa.wailo.daemon.ProxyCertificate
import com.venbiasa.wailo.daemon.ProxyStatus
import com.venbiasa.wailo.daemon.RULE_FAMILY_MAP_LOCAL
import com.venbiasa.wailo.daemon.RULE_FAMILY_SEEDS
import com.venbiasa.wailo.daemon.groupIdByRule
import com.venbiasa.wailo.daemon.groups
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
import com.venbiasa.wailo.daemon.provision.ProxyTargetOutcome
import com.venbiasa.wailo.daemon.provision.ProxyTargetScan
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
    /** The filter as authored; [captureFilterEnabled] is the master gating what devices actually apply. */
    val captureFilter: CaptureFilter
    val captureFilterEnabled: Boolean

    /** Hosts the user marked as worth watching — which hosts they care about, readable here (ADR-0084). */
    val bookmarkedHosts: List<String>
    val proxyStatus: ProxyStatus get() = ProxyStatus(error = "Proxy controls need a running Wailo daemon.")

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
    /**
     * The groups a panel is organised into, and which group each rule sits in (ADR-0081). Both are empty
     * for a backend with no daemon behind it, since grouping is daemon-owned state.
     */
    fun ruleGroups(family: String): List<DaemonRuleGroup>

    fun groupIdByRule(family: String): Map<String, String>

    suspend fun setRuleGroup(family: String, group: DaemonRuleGroup)

    suspend fun removeRuleGroup(family: String, id: String, withRules: Boolean): Boolean

    /** Sets match priority within one container (ADR-0026). False when it does not hold every id. */
    suspend fun setRuleOrder(family: String, groupId: String?, ids: List<String>): Boolean

    /**
     * A bounded read of one authored fixture's body. A rule arrives naming its body rather than carrying
     * it (ADR-0086), so `list_*` reports the size and this is what `get_*` shows.
     */
    suspend fun readRuleBody(family: String, id: String, limit: Int): ByteArray

    suspend fun upsertMapLocalRule(rule: HostMapLocalRule, groupId: String?)
    suspend fun removeMapLocalRule(id: String): Boolean
    suspend fun setMapLocalEnabled(enabled: Boolean)
    suspend fun updateCaptureFilter(
        allowlistEnabled: Boolean,
        allowPatterns: List<String>,
        blocklistEnabled: Boolean,
        blockPatterns: List<String>,
    )
    suspend fun setCaptureFilterEnabled(enabled: Boolean)
    suspend fun upsertBreakpointRule(rule: HostBreakpointRule, groupId: String?)
    suspend fun removeBreakpointRule(id: String): Boolean
    suspend fun setBreakpointsEnabled(enabled: Boolean)
    suspend fun upsertSeed(seed: HostSeed, groupId: String?)
    suspend fun removeSeed(id: String): Boolean
    suspend fun setSeedsEnabled(enabled: Boolean)

    /** Arms the enabled library and sweeps the waiting holds; returns how many seeds are left armed. */
    suspend fun fillSeeds(): Int
    suspend fun clearSeedQueue()
    suspend fun resumeHold(id: String, request: HttpRequest?, response: HttpResponse?): Boolean
    suspend fun abortHold(id: String): Boolean
    suspend fun proxyTargets(): ProxyTargetScan = proxyUnavailable()
    suspend fun setProxyEnabled(enabled: Boolean, port: Int?): ProxyStatus = proxyUnavailable()
    suspend fun setProxyPort(port: Int): ProxyStatus = proxyUnavailable()
    suspend fun setProxyLan(enabled: Boolean): ProxyStatus = proxyUnavailable()
    suspend fun setSystemProxy(enabled: Boolean): ProxyStatus = proxyUnavailable()
    suspend fun setProxyDecryptHosts(hosts: List<String>): ProxyStatus = proxyUnavailable()
    suspend fun currentProxyCertificate(): ProxyCertificate = proxyUnavailable()
    suspend fun ensureProxyCertificate(): ProxyCertificate = proxyUnavailable()
    suspend fun rotateProxyCertificate(): ProxyCertificate = proxyUnavailable()
    suspend fun removeProxyCertificate(): ProxyCertificate = proxyUnavailable()
    suspend fun setUpProxyTarget(id: String): ProxyTargetOutcome = proxyUnavailable()
    suspend fun clearProxyTarget(id: String): ProxyTargetOutcome = proxyUnavailable()

    private fun proxyUnavailable(): Nothing =
        throw UnsupportedOperationException("Proxy controls need a running Wailo daemon")
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
    override val captureFilterEnabled get() = daemon.captureFilterEnabled.value
    override val bookmarkedHosts get() = daemon.bookmarkedHosts.value
    override val proxyStatus get() = daemon.proxy.value

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
    override fun ruleGroups(family: String) = daemon.nodesFor(family).groups()

    override fun groupIdByRule(family: String) = daemon.nodesFor(family).groupIdByRule { it }

    override suspend fun setRuleGroup(family: String, group: DaemonRuleGroup) =
        daemon.setRuleGroup(family, group)

    override suspend fun removeRuleGroup(family: String, id: String, withRules: Boolean) =
        daemon.removeRuleGroup(family, id, withRules)

    override suspend fun setRuleOrder(family: String, groupId: String?, ids: List<String>) =
        daemon.setRuleOrder(family, groupId, ids)

    override suspend fun readRuleBody(family: String, id: String, limit: Int) =
        daemon.readRuleBody(family, id, length = limit)

    override suspend fun upsertMapLocalRule(rule: HostMapLocalRule, groupId: String?) =
        daemon.upsertMapLocalRule(rule, groupId)
    override suspend fun removeMapLocalRule(id: String) = daemon.removeMapLocalRule(id)
    override suspend fun setMapLocalEnabled(enabled: Boolean) = daemon.setMapLocalEnabled(enabled)
    override suspend fun updateCaptureFilter(
        allowlistEnabled: Boolean,
        allowPatterns: List<String>,
        blocklistEnabled: Boolean,
        blockPatterns: List<String>,
    ) = daemon.updateCaptureFilter(allowlistEnabled, allowPatterns, blocklistEnabled, blockPatterns)
    override suspend fun setCaptureFilterEnabled(enabled: Boolean) = daemon.setCaptureFilterEnabled(enabled)
    override suspend fun upsertBreakpointRule(rule: HostBreakpointRule, groupId: String?) =
        daemon.upsertBreakpointRule(rule, groupId)
    override suspend fun removeBreakpointRule(id: String) = daemon.removeBreakpointRule(id)
    override suspend fun setBreakpointsEnabled(enabled: Boolean) = daemon.setBreakpointsEnabled(enabled)
    override suspend fun upsertSeed(seed: HostSeed, groupId: String?) = daemon.upsertSeed(seed, groupId)
    override suspend fun removeSeed(id: String) = daemon.removeSeed(id)
    override suspend fun setSeedsEnabled(enabled: Boolean) = daemon.setSeedsEnabled(enabled)
    override suspend fun fillSeeds() = daemon.fillSeeds()
    override suspend fun clearSeedQueue() = daemon.clearSeedQueue()
    override suspend fun resumeHold(id: String, request: HttpRequest?, response: HttpResponse?) =
        daemon.resumeHold(id, request, response)
    override suspend fun abortHold(id: String) = daemon.abortHold(id)
    override suspend fun proxyTargets() = daemon.proxyTargets()
    override suspend fun setProxyEnabled(enabled: Boolean, port: Int?) = daemon.setProxyEnabled(enabled, port)
    override suspend fun setProxyPort(port: Int) = daemon.setProxyPort(port)
    override suspend fun setProxyLan(enabled: Boolean) = daemon.setProxyLan(enabled)
    override suspend fun setSystemProxy(enabled: Boolean) = daemon.setSystemProxy(enabled)
    override suspend fun setProxyDecryptHosts(hosts: List<String>) = daemon.setProxyDecryptHosts(hosts)
    override suspend fun currentProxyCertificate() = daemon.currentProxyCertificate()
    override suspend fun ensureProxyCertificate() = daemon.proxyCertificate()
    override suspend fun rotateProxyCertificate() = daemon.rotateProxyCertificate()
    override suspend fun removeProxyCertificate() = daemon.removeProxyCertificate()
    override suspend fun setUpProxyTarget(id: String) = daemon.setUpProxyTarget(id)
    override suspend fun clearProxyTarget(id: String) = daemon.clearProxyTarget(id)
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
    override val captureFilter get() = host.captureFilter.value
    override val captureFilterEnabled get() = host.isCaptureFilterEnabled()

    // Bookmarks are daemon state, and this backend wraps a bare host with no daemon behind it, so there
    // is nothing to report rather than an empty list standing in for "none bookmarked".
    override val bookmarkedHosts: List<String> get() = emptyList()

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
    // Grouping is daemon-owned, and this backend is the daemon-less one, so it reports a flat panel and
    // refuses to author groups rather than keeping a second copy of the model that nothing would read.
    // A refused placement is refused out loud: a rule that quietly landed outside the group it named
    // would read back as filed correctly on the next list.
    override fun ruleGroups(family: String) = emptyList<DaemonRuleGroup>()

    override fun groupIdByRule(family: String) = emptyMap<String, String>()

    override suspend fun setRuleGroup(family: String, group: DaemonRuleGroup) = refuseGroups()

    override suspend fun removeRuleGroup(family: String, id: String, withRules: Boolean): Boolean = refuseGroups()

    // Order is part of the same daemon-owned layout, and this backend reports a flat panel it cannot
    // rearrange, so it says so instead of accepting an order nothing would apply.
    override suspend fun setRuleOrder(family: String, groupId: String?, ids: List<String>): Boolean =
        throw UnsupportedOperationException("Rule order needs a running Wailo daemon")

    private fun refuseGroups(): Nothing =
        throw UnsupportedOperationException("Rule groups need a running Wailo daemon")

    private fun rejectGroup(groupId: String?) {
        if (!groupId.isNullOrEmpty()) refuseGroups()
    }

    // This backend's rules never left the process, so they still carry their bytes and the read is a copy
    // rather than a fetch — the seam exists for the daemon-backed one beside it.
    override suspend fun readRuleBody(family: String, id: String, limit: Int): ByteArray {
        val body = when (family) {
            RULE_FAMILY_MAP_LOCAL -> host.mapLocalRules.value.firstOrNull { it.id == id }?.bodyCopy()
            RULE_FAMILY_SEEDS -> host.seeds.value.firstOrNull { it.id == id }?.bodyCopy()
            else -> null
        } ?: return ByteArray(0)
        return body.copyOf(minOf(body.size, limit.coerceAtLeast(0)))
    }

    override suspend fun upsertMapLocalRule(rule: HostMapLocalRule, groupId: String?) {
        rejectGroup(groupId)
        host.upsertMapLocalRule(rule)
    }
    override suspend fun removeMapLocalRule(id: String) = host.removeMapLocalRule(id)
    override suspend fun setMapLocalEnabled(enabled: Boolean) = host.setMapLocalEnabled(enabled)
    override suspend fun updateCaptureFilter(
        allowlistEnabled: Boolean,
        allowPatterns: List<String>,
        blocklistEnabled: Boolean,
        blockPatterns: List<String>,
    ) = host.updateCaptureFilter(allowlistEnabled, allowPatterns, blocklistEnabled, blockPatterns)
    override suspend fun setCaptureFilterEnabled(enabled: Boolean) = host.setCaptureFilterEnabled(enabled)
    override suspend fun upsertBreakpointRule(rule: HostBreakpointRule, groupId: String?) {
        rejectGroup(groupId)
        host.upsertBreakpointRule(rule)
    }
    override suspend fun removeBreakpointRule(id: String) = host.removeBreakpointRule(id)
    override suspend fun setBreakpointsEnabled(enabled: Boolean) = host.setBreakpointsEnabled(enabled)
    override suspend fun upsertSeed(seed: HostSeed, groupId: String?) {
        rejectGroup(groupId)
        host.upsertSeed(seed)
    }
    override suspend fun removeSeed(id: String) = host.removeSeed(id)
    override suspend fun setSeedsEnabled(enabled: Boolean) = host.setSeedsEnabled(enabled)
    override suspend fun fillSeeds() = host.fillSeeds()
    override suspend fun clearSeedQueue() = host.clearSeedQueue()
    override suspend fun resumeHold(id: String, request: HttpRequest?, response: HttpResponse?) =
        host.resumeHold(id, request, response)
    override suspend fun abortHold(id: String) = host.abortHold(id)
}
