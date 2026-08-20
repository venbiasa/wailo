package com.venbiasa.wailo.daemon

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
    private val _captureFilter = MutableStateFlow(CaptureFilter())
    val captureFilter: StateFlow<CaptureFilter> = _captureFilter.asStateFlow()
    private val _pausedExchanges = MutableStateFlow<List<PausedExchange>>(emptyList())
    val pausedExchanges: StateFlow<List<PausedExchange>> = _pausedExchanges.asStateFlow()
    private val _mapLocalRules = MutableStateFlow<List<HostMapLocalRule>>(emptyList())
    val mapLocalRules: StateFlow<List<HostMapLocalRule>> = _mapLocalRules.asStateFlow()
    private val _mapLocalEnabled = MutableStateFlow(true)
    val mapLocalEnabled: StateFlow<Boolean> = _mapLocalEnabled.asStateFlow()
    private val _mapLocalLayout = MutableStateFlow("")
    val mapLocalLayout: StateFlow<String> = _mapLocalLayout.asStateFlow()
    private val _breakpointRules = MutableStateFlow<List<HostBreakpointRule>>(emptyList())
    val breakpointRules: StateFlow<List<HostBreakpointRule>> = _breakpointRules.asStateFlow()
    private val _breakpointsEnabled = MutableStateFlow(true)
    val breakpointsEnabled: StateFlow<Boolean> = _breakpointsEnabled.asStateFlow()
    private val _breakpointLayout = MutableStateFlow("")
    val breakpointLayout: StateFlow<String> = _breakpointLayout.asStateFlow()
    private val _seeds = MutableStateFlow<List<HostSeed>>(emptyList())
    val seeds: StateFlow<List<HostSeed>> = _seeds.asStateFlow()
    private val _seedsEnabled = MutableStateFlow(true)
    val seedsEnabled: StateFlow<Boolean> = _seedsEnabled.asStateFlow()
    private val _seedLayout = MutableStateFlow("")
    val seedLayout: StateFlow<String> = _seedLayout.asStateFlow()

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

    suspend fun updateCaptureFilter(
        allowlistEnabled: Boolean,
        allowPatterns: List<String>,
        blocklistEnabled: Boolean,
        blockPatterns: List<String>,
    ) {
        command(
            "set_capture_filter",
            CaptureFilterRequest(allowlistEnabled, allowPatterns, blocklistEnabled, blockPatterns),
        )
        _captureFilter.value = CaptureFilter(
            allowlist_enabled = allowlistEnabled,
            allow_patterns = allowPatterns,
            blocklist_enabled = blocklistEnabled,
            block_patterns = blockPatterns,
        )
    }

    suspend fun replaceMapLocalRules(
        rules: List<HostMapLocalRule>,
        enabled: Boolean,
        layout: String? = null,
    ) {
        command("replace_map_local", ReplaceMapLocalRequest(enabled, rules.map { it.toDto() }, layout))
        _mapLocalRules.value = rules.toList()
        _mapLocalEnabled.value = enabled
        if (layout != null) _mapLocalLayout.value = layout
    }

    suspend fun upsertMapLocalRule(rule: HostMapLocalRule) {
        command("upsert_map_local", rule.toDto())
        _mapLocalRules.value = _mapLocalRules.value.filterNot { it.id == rule.id } + rule
    }

    suspend fun removeMapLocalRule(id: String): Boolean {
        val removed = booleanCommand("remove_map_local", IdRequest(id))
        if (removed) _mapLocalRules.value = _mapLocalRules.value.filterNot { it.id == id }
        return removed
    }

    suspend fun setMapLocalEnabled(enabled: Boolean) {
        command("set_map_local_enabled", BooleanValue(enabled))
        _mapLocalEnabled.value = enabled
    }

    suspend fun replaceBreakpointRules(
        rules: List<HostBreakpointRule>,
        enabled: Boolean,
        layout: String? = null,
    ) {
        command("replace_breakpoints", ReplaceBreakpointsRequest(enabled, rules.map { it.toDto() }, layout))
        _breakpointRules.value = rules.toList()
        _breakpointsEnabled.value = enabled
        if (layout != null) _breakpointLayout.value = layout
    }

    suspend fun upsertBreakpointRule(rule: HostBreakpointRule) {
        command("upsert_breakpoint", rule.toDto())
        _breakpointRules.value = _breakpointRules.value.filterNot { it.id == rule.id } + rule
    }

    suspend fun removeBreakpointRule(id: String): Boolean {
        val removed = booleanCommand("remove_breakpoint", IdRequest(id))
        if (removed) _breakpointRules.value = _breakpointRules.value.filterNot { it.id == id }
        return removed
    }

    suspend fun setBreakpointsEnabled(enabled: Boolean) {
        command("set_breakpoints_enabled", BooleanValue(enabled))
        _breakpointsEnabled.value = enabled
    }

    suspend fun replaceSeeds(
        seeds: List<HostSeed>,
        enabled: Boolean,
        layout: String? = null,
    ) {
        command("replace_seeds", ReplaceSeedsRequest(enabled, seeds.map { it.toDto() }, layout))
        _seeds.value = seeds.toList()
        _seedsEnabled.value = enabled
        if (layout != null) _seedLayout.value = layout
    }

    suspend fun upsertSeed(seed: HostSeed) {
        command("upsert_seed", seed.toDto())
        _seeds.value = _seeds.value.filterNot { it.id == seed.id } + seed
    }

    suspend fun removeSeed(id: String): Boolean {
        val removed = booleanCommand("remove_seed", IdRequest(id))
        if (removed) _seeds.value = _seeds.value.filterNot { it.id == id }
        return removed
    }

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
        response.holds?.let { _pausedExchanges.value = it.map(PausedExchangeDto::toDomain) }
        holdsHash = response.holdsHash
        _mapLocalEnabled.value = response.mapLocalEnabled
        response.mapLocalRules?.let { _mapLocalRules.value = it.map(MapLocalRuleDto::toDomain) }
        response.mapLocalLayout?.let { _mapLocalLayout.value = it }
        mapHash = response.mapLocalHash
        _breakpointsEnabled.value = response.breakpointsEnabled
        response.breakpointRules?.let { _breakpointRules.value = it.map(BreakpointRuleDto::toDomain) }
        response.breakpointLayout?.let { _breakpointLayout.value = it }
        breakpointHash = response.breakpointHash
        _seedsEnabled.value = response.seedsEnabled
        response.seeds?.let { _seeds.value = it.map(SeedRuleDto::toDomain) }
        response.seedLayout?.let { _seedLayout.value = it }
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
