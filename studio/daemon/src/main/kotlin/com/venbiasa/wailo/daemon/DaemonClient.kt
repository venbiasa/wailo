package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.BodyRef
import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.engine.ConnectedDevice
import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.HostSeed
import com.venbiasa.wailo.host.urlPatternMatches
import com.venbiasa.wailo.protocol.CaptureFilter
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class DaemonPairingOffer(
    val code: String,
    val expiresAtEpochMs: Long,
    val qrPayload: String,
)

data class DaemonPairedDevice(
    val deviceId: String,
    val name: String,
    val pairedAtEpochMs: Long,
    val lastSeenEpochMs: Long,
    val trustedOnFirstUse: Boolean,
)

data class DaemonRefusedDevice(val deviceId: String, val reason: String)

data class DaemonPairingState(
    val supported: Boolean = false,
    val requirePairing: Boolean = false,
    val studioId: String = "",
    val offer: DaemonPairingOffer? = null,
    val devices: List<DaemonPairedDevice> = emptyList(),
    val refusals: List<DaemonRefusedDevice> = emptyList(),
    val suspectedClones: Set<String> = emptySet(),
)

/**
 * StateFlow-based client for the one persistent Wailo daemon. Every frontend gets this same projection,
 * so opening Studio cannot split devices, holds, or rules away from an already-running MCP session.
 */
class DaemonClient internal constructor(
    private val rpc: DaemonRpcClient,
    private val autoRestart: Boolean,
    holdsPresence: Boolean = false,
    clientKind: String = CLIENT_KIND_UNKNOWN,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private val presence = if (holdsPresence) rpc.presence(clientKind) else null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _exchanges = MutableStateFlow<List<CapturedExchange>>(emptyList())
    val exchanges: StateFlow<List<CapturedExchange>> = _exchanges.asStateFlow()
    private val _capturing = MutableStateFlow(true)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()
    private val _connectedDevices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val connectedDevices: StateFlow<List<ConnectedDevice>> = _connectedDevices.asStateFlow()
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _capturePort = MutableStateFlow(8899)
    val capturePort: StateFlow<Int> = _capturePort.asStateFlow()
    private val _lanAddress = MutableStateFlow("localhost")
    val lanAddress: StateFlow<String> = _lanAddress.asStateFlow()
    private val _maxRetained = MutableStateFlow(10_000)
    val maxRetained: StateFlow<Int> = _maxRetained.asStateFlow()
    // The filter as authored, with its feature master beside it rather than folded in, so a frontend can
    // show what each list will come back armed with once the master flips on again (ADR-0082).
    private val _captureFilter = MutableStateFlow(CaptureFilter())
    val captureFilter: StateFlow<CaptureFilter> = _captureFilter.asStateFlow()
    private val _captureFilterEnabled = MutableStateFlow(true)
    val captureFilterEnabled: StateFlow<Boolean> = _captureFilterEnabled.asStateFlow()
    private val _pausedExchanges = MutableStateFlow<List<PausedExchange>>(emptyList())
    val pausedExchanges: StateFlow<List<PausedExchange>> = _pausedExchanges.asStateFlow()
    // The grouped structure is what the daemon actually holds (ADR-0081); the flat `…Rules` flows below
    // are its flattened projection, kept because most callers only ever want "which rules are there".
    // Both are updated from one poll field, so they cannot disagree.
    private val _mapLocalNodes = MutableStateFlow<List<DaemonRuleNode<HostMapLocalRule>>>(emptyList())
    val mapLocalNodes: StateFlow<List<DaemonRuleNode<HostMapLocalRule>>> = _mapLocalNodes.asStateFlow()
    private val _mapLocalRules = MutableStateFlow<List<HostMapLocalRule>>(emptyList())
    val mapLocalRules: StateFlow<List<HostMapLocalRule>> = _mapLocalRules.asStateFlow()
    private val _mapLocalEnabled = MutableStateFlow(true)
    val mapLocalEnabled: StateFlow<Boolean> = _mapLocalEnabled.asStateFlow()
    private val _breakpointNodes = MutableStateFlow<List<DaemonRuleNode<HostBreakpointRule>>>(emptyList())
    val breakpointNodes: StateFlow<List<DaemonRuleNode<HostBreakpointRule>>> = _breakpointNodes.asStateFlow()
    private val _breakpointRules = MutableStateFlow<List<HostBreakpointRule>>(emptyList())
    val breakpointRules: StateFlow<List<HostBreakpointRule>> = _breakpointRules.asStateFlow()
    private val _breakpointsEnabled = MutableStateFlow(true)
    val breakpointsEnabled: StateFlow<Boolean> = _breakpointsEnabled.asStateFlow()
    private val _seedNodes = MutableStateFlow<List<DaemonRuleNode<HostSeed>>>(emptyList())
    val seedNodes: StateFlow<List<DaemonRuleNode<HostSeed>>> = _seedNodes.asStateFlow()
    private val _seeds = MutableStateFlow<List<HostSeed>>(emptyList())
    val seeds: StateFlow<List<HostSeed>> = _seeds.asStateFlow()
    private val _seedsEnabled = MutableStateFlow(true)
    val seedsEnabled: StateFlow<Boolean> = _seedsEnabled.asStateFlow()

    /**
     * The armed queue, resolved against [seeds] from the ids the poll carries. A seed the library no
     * longer holds simply drops out, which is the same thing the daemon does to it.
     */
    private val _seedQueue = MutableStateFlow<List<HostSeed>>(emptyList())
    val seedQueue: StateFlow<List<HostSeed>> = _seedQueue.asStateFlow()

    /**
     * The holds the daemon has finished deciding about. A hold in [pausedExchanges] and in here is one
     * no seed answered, which is a frontend's cue that it needs a human.
     */
    private val _triagedHolds = MutableStateFlow<Set<String>>(emptySet())
    val triagedHolds: StateFlow<Set<String>> = _triagedHolds.asStateFlow()
    private val _pairing = MutableStateFlow(DaemonPairingState())
    val pairing: StateFlow<DaemonPairingState> = _pairing.asStateFlow()
    private val _mcpAccess = MutableStateFlow(true)
    val mcpAccess: StateFlow<Boolean> = _mcpAccess.asStateFlow()
    private val _mcpRedactSecrets = MutableStateFlow(true)
    val mcpRedactSecrets: StateFlow<Boolean> = _mcpRedactSecrets.asStateFlow()
    private val _studioAttached = MutableStateFlow(false)
    val studioAttached: StateFlow<Boolean> = _studioAttached.asStateFlow()

    /**
     * Counters the daemon relays between frontends (ADR-0065): the menu bar agent asks, and whichever
     * Studio is attached sees the number go up on its next poll and acts. Both start wherever the daemon
     * happens to be, so a frontend that connects late does not act on a request made before it existed.
     */
    private val _showStudioRequests = MutableStateFlow<Int?>(null)
    val showStudioRequests: StateFlow<Int?> = _showStudioRequests.asStateFlow()
    private val _quitRequests = MutableStateFlow<Int?>(null)
    val quitRequests: StateFlow<Int?> = _quitRequests.asStateFlow()
    private val _usbSupported = MutableStateFlow(false)
    val usbSupported: StateFlow<Boolean> = _usbSupported.asStateFlow()
    private val _usbPort = MutableStateFlow(8900)
    val usbPort: StateFlow<Int> = _usbPort.asStateFlow()
    private val _usbDevices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val usbDevices: StateFlow<List<UsbDeviceInfo>> = _usbDevices.asStateFlow()
    private val _adbSupported = MutableStateFlow(false)
    val adbSupported: StateFlow<Boolean> = _adbSupported.asStateFlow()
    private val _adbExecutable = MutableStateFlow<String?>(null)
    val adbExecutable: StateFlow<String?> = _adbExecutable.asStateFlow()
    private val _adbDevices = MutableStateFlow<List<AdbDeviceInfo>>(emptyList())
    val adbDevices: StateFlow<List<AdbDeviceInfo>> = _adbDevices.asStateFlow()

    /** The bundled proxy's daemon-owned state (ADR-0070): running, where, and what it is carrying. */
    private val _proxy = MutableStateFlow(ProxyStatus())
    val proxy: StateFlow<ProxyStatus> = _proxy.asStateFlow()

    private var mapHash: String? = null
    private var breakpointHash: String? = null
    private var seedHash: String? = null
    private var holdsHash: String? = null

    // The poll loop has to keep retrying rather than fail, so the reason it is not connected would
    // otherwise be lost and a frontend could only report "not ready" with no cause.
    @Volatile
    internal var lastPollFailure: Throwable? = null
        private set

    init {
        scope.launch { pollLoop() }
        presence?.let { connection -> scope.launch { presenceLoop(connection) } }
    }

    suspend fun awaitReady(timeout: Duration = 10.seconds): Boolean =
        withTimeoutOrNull(timeout) { connected.first { it } } != null

    /** The loopback port the daemon chose for its control channel, or null if none is published. */
    val controlPort: Int? get() = rpc.controlPort()

    fun listExchanges(): List<CapturedExchange> = exchanges.value
    fun listDevices(): List<ConnectedDevice> = connectedDevices.value
    fun listHolds(): List<PausedExchange> = pausedExchanges.value
    fun findExchangeById(id: String): CapturedExchange? = exchanges.value.firstOrNull { it.exchange.id == id }
    fun findHold(id: String): PausedExchange? = pausedExchanges.value.firstOrNull { it.correlationId == id }

    fun searchTraffic(
        urlContains: String? = null,
        urlPattern: String? = null,
        method: String? = null,
        statusCode: Int? = null,
        appId: String? = null,
    ): List<CapturedExchange> = exchanges.value.filter { row ->
        val request = row.exchange.request
        val url = request?.url.orEmpty()
        val matchesUrl = when {
            urlPattern != null -> urlPatternMatches(urlPattern, url)
            urlContains != null -> url.contains(urlContains, ignoreCase = true)
            else -> true
        }
        matchesUrl &&
            (method == null || request?.method.equals(method, ignoreCase = true)) &&
            (statusCode == null || row.exchange.response?.code == statusCode) &&
            (appId == null || row.appId == appId)
    }

    suspend fun waitForExchange(
        timeout: Duration = 30.seconds,
        predicate: (CapturedExchange) -> Boolean,
    ): CapturedExchange? = withTimeoutOrNull(timeout) {
        exchanges.first { rows -> rows.any(predicate) }.first(predicate)
    }

    suspend fun waitForHold(
        timeout: Duration = 30.seconds,
        predicate: (PausedExchange) -> Boolean = { true },
    ): PausedExchange? = withTimeoutOrNull(timeout) {
        pausedExchanges.first { rows -> rows.any(predicate) }.first(predicate)
    }

    suspend fun clear() {
        command("clear")
        _exchanges.value = emptyList()
    }

    /**
     * Fetch a slice of a captured body. Polls carry handles, not bytes (ADR-0069), so anything that
     * wants to show, search, or export a body asks for the part it is about to use — and a viewer
     * scrolled into the middle of a huge response never pulls the beginning of it.
     */
    suspend fun readBody(ref: BodyRef, offset: Long = 0, length: Int): ByteArray {
        if (length <= 0 || offset >= ref.size) return ByteArray(0)
        val response = rpc.call(
            "read_body",
            DaemonJson.encodeToJsonElement(ReadBodyRequest(ref.id, ref.size, offset, length)),
            ReadBodyResponse.serializer(),
        )
        return response.bytesBase64.decodeBase64()
    }

    suspend fun setCapturing(enabled: Boolean) {
        command("set_capturing", BooleanValue(enabled))
        _capturing.value = enabled
    }

    suspend fun setMaxRetained(value: Int) {
        command("set_max_retained", IntValue(value))
        _maxRetained.value = value
    }
    suspend fun rebind(port: Int): Boolean {
        val rebound = booleanCommand("rebind", IntValue(port))
        if (rebound) _capturePort.value = port
        return rebound
    }
    suspend fun restart(): Boolean = booleanCommand("restart")
    suspend fun setUsbPort(port: Int) {
        command("set_usb_port", IntValue(port))
        _usbPort.value = port
    }

    /**
     * Start or stop the bundled proxy, optionally moving it first. The daemon answers with the state it
     * actually reached rather than an acknowledgement: a port something else holds is the common case,
     * and the caller has to be able to say so.
     */
    suspend fun setProxyEnabled(enabled: Boolean, port: Int? = null): ProxyStatus {
        val status = rpc.call(
            "set_proxy",
            DaemonJson.encodeToJsonElement(SetProxyRequest(enabled, port)),
            ProxyStatusDto.serializer(),
        ).toDomain()
        _proxy.value = status
        return status
    }

    suspend fun setProxyPort(port: Int): ProxyStatus {
        val status = rpc.call(
            "set_proxy_port",
            DaemonJson.encodeToJsonElement(IntValue(port)),
            ProxyStatusDto.serializer(),
        ).toDomain()
        _proxy.value = status
        return status
    }

    /**
     * Bind the proxy beyond loopback so a phone or another machine can use it (ADR-0074). It is an open
     * relay while on, so a caller is expected to have said so.
     */
    suspend fun setProxyLan(enabled: Boolean): ProxyStatus {
        val status = rpc.call(
            "set_proxy_lan",
            DaemonJson.encodeToJsonElement(BooleanValue(enabled)),
            ProxyStatusDto.serializer(),
        ).toDomain()
        _proxy.value = status
        return status
    }

    /**
     * Point this machine's own network settings at the proxy, or restore them (ADR-0075). Turning it on
     * starts the proxy too: the two being out of step is a machine with no network.
     */
    suspend fun setSystemProxy(enabled: Boolean): ProxyStatus {
        val status = rpc.call(
            "set_system_proxy",
            DaemonJson.encodeToJsonElement(BooleanValue(enabled)),
            ProxyStatusDto.serializer(),
        ).toDomain()
        _proxy.value = status
        return status
    }

    /** Replace the hosts whose TLS the proxy terminates. Everything absent from [hosts] relocks. */
    suspend fun setProxyDecryptHosts(hosts: List<String>): ProxyStatus {
        val status = rpc.call(
            "set_proxy_decrypt",
            DaemonJson.encodeToJsonElement(SetProxyDecryptRequest(hosts)),
            ProxyStatusDto.serializer(),
        ).toDomain()
        _proxy.value = status
        return status
    }

    /**
     * The local root, minting one if this is the first ask (ADR-0073). Separate from the status flow on
     * purpose: polling for state must never be what puts a universal signing key on the machine.
     */
    suspend fun proxyCertificate(): ProxyCertificate =
        rpc.call("proxy_certificate", JsonNull, ProxyCertificateDto.serializer()).toDomain()

    suspend fun rotateProxyCertificate(): ProxyCertificate =
        rpc.call("rotate_proxy_certificate", JsonNull, ProxyCertificateDto.serializer()).toDomain()

    suspend fun removeProxyCertificate(): ProxyCertificate =
        rpc.call("remove_proxy_certificate", JsonNull, ProxyCertificateDto.serializer()).toDomain()

    /**
     * Re-reads the AI tool gate straight from the daemon. Callers that enforce it must not wait for the
     * next poll: turning access off has to refuse the very next tool call, not one 200ms later.
     */
    suspend fun refreshMcpSettings() {
        val settings = rpc.call("mcp_settings", JsonNull, McpSettingsDto.serializer())
        _mcpAccess.value = settings.access
        _mcpRedactSecrets.value = settings.redactSecrets
    }

    suspend fun setMcpAccess(enabled: Boolean) {
        command("set_mcp_access", BooleanValue(enabled))
        _mcpAccess.value = enabled
    }

    suspend fun setMcpRedactSecrets(enabled: Boolean) {
        command("set_mcp_redact_secrets", BooleanValue(enabled))
        _mcpRedactSecrets.value = enabled
    }

    /** Asks whichever Studio is attached to come forward; does nothing if none is (ADR-0065). */
    suspend fun requestShowStudio() = command("request_show_studio")

    /** Asks every attached frontend to exit. The caller stops the daemon itself, once they are gone. */
    suspend fun requestQuit() = command("request_quit")

    /**
     * Replaces the authored lists, and the master with them when [enabled] is given — the whole panel in
     * one call, which is what an authoring frontend needs: these flows drive what it renders, so two
     * calls would publish a state that was never authored and invite it straight back (ADR-0082).
     */
    suspend fun updateCaptureFilter(
        allowlistEnabled: Boolean,
        allowPatterns: List<String>,
        blocklistEnabled: Boolean,
        blockPatterns: List<String>,
        enabled: Boolean? = null,
    ) {
        command(
            "set_capture_filter",
            CaptureFilterRequest(
                allowlistEnabled,
                allowPatterns,
                blocklistEnabled,
                blockPatterns,
                enabled,
            ),
        )
        _captureFilter.value = CaptureFilter(
            allowlist_enabled = allowlistEnabled,
            allow_patterns = allowPatterns,
            blocklist_enabled = blocklistEnabled,
            block_patterns = blockPatterns,
        )
        enabled?.let { _captureFilterEnabled.value = it }
    }

    suspend fun setCaptureFilterEnabled(enabled: Boolean) {
        command("set_capture_filter_enabled", BooleanValue(enabled))
        _captureFilterEnabled.value = enabled
    }

    /** Replaces the whole panel, grouping included — the authoring frontend's single mutation. */
    suspend fun replaceMapLocalNodes(nodes: List<DaemonRuleNode<HostMapLocalRule>>, enabled: Boolean) {
        val dtos = nodes.mapRules { it.toDto() }
        command("replace_map_local", ReplaceMapLocalRequest(enabled, dtos))
        applyMapLocal(dtos)
        _mapLocalEnabled.value = enabled
    }

    /**
     * Adds or edits one rule, optionally filing it into [groupId] — the headless frontends' mutation,
     * since an agent edits one rule rather than redrawing a panel it cannot see. Fails when the group is
     * unknown, so a mistyped id is reported instead of quietly landing the rule somewhere else.
     */
    suspend fun upsertMapLocalRule(rule: HostMapLocalRule, groupId: String? = null) {
        val dto = rule.toDto()
        command("upsert_map_local", UpsertMapLocalRequest(dto, groupId))
        mapLocalNodeDtos.upsertRule(dto, groupId) { it.id }?.let(::applyMapLocal)
    }

    suspend fun removeMapLocalRule(id: String): Boolean {
        val removed = booleanCommand("remove_map_local", IdRequest(id))
        if (removed) applyMapLocal(mapLocalNodeDtos.removeRule(id) { it.id })
        return removed
    }

    suspend fun setMapLocalEnabled(enabled: Boolean) {
        command("set_map_local_enabled", BooleanValue(enabled))
        _mapLocalEnabled.value = enabled
    }

    suspend fun replaceBreakpointNodes(nodes: List<DaemonRuleNode<HostBreakpointRule>>, enabled: Boolean) {
        val dtos = nodes.mapRules { it.toDto() }
        command("replace_breakpoints", ReplaceBreakpointsRequest(enabled, dtos))
        applyBreakpoints(dtos)
        _breakpointsEnabled.value = enabled
    }

    suspend fun upsertBreakpointRule(rule: HostBreakpointRule, groupId: String? = null) {
        val dto = rule.toDto()
        command("upsert_breakpoint", UpsertBreakpointRequest(dto, groupId))
        breakpointNodeDtos.upsertRule(dto, groupId) { it.id }?.let(::applyBreakpoints)
    }

    suspend fun removeBreakpointRule(id: String): Boolean {
        val removed = booleanCommand("remove_breakpoint", IdRequest(id))
        if (removed) applyBreakpoints(breakpointNodeDtos.removeRule(id) { it.id })
        return removed
    }

    suspend fun setBreakpointsEnabled(enabled: Boolean) {
        command("set_breakpoints_enabled", BooleanValue(enabled))
        _breakpointsEnabled.value = enabled
    }

    suspend fun replaceSeedNodes(nodes: List<DaemonRuleNode<HostSeed>>, enabled: Boolean) {
        val dtos = nodes.mapRules { it.toDto() }
        command("replace_seeds", ReplaceSeedsRequest(enabled, dtos))
        applySeeds(dtos)
        _seedsEnabled.value = enabled
    }

    suspend fun upsertSeed(seed: HostSeed, groupId: String? = null) {
        val dto = seed.toDto()
        command("upsert_seed", UpsertSeedRequest(dto, groupId))
        seedNodeDtos.upsertRule(dto, groupId) { it.id }?.let(::applySeeds)
    }

    suspend fun removeSeed(id: String): Boolean {
        val removed = booleanCommand("remove_seed", IdRequest(id))
        if (removed) applySeeds(seedNodeDtos.removeRule(id) { it.id })
        return removed
    }

    /**
     * A family's layout with its rules erased to their ids, so a caller that only wants the *shape* —
     * which groups exist, and which one a rule is in — does not have to switch on the family itself.
     */
    fun nodesFor(family: String): List<DaemonRuleNode<String>> = when (family) {
        RULE_FAMILY_MAP_LOCAL -> _mapLocalNodes.value.mapRules { it.id }
        RULE_FAMILY_BREAKPOINTS -> _breakpointNodes.value.mapRules { it.id }
        RULE_FAMILY_SEEDS -> _seedNodes.value.mapRules { it.id }
        else -> emptyList()
    }

    /** Creates a group, or renames/re-gates one that exists. [family] is one of the `RULE_FAMILY_*` ids. */
    suspend fun setRuleGroup(family: String, group: DaemonRuleGroup) {
        command("set_rule_group", SetRuleGroupRequest(family, group))
        // Locally too, or the very next call to file a rule into it would be told the group is unknown.
        when (family) {
            RULE_FAMILY_MAP_LOCAL -> applyMapLocal(mapLocalNodeDtos.upsertGroup(group))
            RULE_FAMILY_BREAKPOINTS -> applyBreakpoints(breakpointNodeDtos.upsertGroup(group))
            RULE_FAMILY_SEEDS -> applySeeds(seedNodeDtos.upsertGroup(group))
        }
    }

    /** Deletes a group, keeping its rules as loose rules unless [withRules]. False when it never existed. */
    suspend fun removeRuleGroup(family: String, id: String, withRules: Boolean = false): Boolean {
        val removed = booleanCommand("remove_rule_group", RemoveRuleGroupRequest(family, id, withRules))
        if (removed) {
            when (family) {
                RULE_FAMILY_MAP_LOCAL -> mapLocalNodeDtos.removeGroup(id, withRules)?.let(::applyMapLocal)
                RULE_FAMILY_BREAKPOINTS -> breakpointNodeDtos.removeGroup(id, withRules)?.let(::applyBreakpoints)
                RULE_FAMILY_SEEDS -> seedNodeDtos.removeGroup(id, withRules)?.let(::applySeeds)
            }
        }
        return removed
    }

    suspend fun listRuleGroups(family: String): List<DaemonRuleGroup> = rpc.call(
        "list_rule_groups",
        DaemonJson.encodeToJsonElement(ListRuleGroupsRequest.serializer(), ListRuleGroupsRequest(family)),
        RuleGroupListDto.serializer(),
    ).groups

    suspend fun setSeedsEnabled(enabled: Boolean) {
        command("set_seeds_enabled", BooleanValue(enabled))
        _seedsEnabled.value = enabled
    }

    /**
     * Arms the enabled seed library and sweeps the holds already waiting (ADR-0044), returning how many
     * seeds are left armed afterwards. The daemon does both, so a Studio-less run scripts a sequence the
     * same way an open window does.
     */
    suspend fun fillSeeds(): Int = rpc.call("fill_seeds", JsonNull, IntValue.serializer()).value

    suspend fun clearSeedQueue() {
        command("clear_seed_queue")
        _seedQueue.value = emptyList()
    }

    suspend fun resumeHold(
        correlationId: String,
        editedRequest: HttpRequest? = null,
        editedResponse: HttpResponse? = null,
    ): Boolean = booleanCommand(
        "resume_hold",
        ResumeHoldRequest(
            correlationId,
            editedRequest?.encodeBase64(),
            editedResponse?.encodeBase64(),
        ),
    )

    suspend fun abortHold(correlationId: String): Boolean =
        booleanCommand("abort_hold", IdRequest(correlationId))

    suspend fun beginPairing() = pairingCommand(PairingActionRequest("begin"))
    suspend fun cancelPairing() = pairingCommand(PairingActionRequest("cancel"))
    suspend fun forgetDevice(id: String) = pairingCommand(PairingActionRequest("forget", deviceId = id))
    suspend fun forgetAllDevices() = pairingCommand(PairingActionRequest("forget_all"))
    suspend fun resetIdentity() = pairingCommand(PairingActionRequest("reset_identity"))
    suspend fun setRequirePairing(enabled: Boolean) =
        pairingCommand(PairingActionRequest("set_required", enabled = enabled))
    suspend fun dismissRefusal(id: String) =
        pairingCommand(PairingActionRequest("dismiss_refusal", deviceId = id))

    suspend fun stopDaemon() {
        command("stop")
        close()
    }

    override fun close() {
        presence?.close()
        scope.cancel()
        _connected.value = false
    }

    private suspend fun pairingCommand(request: PairingActionRequest) = command("pairing", request)

    private suspend fun presenceLoop(connection: DaemonPresenceConnection) {
        while (scope.isActive) {
            try {
                // Returns when the connection dies, so a daemon that was restarted or replaced gets
                // re-referenced without the frontend having to notice either event.
                withContext(Dispatchers.IO) { connection.hold() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
            delay(RECONNECT_INTERVAL_MS)
        }
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            try {
                val caughtUp = pollOnce()
                _connected.value = caughtUp
                lastPollFailure = null
                if (caughtUp) delay(POLL_INTERVAL_MS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _connected.value = false
                lastPollFailure = failure
                if (autoRestart) {
                    runCatching { DaemonLauncher.ensureRunning(startAfterExplicitStop = false) }
                }
                delay(RECONNECT_INTERVAL_MS)
            }
        }
    }

    /**
     * The wire-shaped mirror the local edits below are applied to, so an upsert lands in the same place
     * the daemon put it without this class re-deriving where that was. Keeping it in DTO form is what
     * lets [upsertRule] — the daemon's own placement rule — be the single implementation of it.
     */
    private var mapLocalNodeDtos: List<DaemonRuleNode<MapLocalRuleDto>> = emptyList()
    private var breakpointNodeDtos: List<DaemonRuleNode<BreakpointRuleDto>> = emptyList()
    private var seedNodeDtos: List<DaemonRuleNode<SeedRuleDto>> = emptyList()

    private fun applyMapLocal(nodes: List<DaemonRuleNode<MapLocalRuleDto>>) {
        mapLocalNodeDtos = nodes
        _mapLocalNodes.value = nodes.mapRules { it.toDomain() }
        _mapLocalRules.value = nodes.toHostRules()
    }

    private fun applyBreakpoints(nodes: List<DaemonRuleNode<BreakpointRuleDto>>) {
        breakpointNodeDtos = nodes
        _breakpointNodes.value = nodes.mapRules { it.toDomain() }
        _breakpointRules.value = nodes.toHostBreakpointRules()
    }

    private fun applySeeds(nodes: List<DaemonRuleNode<SeedRuleDto>>) {
        seedNodeDtos = nodes
        _seedNodes.value = nodes.mapRules { it.toDomain() }
        _seeds.value = nodes.toHostSeeds()
    }

    private suspend fun pollOnce(): Boolean {
        val current = _exchanges.value
        val pollRequest = PollRequest(
            lastExchangeId = current.lastOrNull()?.exchange?.id,
            hasExchanges = current.isNotEmpty(),
            mapLocalHash = mapHash,
            breakpointHash = breakpointHash,
            seedHash = seedHash,
            holdsHash = holdsHash,
        )
        val response = rpc.call(
            "poll",
            DaemonJson.encodeToJsonElement(PollRequest.serializer(), pollRequest),
            PollResponse.serializer(),
        )
        val incoming = response.exchanges.map(CapturedExchangeDto::toDomain)
        _exchanges.value = if (response.resetExchanges) {
            incoming
        } else {
            val first = response.firstExchangeId
            val retained = if (first == null) {
                emptyList()
            } else {
                current.dropWhile { it.exchange.id != first }
            }
            retained + incoming
        }
        _listening.value = response.listening
        _capturePort.value = response.capturePort
        _lanAddress.value = response.lanAddress
        _capturing.value = response.capturing
        _maxRetained.value = response.maxRetained
        _connectedDevices.value = response.connectedDevices.map(ConnectedDeviceDto::toDomain)
        _captureFilter.value = response.captureFilterBase64.decodeCaptureFilter()
        _captureFilterEnabled.value = response.captureFilterEnabled
        response.holds?.let { _pausedExchanges.value = it.map(PausedExchangeDto::toDomain) }
        holdsHash = response.holdsHash
        _mapLocalEnabled.value = response.mapLocalEnabled
        response.mapLocalNodes?.let(::applyMapLocal)
        mapHash = response.mapLocalHash
        _breakpointsEnabled.value = response.breakpointsEnabled
        response.breakpointNodes?.let(::applyBreakpoints)
        breakpointHash = response.breakpointHash
        _seedsEnabled.value = response.seedsEnabled
        response.seedNodes?.let(::applySeeds)
        seedHash = response.seedHash
        // Resolved after the library above, so a fill that arms a freshly pushed seed is never reported
        // as an id with nothing behind it.
        _seedQueue.value = _seeds.value.let { library ->
            response.armedSeedIds.mapNotNull { id -> library.firstOrNull { it.id == id } }
        }
        _triagedHolds.value = response.triagedHoldIds.toSet()
        _pairing.value = response.pairing.toPublic()
        _mcpAccess.value = response.mcpAccess
        _mcpRedactSecrets.value = response.mcpRedactSecrets
        _studioAttached.value = response.studioAttached
        _showStudioRequests.value = response.showStudioRequests
        _quitRequests.value = response.quitRequests
        _usbSupported.value = response.usbSupported
        _usbPort.value = response.usbPort
        _usbDevices.value = response.usbDevices.map {
            UsbDeviceInfo(it.udid, UsbConnectionStatus.valueOf(it.status), it.error)
        }
        _adbSupported.value = response.adbSupported
        _adbExecutable.value = response.adbExecutable
        _adbDevices.value = response.adbDevices.map {
            AdbDeviceInfo(it.serial, it.name, AdbConnectionStatus.valueOf(it.status), it.error)
        }
        response.proxy?.let { _proxy.value = it.toDomain() }
        return response.exchangesCaughtUp
    }

    private suspend fun command(command: String) {
        rpc.callRaw(command)
    }

    private suspend inline fun <reified T> command(command: String, value: T) {
        rpc.callRaw(command, DaemonJson.encodeToJsonElement(value))
    }

    private suspend fun booleanCommand(command: String): Boolean =
        rpc.call(command, JsonNull, BooleanValue.serializer()).value

    private suspend inline fun <reified T> booleanCommand(command: String, value: T): Boolean =
        rpc.call(
            command,
            DaemonJson.encodeToJsonElement(value),
            BooleanValue.serializer(),
        ).value

    companion object {
        private const val POLL_INTERVAL_MS = 200L
        private const val RECONNECT_INTERVAL_MS = 500L

        /**
         * [holdPresence] keeps the daemon alive for as long as this client lives (ADR-0062). Frontends
         * with a real lifetime — Studio, MCP, a blocking CLI wait — pass true; a one-shot command must
         * not, or it would start a daemon and take it down again on the way out.
         */
        suspend fun connect(
            autoStart: Boolean = true,
            initialCapturePort: Int? = null,
            initialMaxRetained: Int? = null,
            holdPresence: Boolean = false,
            clientKind: String = CLIENT_KIND_UNKNOWN,
        ): DaemonClient {
            if (autoStart) {
                withContext(Dispatchers.IO) {
                    DaemonLauncher.ensureRunning(
                        initialCapturePort = initialCapturePort,
                        initialMaxRetained = initialMaxRetained,
                    )
                }
            }
            val client = DaemonClient(
                DaemonRpcClient(),
                autoRestart = autoStart,
                holdsPresence = holdPresence,
                clientKind = clientKind,
            )
            if (client.awaitReady()) return client
            val reason = client.lastPollFailure?.message?.takeIf { it.isNotBlank() }
            client.close()
            error(
                "Wailo daemon did not become ready" +
                    (reason?.let { " (it answered: $it)" } ?: "") +
                    "; see ${DaemonLauncher.logPath()}",
            )
        }
    }
}

internal class DaemonRpcClient(
    private val handshakeStore: DaemonHandshakeStore = FileDaemonHandshakeStore(),
) {
    fun presence(kind: String = CLIENT_KIND_UNKNOWN) = DaemonPresenceConnection(handshakeStore, kind)

    suspend fun ping(): Boolean = runCatching {
        callRaw("ping")
        true
    }.getOrDefault(false)

    fun controlPort(): Int? = runCatching { handshakeStore.read().controlPort }.getOrNull()

    suspend fun controlProtocolVersion(): Int {
        val payload = callRaw("ping")
        return if (payload is JsonNull) {
            0
        } else {
            DaemonJson.decodeFromJsonElement(IntValue.serializer(), payload).value
        }
    }

    suspend fun callRaw(command: String, payload: JsonElement = JsonNull): JsonElement =
        request(RpcRequest(command, payload)).payload

    suspend fun <T> call(command: String, value: T, serializer: KSerializer<T>): T {
        val payload = DaemonJson.encodeToJsonElement(serializer, value)
        val response = request(RpcRequest(command, payload))
        return DaemonJson.decodeFromJsonElement(serializer, response.payload)
    }

    suspend fun <T> call(command: String, payload: JsonElement, serializer: KSerializer<T>): T {
        val response = request(RpcRequest(command, payload))
        return DaemonJson.decodeFromJsonElement(serializer, response.payload)
    }

    private suspend fun request(request: RpcRequest): RpcResponse = withContext(Dispatchers.IO) {
        // Re-read per call rather than caching: a daemon that restarted comes back on a different
        // OS-chosen port with a fresh token, and the handshake file is what tells us both.
        val handshake = handshakeStore.read()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, handshake.controlPort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            DaemonWire.writeRequest(
                DataOutputStream(socket.getOutputStream()),
                handshake.token,
                DaemonJson.encodeToString(RpcRequest.serializer(), request),
            )
            val response = DaemonJson.decodeFromString(
                RpcResponse.serializer(),
                DaemonWire.readResponse(DataInputStream(socket.getInputStream())),
            )
            if (!response.ok) throw IOException(response.error ?: "Wailo daemon command failed")
            response
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 1_500
        const val READ_TIMEOUT_MS = 30_000
    }
}

/**
 * The client half of the daemon's reference count (ADR-0062): one socket held open for the life of this
 * frontend. It carries no traffic — the daemon reads it only to learn when it dies — so being killed
 * releases the reference exactly as a clean exit does, which a detach message would not.
 */
internal class DaemonPresenceConnection(
    private val handshakeStore: DaemonHandshakeStore,
    private val kind: String = CLIENT_KIND_UNKNOWN,
) : AutoCloseable {
    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var released = false

    /** Opens the connection and blocks until either end drops it. */
    fun hold() {
        if (released) return
        val handshake = handshakeStore.read()
        val current = Socket()
        socket = current
        try {
            if (released) return
            current.connect(InetSocketAddress(LOOPBACK, handshake.controlPort), CONNECT_TIMEOUT_MS)
            current.soTimeout = HANDSHAKE_TIMEOUT_MS
            DaemonWire.writeRequest(
                DataOutputStream(current.getOutputStream()),
                handshake.token,
                DaemonJson.encodeToString(
                    RpcRequest.serializer(),
                    RpcRequest(
                        PRESENCE_COMMAND,
                        DaemonJson.encodeToJsonElement(PresenceRequest.serializer(), PresenceRequest(kind)),
                    ),
                ),
            )
            val input = DataInputStream(current.getInputStream())
            val accepted = DaemonJson.decodeFromString(
                RpcResponse.serializer(),
                DaemonWire.readResponse(input),
            )
            if (!accepted.ok) {
                throw IOException(accepted.error ?: "Wailo daemon refused the presence connection")
            }
            // Idling is the whole point of this socket, so it must not time out the way an RPC does.
            current.soTimeout = 0
            while (input.read() >= 0) Unit
        } finally {
            runCatching { current.close() }
            socket = null
        }
    }

    override fun close() {
        released = true
        // Closing from another thread is what ends the parked read; no interrupt reaches it.
        runCatching { socket?.close() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 1_500
        const val HANDSHAKE_TIMEOUT_MS = 5_000
    }
}

object DaemonLauncher {
    private const val RELAUNCH_INTERVAL_MS = 1_000L

    private const val MENUBAR_MAIN_CLASS = "com.venbiasa.wailo.menubar.MainKt"

    /** The escape hatch for CI and scripted runs, which want a daemon but no icon on someone's screen. */
    private const val NO_MENUBAR_ENV = "WAILO_NO_MENUBAR"

    private val startLock = Any()

    fun ensureRunning(
        timeoutMillis: Long = 10_000,
        initialCapturePort: Int? = null,
        initialMaxRetained: Int? = null,
        startAfterExplicitStop: Boolean = true,
    ) {
        ensureDaemon(timeoutMillis, initialCapturePort, initialMaxRetained, startAfterExplicitStop)
        // Outside the daemon check above on purpose: the daemon is often already up and the agent is what
        // died, and a frontend has no reason to care which of the two it just supplied. An explicit stop is
        // the one case where it must not try: the daemon is down because the user quit, an agent that
        // reaches none exits at once, and a polling frontend would spawn that doomed JVM twice a second for
        // as long as it stays open.
        if (!startAfterExplicitStop && DaemonStopMarker.isMarked()) return
        ensureMenubar()
    }

    /**
     * Starts the menu bar agent if nothing is drawing the item yet (ADR-0065). Best-effort by design: the
     * item is how the user *sees* the daemon, so failing to draw it must never be able to stop a frontend
     * from reaching one.
     */
    fun ensureMenubar() {
        runCatching {
            if (System.getenv(NO_MENUBAR_ENV)?.isNotBlank() == true) return
            // Written by an agent that found no system tray, so a headless machine wastes one JVM ever
            // rather than one per frontend command.
            if (Files.exists(menubarUnsupportedPath())) return
            // An install that does not ship the agent is a valid install; spawning its class name anyway
            // would just fail once per command.
            if (!menubarOnClasspath()) return
            // Racing spawns are harmless: the loser cannot take the lock and exits on its own.
            if (menubarRunning()) return
            launchMenubar()
        }
    }

    /**
     * Whether an agent is drawing the item right now, probed by trying to take the lock it holds for life —
     * so succeeding means nobody is there. An OS file lock is released on a crash or a kill, which is what
     * makes this answer current rather than merely recorded, the way a pid file would be.
     */
    fun menubarRunning(): Boolean = lockHeld(menubarLockPath())

    internal fun lockHeld(path: Path): Boolean {
        val probe = runCatching { DaemonSingleInstanceLock.tryAcquire(path) }.getOrElse { return false }
        probe?.close()
        return probe == null
    }

    private fun ensureDaemon(
        timeoutMillis: Long,
        initialCapturePort: Int?,
        initialMaxRetained: Int?,
        startAfterExplicitStop: Boolean,
    ) {
        if (startAfterExplicitStop) {
            DaemonStopMarker.clear()
        } else if (DaemonStopMarker.isMarked()) {
            return
        }
        if (runningVersionBlocking() == DAEMON_CONTROL_PROTOCOL_VERSION) return
        synchronized(startLock) {
            if (!startAfterExplicitStop && DaemonStopMarker.isMarked()) return
            val deadline = System.currentTimeMillis() + timeoutMillis
            // One loop rather than a replace-then-launch sequence, because the two are not separable: an
            // update leaves older frontends running (an editor's MCP adapter is the case that bites), and
            // whichever of them notices the daemon go will start its own. Reaching the wanted protocol has
            // to be retried until the deadline, or a version bump would crash the frontend the user just
            // updated while the stale ones carried on.
            var lastSeen: Int? = null
            var launchedAt = 0L
            while (true) {
                val runningVersion = runningVersionBlocking()
                when {
                    runningVersion == DAEMON_CONTROL_PROTOCOL_VERSION -> return
                    // Nothing this frontend can do: it is the outdated half, and talking down to a daemon
                    // built after it is exactly what the version check exists to prevent.
                    runningVersion != null && runningVersion > DAEMON_CONTROL_PROTOCOL_VERSION -> throw IOException(
                        "Wailo daemon protocol $runningVersion is newer than this frontend's " +
                            "protocol $DAEMON_CONTROL_PROTOCOL_VERSION; update the frontend",
                    )
                    runningVersion != null -> {
                        lastSeen = runningVersion
                        stopOutdatedDaemon(deadline)
                    }
                    // A daemon being replaced stops answering well before it releases the single-instance
                    // lock, since it still has to unwind capture, USB, and adb. The first spawn can find
                    // the state directory still owned and exit; keep spawning, because a loser costs one
                    // short-lived JVM while giving up costs the frontend its whole launch.
                    System.currentTimeMillis() - launchedAt >= RELAUNCH_INTERVAL_MS -> {
                        launchedAt = System.currentTimeMillis()
                        launch(initialCapturePort, initialMaxRetained)
                    }
                }
                if (System.currentTimeMillis() >= deadline) break
                Thread.sleep(100)
            }
            throw IOException(
                if (lastSeen != null) {
                    "Wailo daemon protocol $lastSeen would not give way to " +
                        "$DAEMON_CONTROL_PROTOCOL_VERSION; another Wailo tool is still running an older " +
                        "build — close it and retry"
                } else {
                    "Wailo daemon did not start; see ${logPath()}"
                },
            )
        }
    }

    private fun runningVersionBlocking(): Int? = runCatching {
        kotlinx.coroutines.runBlocking { DaemonRpcClient().controlProtocolVersion() }
    }.getOrNull()

    /**
     * Asks the daemon on the other end to stop and waits for it to go quiet. Best-effort and silent about
     * failure: the caller re-probes on every pass, so a stop that lands on an already-dead daemon or on one
     * that refuses is just another turn of the same loop.
     */
    private fun stopOutdatedDaemon(deadline: Long) {
        // Marked here rather than left to the daemon being replaced: it is what suppresses the auto-restart
        // path of every *other* frontend for the length of the handover, and the build being replaced is by
        // definition too old to be relied on for that. Never cleared on the way out — the caller launches
        // the replacement next, and that daemon clears it itself once it is actually answering.
        DaemonStopMarker.mark()
        runCatching { kotlinx.coroutines.runBlocking { DaemonRpcClient().callRaw("stop") } }
        while (System.currentTimeMillis() < deadline) {
            if (runningVersionBlocking() == null) return
            Thread.sleep(100)
        }
    }

    private fun launch(initialCapturePort: Int?, initialMaxRetained: Int?) {
        val process = jvm("com.venbiasa.wailo.daemon.MainKt")
        initialCapturePort?.let { process.environment()["WAILO_CAPTURE_PORT"] = it.toString() }
        initialMaxRetained?.let { process.environment()["WAILO_MAX_RETAINED"] = it.toString() }
        process.redirectTo(logPath()).start()
    }

    private fun launchMenubar() {
        // -Dapple.awt.UIElement keeps the agent out of the Dock and the Cmd-Tab list: it is a menu bar
        // item, and a second Wailo tile beside Studio's would be the opposite of making one daemon legible.
        jvm(MENUBAR_MAIN_CLASS, "-Dapple.awt.UIElement=true")
            .redirectTo(menubarLogPath())
            .start()
    }

    private fun jvm(mainClass: String, vararg jvmArgs: String): ProcessBuilder {
        val java = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (isWindows) "java.exe" else "java",
        )
        return ProcessBuilder(
            listOf(java.toString()) + jvmArgs + listOf("-cp", System.getProperty("java.class.path"), mainClass),
        )
    }

    private fun ProcessBuilder.redirectTo(log: Path): ProcessBuilder {
        Files.createDirectories(log.parent)
        return redirectInput(File(if (isWindows) "NUL" else "/dev/null"))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
    }

    private fun menubarOnClasspath(): Boolean = runCatching {
        // Resolve without initializing: this process must not load AWT just to decide whether to spawn.
        Class.forName(MENUBAR_MAIN_CLASS, false, DaemonLauncher::class.java.classLoader)
        true
    }.getOrDefault(false)

    private val isWindows get() = System.getProperty("os.name").startsWith("Windows", true)

    internal fun logPath(): Path = wailoStateDir().resolve("daemon.log")

    internal fun menubarLogPath(): Path = wailoStateDir().resolve("menubar.log")

    /** The agent holds this for its lifetime; that is the whole single-instance protocol (ADR-0065). */
    fun menubarLockPath(): Path = wailoStateDir().resolve("menubar.lock")

    /** Written once by an agent that found no system tray, so this machine stops being asked. */
    fun menubarUnsupportedPath(): Path = wailoStateDir().resolve("menubar.unsupported")
}

private fun PairingDto.toPublic() = DaemonPairingState(
    supported = supported,
    requirePairing = requirePairing,
    studioId = studioId,
    offer = offer?.let { DaemonPairingOffer(it.code, it.expiresAtEpochMs, it.qrPayload) },
    devices = devices.map {
        DaemonPairedDevice(it.deviceId, it.name, it.pairedAtEpochMs, it.lastSeenEpochMs, it.trustedOnFirstUse)
    },
    refusals = refusals.map { DaemonRefusedDevice(it.deviceId, it.reason) },
    suspectedClones = suspectedClones,
)
