package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.BodyRef
import com.venbiasa.wailo.host.HeadlessHost
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.HostSeed
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Bind the control socket on an OS-chosen port and publish it in the handshake file, so the daemon can
 * never fail to start because an unrelated process holds one fixed port (ADR-0059).
 */
internal const val AUTO_PORT = 0

internal const val TOKEN_BYTES = 32

internal val DaemonJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

internal class DaemonRuntime(
    val host: HeadlessHost,
    private val usb: UsbController,
    private val adb: AdbController,
    private val settings: DaemonSettings,
    private val pairingSupported: Boolean,
    mcpAccess: Boolean = true,
    mcpRedactSecrets: Boolean = true,
    proxyPort: Int = DEFAULT_PROXY_PORT,
    proxyDecryptHosts: List<String> = emptyList(),
    proxyLan: Boolean = false,
    certificateAuthority: WailoCertificateAuthority = WailoCertificateAuthority(EphemeralCertificateAuthorityStore()),
    systemProxy: SystemProxyController = SystemProxyController(),
    private val fixtures: DaemonFixturesStore = DaemonFixturesStore(),
) : AutoCloseable {
    /**
     * The bundled proxy, off until something explicitly starts it (ADR-0070). Daemon-owned like every
     * other master, so a CLI or menu bar session can start and stop it with no window open.
     */
    private val proxy = ProxyController(
        host = host,
        initialPort = proxyPort,
        initialDecryptHosts = proxyDecryptHosts,
        initialLan = proxyLan,
        ca = certificateAuthority,
        system = systemProxy,
    )

    /** Whether the proxy is holding this daemon up: a client pointed at a dead one loses its network. */
    val proxyRunning: Boolean get() = proxy.running

    // Daemon-owned rather than engine-owned: neither value changes what is captured, only what an MCP
    // client is allowed to do with it, and both must outlive whichever frontend flipped them.
    private val _mcpAccess = MutableStateFlow(mcpAccess)
    private val _mcpRedactSecrets = MutableStateFlow(mcpRedactSecrets)
    val mcpAccess: StateFlow<Boolean> = _mcpAccess.asStateFlow()
    val mcpRedactSecrets: StateFlow<Boolean> = _mcpRedactSecrets.asStateFlow()

    // The menu bar agent cannot reach Studio's window, and Studio cannot be signalled by a process that
    // did not start it — so the daemon relays both asks as counters every frontend already polls for
    // (ADR-0065). Session state: a request that nothing was around to act on is not worth replaying.
    private val _showStudioRequests = MutableStateFlow(0)
    private val _quitRequests = MutableStateFlow(0)

    // Studio's grouped layout codec, opaque to this module. Loaded with the compiled rules so a restart
    // can restore groups/names, not just the flattened match-set the engine pushes (ADR-0061).
    @Volatile
    var mapLocalLayout: String = ""
        private set

    @Volatile
    var breakpointLayout: String = ""
        private set

    @Volatile
    var seedLayout: String = ""
        private set

    init {
        restoreFixtures()
    }

    fun poll(request: PollRequest): PollResponse {
        val current = host.listExchanges()
        val lastIndex = request.lastExchangeId?.let { id ->
            current.indexOfFirst { it.exchange.id == id }.takeIf { it >= 0 }
        }
        val reset = when {
            current.isEmpty() -> request.hasExchanges
            request.lastExchangeId == null -> request.hasExchanges
            lastIndex == null -> true
            else -> false
        }
        val exchangeRows = when {
            reset || request.lastExchangeId == null -> current
            else -> current.drop(lastIndex!! + 1)
        }
        val exchangeDtos = exchangeBatch(exchangeRows)

        val holds = host.listHolds()
        val holdsHash = hash(holds.map { it.toDto() })
        val mapRules = host.mapLocalRules.value
        val mapHash = hash(listOf(mapLocalLayout, mapRules.map { it.toDto() }))
        val breakpointRules = host.breakpointRules.value
        val breakpointHash = hash(listOf(breakpointLayout, breakpointRules.map { it.toDto() }))
        val seeds = host.seeds.value
        val seedHash = hash(listOf(seedLayout, seeds.map { it.toDto() }))
        val engine = host.engine
        val identity = engine.pairings.identity.value
        val offer = engine.pairings.offer.value
        val address = engine.lanAddress.value
        val port = engine.port
        return PollResponse(
            resetExchanges = reset,
            firstExchangeId = current.firstOrNull()?.exchange?.id,
            exchanges = exchangeDtos,
            exchangesCaughtUp = exchangeDtos.size == exchangeRows.size,
            listening = engine.listening.value,
            capturePort = port,
            lanAddress = address,
            capturing = engine.capturing.value,
            maxRetained = engine.maxRetained.value,
            connectedDevices = engine.connectedDevices.value.map { it.toDto() },
            captureFilterBase64 = engine.captureFilter.value.encodeBase64(),
            holdsHash = holdsHash,
            holds = holds.takeUnless { request.holdsHash == holdsHash }?.map { it.toDto() },
            mapLocalEnabled = host.isMapLocalEnabled(),
            mapLocalHash = mapHash,
            mapLocalRules = mapRules.takeUnless { request.mapLocalHash == mapHash }?.map { it.toDto() },
            mapLocalLayout = this.mapLocalLayout.takeUnless { request.mapLocalHash == mapHash },
            breakpointsEnabled = host.areBreakpointsEnabled(),
            breakpointHash = breakpointHash,
            breakpointRules = breakpointRules
                .takeUnless { request.breakpointHash == breakpointHash }
                ?.map { it.toDto() },
            breakpointLayout = this.breakpointLayout.takeUnless { request.breakpointHash == breakpointHash },
            seedsEnabled = host.areSeedsEnabled(),
            seedHash = seedHash,
            seeds = seeds.takeUnless { request.seedHash == seedHash }?.map { it.toDto() },
            seedLayout = this.seedLayout.takeUnless { request.seedHash == seedHash },
            armedSeedIds = host.seedQueue.value.map { it.id },
            triagedHoldIds = host.triagedHolds.value.toList(),
            pairing = PairingDto(
                supported = pairingSupported,
                requirePairing = engine.requirePairing.value,
                studioId = identity.studioId,
                offer = offer?.let {
                    PairingOfferDto(
                        code = it.code,
                        expiresAtEpochMs = it.expiresAtEpochMs,
                        qrPayload = it.qrPayload(identity.studioId, identity.publicKey, address, port),
                    )
                },
                devices = engine.pairings.devices.value.map { it.toDto() },
                refusals = engine.refusedDevices.value.map { it.toDto() },
                suspectedClones = engine.suspectedClones.value,
            ),
            mcpAccess = _mcpAccess.value,
            mcpRedactSecrets = _mcpRedactSecrets.value,
            showStudioRequests = _showStudioRequests.value,
            quitRequests = _quitRequests.value,
            usbSupported = usb.supported,
            usbPort = usb.devicePort.value,
            usbDevices = usb.devices.value.map { UsbDeviceDto(it.udid, it.status.name, it.error) },
            adbSupported = adb.supported,
            adbExecutable = adb.executablePath,
            adbDevices = adb.devices.value.map { AdbDeviceDto(it.serial, it.name, it.status.name, it.error) },
            proxy = proxy.sample().toDto(),
        )
    }

    fun setProxyEnabled(enabled: Boolean, port: Int? = null): ProxyStatus {
        if (port != null) proxy.setPort(port)
        if (enabled) proxy.start() else proxy.stop()
        val status = proxy.sample()
        // Persisted after the attempt, so a port that would not bind is not the one a restart retries.
        if (status.running) settings.update { it.copy(proxyPort = status.port) }
        return status
    }

    fun setProxyPort(port: Int): ProxyStatus {
        proxy.setPort(port)
        val status = proxy.sample()
        settings.update { it.copy(proxyPort = port) }
        return status
    }

    /**
     * Replace the set of hosts whose TLS is terminated (ADR-0071). `*` is honoured for the session but
     * never written: an escape hatch that survived a restart would stop being one.
     */
    fun setProxyDecryptHosts(hosts: List<String>): ProxyStatus {
        proxy.setDecryptHosts(hosts)
        val applied = proxy.status.value.decryptHosts
        settings.update { it.copy(proxyDecryptHosts = applied.filterNot { host -> host == "*" }) }
        return proxy.status.value
    }

    /**
     * Bind the proxy beyond loopback, or bring it back (ADR-0074). Persisted so a device configured once
     * keeps working, and applied immediately by restarting a running listener on the wider address.
     */
    fun setProxyLan(enabled: Boolean): ProxyStatus {
        val status = proxy.setLan(enabled)
        settings.update { it.copy(proxyLan = enabled) }
        return status
    }

    /**
     * Point this machine at the proxy, or put its settings back (ADR-0075). Session state on purpose:
     * a daemon that reclaimed the system proxy on every start would take over a machine nobody asked it
     * to, and it always restores on the way out.
     */
    fun setSystemProxy(enabled: Boolean): ProxyStatus = proxy.setSystemProxy(enabled)

    /** Mint the local root if there is not one yet — the one call that may create a signing key. */
    fun proxyCertificate(): ProxyCertificateDto = proxy.certificate().toDto(proxy.certificateError)

    fun rotateProxyCertificate(): ProxyCertificateDto = proxy.rotateCertificate().toDto(proxy.certificateError)

    fun removeProxyCertificate(): ProxyCertificateDto {
        proxy.removeCertificate()
        return null.toDto()
    }

    suspend fun rebind(port: Int): Boolean {
        if (port !in 1..65535) return false
        val rebound = host.rebind(port)
        if (rebound) {
            adb.setHostPort(port)
            settings.update { it.copy(capturePort = port) }
        }
        return rebound
    }

    fun setMaxRetained(value: Int) {
        require(value in 100..100_000) { "maxRetained must be between 100 and 100000" }
        host.setMaxRetained(value)
        settings.update { it.copy(maxRetained = value) }
    }

    fun setUsbPort(value: Int) {
        require(value in 1..65535) { "USB port must be between 1 and 65535" }
        usb.setDevicePort(value)
        settings.update { it.copy(usbPort = value) }
    }

    fun setRequirePairing(value: Boolean) {
        host.engine.setRequirePairing(value)
        settings.update { it.copy(requirePairing = value) }
    }

    fun setMcpAccess(value: Boolean) {
        _mcpAccess.value = value
        settings.update { it.copy(mcpAccess = value) }
    }

    fun setMcpRedactSecrets(value: Boolean) {
        _mcpRedactSecrets.value = value
        settings.update { it.copy(mcpRedactSecrets = value) }
    }

    fun requestShowStudio() {
        _showStudioRequests.value += 1
    }

    fun requestQuit() {
        _quitRequests.value += 1
    }

    suspend fun replaceMapLocal(
        rules: List<HostMapLocalRule>,
        enabled: Boolean,
        layout: String?,
    ) {
        host.replaceMapLocalRules(rules, enabled)
        // A full snapshot: omit means the compiled list is the authority, so drop a stale Studio blob.
        mapLocalLayout = layout.orEmpty()
        persistMapLocal()
    }

    suspend fun upsertMapLocal(rule: HostMapLocalRule) {
        host.upsertMapLocalRule(rule)
        persistMapLocal()
    }

    suspend fun removeMapLocal(id: String): Boolean {
        val removed = host.removeMapLocalRule(id)
        if (removed) persistMapLocal()
        return removed
    }

    suspend fun setMapLocalEnabled(enabled: Boolean) {
        host.setMapLocalEnabled(enabled)
        persistMapLocal()
    }

    suspend fun replaceBreakpoints(
        rules: List<HostBreakpointRule>,
        enabled: Boolean,
        layout: String?,
    ) {
        host.replaceBreakpointRules(rules, enabled)
        breakpointLayout = layout.orEmpty()
        persistBreakpoints()
    }

    suspend fun upsertBreakpoint(rule: HostBreakpointRule) {
        host.upsertBreakpointRule(rule)
        persistBreakpoints()
    }

    suspend fun removeBreakpoint(id: String): Boolean {
        val removed = host.removeBreakpointRule(id)
        if (removed) persistBreakpoints()
        return removed
    }

    suspend fun setBreakpointsEnabled(enabled: Boolean) {
        host.setBreakpointsEnabled(enabled)
        persistBreakpoints()
    }

    suspend fun replaceSeeds(seeds: List<HostSeed>, enabled: Boolean, layout: String?) {
        host.replaceSeeds(seeds, enabled)
        seedLayout = layout.orEmpty()
        persistSeeds()
    }

    suspend fun upsertSeed(seed: HostSeed) {
        host.upsertSeed(seed)
        persistSeeds()
    }

    suspend fun removeSeed(id: String): Boolean {
        val removed = host.removeSeed(id)
        if (removed) persistSeeds()
        return removed
    }

    suspend fun setSeedsEnabled(enabled: Boolean) {
        host.setSeedsEnabled(enabled)
        persistSeeds()
    }

    fun updateCaptureFilter(
        allowlistEnabled: Boolean,
        allowPatterns: List<String>,
        blocklistEnabled: Boolean,
        blockPatterns: List<String>,
    ) {
        host.updateCaptureFilter(allowlistEnabled, allowPatterns, blocklistEnabled, blockPatterns)
        fixtures.saveCaptureFilter(
            PersistedCaptureFilter(
                allowlistEnabled = allowlistEnabled,
                allowPatterns = allowPatterns,
                blocklistEnabled = blocklistEnabled,
                blockPatterns = blockPatterns,
            ),
        )
    }

    override fun close() {
        proxy.close()
        usb.close()
        adb.close()
        host.stop()
    }

    private fun restoreFixtures() {
        runBlocking {
            fixtures.loadMapLocalIfPresent()?.let { mapLocal ->
                mapLocalLayout = mapLocal.layout
                host.replaceMapLocalRules(mapLocal.rules.map { it.toDomain() }, mapLocal.enabled)
            }
            fixtures.loadBreakpointsIfPresent()?.let { breakpoints ->
                breakpointLayout = breakpoints.layout
                host.replaceBreakpointRules(breakpoints.rules.map { it.toDomain() }, breakpoints.enabled)
            }
            fixtures.loadSeedsIfPresent()?.let { seeds ->
                seedLayout = seeds.layout
                host.replaceSeeds(seeds.rules.map { it.toDomain() }, seeds.enabled)
            }
            fixtures.loadCaptureFilterIfPresent()?.let { filter ->
                host.updateCaptureFilter(
                    filter.allowlistEnabled,
                    filter.allowPatterns,
                    filter.blocklistEnabled,
                    filter.blockPatterns,
                )
            }
        }
    }

    private fun persistMapLocal() {
        fixtures.saveMapLocal(
            PersistedMapLocal(
                enabled = host.isMapLocalEnabled(),
                layout = mapLocalLayout,
                rules = host.mapLocalRules.value.map { it.toDto() },
            ),
        )
    }

    private fun persistBreakpoints() {
        fixtures.saveBreakpoints(
            PersistedBreakpoints(
                enabled = host.areBreakpointsEnabled(),
                layout = breakpointLayout,
                rules = host.breakpointRules.value.map { it.toDto() },
            ),
        )
    }

    private fun persistSeeds() {
        fixtures.saveSeeds(
            PersistedSeeds(
                enabled = host.areSeedsEnabled(),
                layout = seedLayout,
                rules = host.seeds.value.map { it.toDto() },
            ),
        )
    }

    private fun hash(value: Any): String {
        val bytes = DaemonJson.encodeToString(value.toString()).toByteArray()
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun exchangeBatch(rows: List<com.venbiasa.wailo.engine.CapturedExchange>): List<CapturedExchangeDto> {
        var encodedBytes = 0
        return buildList {
            for (row in rows.take(MAX_EXCHANGES_PER_POLL)) {
                val dto = row.toDto()
                val size = dto.exchangeBase64.length + dto.deviceName.length + dto.appId.length + dto.platform.length
                if (isNotEmpty() && encodedBytes + size > MAX_EXCHANGE_BYTES_PER_POLL) break
                add(dto)
                encodedBytes += size
            }
        }
    }

    private companion object {
        const val MAX_EXCHANGES_PER_POLL = 512
        const val MAX_EXCHANGE_BYTES_PER_POLL = 16 * 1024 * 1024
    }
}

internal class DaemonServer(
    private val runtime: DaemonRuntime,
    requestedPort: Int = AUTO_PORT,
    private val handshakeStore: DaemonHandshakeStore = FileDaemonHandshakeStore(),
    private val onStop: () -> Unit,
) : AutoCloseable {
    private val socket = ServerSocket()
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val closed = AtomicBoolean()
    private val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
    // Keyed by socket so a death removes exactly one frontend's claim; the value is only there to answer
    // "is a Studio here" for the menu bar agent (ADR-0065).
    private val presenceSockets: MutableMap<Socket, String> = ConcurrentHashMap()

    @Volatile
    private var lastActivityAt = System.currentTimeMillis()

    /** The port actually bound, which callers need because [AUTO_PORT] lets the OS choose one. */
    val port: Int get() = socket.localPort

    /** Frontends currently holding a presence connection open (ADR-0062). */
    val references: Int get() = presenceSockets.size

    /** Whether one of those frontends is a Studio, which is what makes Show Studio a raise and not a launch. */
    val studioAttached: Boolean get() = presenceSockets.containsValue(CLIENT_KIND_STUDIO)

    /** When a request last arrived, which is what the idle linger measures from. */
    val lastActivityAtMillis: Long get() = lastActivityAt

    init {
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(LOOPBACK, requestedPort))
        try {
            handshakeStore.write(
                DaemonHandshake(
                    controlPort = socket.localPort,
                    protocolVersion = DAEMON_CONTROL_PROTOCOL_VERSION,
                    token = token,
                ),
            )
        } catch (failure: Exception) {
            socket.close()
            executor.shutdownNow()
            throw failure
        }
    }

    fun start() {
        executor.execute(::acceptLoop)
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                return
            }
            executor.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use {
            val input = DataInputStream(it.getInputStream())
            val output = DataOutputStream(it.getOutputStream())
            // Throwable, not Exception, on both halves: a request that dies on an Error (a jar swapped out
            // from under a running daemon during the dev loop is the one that happens) would otherwise kill
            // this thread and close the socket with no reply, leaving every client to report an unexplained
            // readiness timeout. One bad request must still produce an answer the caller can quote.
            val request = try {
                val framed = DaemonWire.readRequest(input)
                if (MessageDigest.isEqual(token, framed.token)) {
                    DaemonJson.decodeFromString(RpcRequest.serializer(), framed.json)
                } else {
                    reply(output, RpcResponse(ok = false, error = "Daemon authentication failed"))
                    return@use
                }
            } catch (failure: Throwable) {
                reply(output, RpcResponse(ok = false, error = failure.rpcError()))
                return@use
            }
            lastActivityAt = System.currentTimeMillis()
            if (request.command == PRESENCE_COMMAND) {
                reply(output, RpcResponse(ok = true))
                // An older frontend sends no payload at all, which decodes to the unknown kind — it still
                // counts as a reference, it just cannot be offered up as a Studio to raise.
                val kind = runCatching { request.decode(PresenceRequest.serializer()).kind }
                    .getOrDefault(CLIENT_KIND_UNKNOWN)
                holdPresence(it, input, kind)
                return@use
            }
            val response = try {
                runBlocking { dispatch(request) }
            } catch (failure: Throwable) {
                RpcResponse(ok = false, error = failure.rpcError())
            }
            reply(output, response)
        }
    }

    /**
     * Blocks until the peer's socket dies, which is the reference itself: the OS closes it on a crash or
     * SIGKILL just as it does on a clean exit, so the count cannot leak the way an unpaired detach would.
     */
    private fun holdPresence(client: Socket, input: DataInputStream, kind: String) {
        presenceSockets[client] = kind
        try {
            while (input.read() >= 0) Unit
        } catch (_: IOException) {
        } finally {
            presenceSockets -= client
            // Releasing a reference is activity too, so the linger is measured from the moment the last
            // frontend left rather than from whatever it happened to poll before that.
            lastActivityAt = System.currentTimeMillis()
        }
    }

    private fun reply(output: DataOutputStream, response: RpcResponse) {
        DaemonWire.writeResponse(output, DaemonJson.encodeToString(RpcResponse.serializer(), response))
    }

    private suspend fun dispatch(request: RpcRequest): RpcResponse {
        fun success(payload: JsonElement = JsonNull) = RpcResponse(ok = true, payload = payload)
        return when (request.command) {
            "ping" -> success(
                DaemonJson.encodeToJsonElement(IntValue(DAEMON_CONTROL_PROTOCOL_VERSION)),
            )
            "poll" -> success(
                DaemonJson.encodeToJsonElement(
                    // Who is attached is the server's knowledge, not the runtime's — it owns the sockets.
                    runtime.poll(request.decode(PollRequest.serializer())).copy(studioAttached = studioAttached),
                ),
            )
            "clear" -> {
                runtime.host.clear()
                success()
            }
            "read_body" -> {
                val value = request.decode(ReadBodyRequest.serializer())
                val bytes = runtime.host.readBody(
                    BodyRef(value.id, value.size),
                    value.offset,
                    value.length.coerceAtMost(MAX_BODY_READ_BYTES),
                )
                success(DaemonJson.encodeToJsonElement(ReadBodyResponse(bytes.encodeBase64())))
            }
            "set_capturing" -> {
                runtime.host.setCapturing(request.decode(BooleanValue.serializer()).value)
                success()
            }
            "set_max_retained" -> {
                runtime.setMaxRetained(request.decode(IntValue.serializer()).value)
                success()
            }
            "rebind" -> success(
                DaemonJson.encodeToJsonElement(BooleanValue(runtime.rebind(request.decode(IntValue.serializer()).value))),
            )
            "restart" -> success(DaemonJson.encodeToJsonElement(BooleanValue(runtime.host.engine.restart())))
            "mcp_settings" -> success(
                DaemonJson.encodeToJsonElement(
                    McpSettingsDto(
                        access = runtime.mcpAccess.value,
                        redactSecrets = runtime.mcpRedactSecrets.value,
                    ),
                ),
            )
            "set_mcp_access" -> {
                runtime.setMcpAccess(request.decode(BooleanValue.serializer()).value)
                success()
            }
            "set_mcp_redact_secrets" -> {
                runtime.setMcpRedactSecrets(request.decode(BooleanValue.serializer()).value)
                success()
            }
            "request_show_studio" -> {
                runtime.requestShowStudio()
                success()
            }
            "request_quit" -> {
                runtime.requestQuit()
                success()
            }
            "set_usb_port" -> {
                runtime.setUsbPort(request.decode(IntValue.serializer()).value)
                success()
            }
            "set_proxy" -> {
                val value = request.decode(SetProxyRequest.serializer())
                success(DaemonJson.encodeToJsonElement(runtime.setProxyEnabled(value.enabled, value.port).toDto()))
            }
            "set_proxy_port" -> success(
                DaemonJson.encodeToJsonElement(
                    runtime.setProxyPort(request.decode(IntValue.serializer()).value).toDto(),
                ),
            )
            "set_proxy_decrypt" -> success(
                DaemonJson.encodeToJsonElement(
                    runtime.setProxyDecryptHosts(request.decode(SetProxyDecryptRequest.serializer()).hosts).toDto(),
                ),
            )
            "set_proxy_lan" -> success(
                DaemonJson.encodeToJsonElement(
                    runtime.setProxyLan(request.decode(BooleanValue.serializer()).value).toDto(),
                ),
            )
            "set_system_proxy" -> success(
                DaemonJson.encodeToJsonElement(
                    runtime.setSystemProxy(request.decode(BooleanValue.serializer()).value).toDto(),
                ),
            )
            "proxy_certificate" -> success(DaemonJson.encodeToJsonElement(runtime.proxyCertificate()))
            "rotate_proxy_certificate" -> success(
                DaemonJson.encodeToJsonElement(runtime.rotateProxyCertificate()),
            )
            "remove_proxy_certificate" -> success(
                DaemonJson.encodeToJsonElement(runtime.removeProxyCertificate()),
            )
            "set_capture_filter" -> {
                val value = request.decode(CaptureFilterRequest.serializer())
                runtime.updateCaptureFilter(
                    value.allowlistEnabled,
                    value.allowPatterns,
                    value.blocklistEnabled,
                    value.blockPatterns,
                )
                success()
            }
            "replace_map_local" -> {
                val value = request.decode(ReplaceMapLocalRequest.serializer())
                runtime.replaceMapLocal(value.rules.map(MapLocalRuleDto::toDomain), value.enabled, value.layout)
                success()
            }
            "upsert_map_local" -> {
                runtime.upsertMapLocal(request.decode(MapLocalRuleDto.serializer()).toDomain())
                success()
            }
            "remove_map_local" -> success(
                DaemonJson.encodeToJsonElement(
                    BooleanValue(runtime.removeMapLocal(request.decode(IdRequest.serializer()).id)),
                ),
            )
            "set_map_local_enabled" -> {
                runtime.setMapLocalEnabled(request.decode(BooleanValue.serializer()).value)
                success()
            }
            "replace_breakpoints" -> {
                val value = request.decode(ReplaceBreakpointsRequest.serializer())
                runtime.replaceBreakpoints(
                    value.rules.map(BreakpointRuleDto::toDomain),
                    value.enabled,
                    value.layout,
                )
                success()
            }
            "upsert_breakpoint" -> {
                runtime.upsertBreakpoint(request.decode(BreakpointRuleDto.serializer()).toDomain())
                success()
            }
            "remove_breakpoint" -> success(
                DaemonJson.encodeToJsonElement(
                    BooleanValue(runtime.removeBreakpoint(request.decode(IdRequest.serializer()).id)),
                ),
            )
            "set_breakpoints_enabled" -> {
                runtime.setBreakpointsEnabled(request.decode(BooleanValue.serializer()).value)
                success()
            }
            "replace_seeds" -> {
                val value = request.decode(ReplaceSeedsRequest.serializer())
                runtime.replaceSeeds(value.rules.map(SeedRuleDto::toDomain), value.enabled, value.layout)
                success()
            }
            "upsert_seed" -> {
                runtime.upsertSeed(request.decode(SeedRuleDto.serializer()).toDomain())
                success()
            }
            "remove_seed" -> success(
                DaemonJson.encodeToJsonElement(
                    BooleanValue(runtime.removeSeed(request.decode(IdRequest.serializer()).id)),
                ),
            )
            "set_seeds_enabled" -> {
                runtime.setSeedsEnabled(request.decode(BooleanValue.serializer()).value)
                success()
            }
            "fill_seeds" -> success(DaemonJson.encodeToJsonElement(IntValue(runtime.host.fillSeeds())))
            "clear_seed_queue" -> {
                runtime.host.clearSeedQueue()
                success()
            }
            "resume_hold" -> {
                val value = request.decode(ResumeHoldRequest.serializer())
                success(
                    DaemonJson.encodeToJsonElement(
                        BooleanValue(
                            runtime.host.resumeHold(
                                value.correlationId,
                                value.editedRequestBase64?.decodeRequest(),
                                value.editedResponseBase64?.decodeResponse(),
                            ),
                        ),
                    ),
                )
            }
            "abort_hold" -> success(
                DaemonJson.encodeToJsonElement(
                    BooleanValue(runtime.host.abortHold(request.decode(IdRequest.serializer()).id)),
                ),
            )
            "pairing" -> {
                val value = request.decode(PairingActionRequest.serializer())
                when (value.action) {
                    "begin" -> runtime.host.engine.pairings.beginPairing()
                    "cancel" -> runtime.host.engine.pairings.cancelPairing()
                    "forget" -> runtime.host.engine.forgetDevice(requireNotNull(value.deviceId))
                    "forget_all" -> runtime.host.engine.forgetAllDevices()
                    "reset_identity" -> runtime.host.engine.resetIdentity()
                    "set_required" -> runtime.setRequirePairing(requireNotNull(value.enabled))
                    "dismiss_refusal" -> runtime.host.engine.dismissRefusal(requireNotNull(value.deviceId))
                    else -> error("Unknown pairing action ${value.action}")
                }
                success()
            }
            "stop" -> {
                DaemonStopMarker.mark()
                thread(name = "wailo-daemon-stop") {
                    Thread.sleep(STOP_RESPONSE_GRACE_MS)
                    onStop()
                }
                success()
            }
            else -> RpcResponse(ok = false, error = "Unknown daemon command: ${request.command}")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        // Closing the server socket does not touch accepted ones, and a presence reader is parked on a
        // blocking read that no interrupt reaches.
        presenceSockets.keys.forEach { runCatching(it::close) }
        executor.shutdownNow()
        handshakeStore.delete(token)
    }

    private fun <T> RpcRequest.decode(serializer: KSerializer<T>): T =
        DaemonJson.decodeFromJsonElement(serializer, payload)

    /** An `Exception` message is already the text a frontend should show; an `Error`'s needs its type to mean anything. */
    private fun Throwable.rpcError(): String {
        val detail = message?.takeIf { it.isNotBlank() }
        val type = this::class.simpleName ?: "failure"
        return when {
            detail == null -> type
            this is Exception -> detail
            else -> "$type: $detail"
        }
    }

    private companion object {
        const val STOP_RESPONSE_GRACE_MS = 100L

        /**
         * The most one `read_body` may return. A range read exists so a caller never has to hold a whole
         * body; a caller that asks for one anyway gets a page at a time instead of taking the daemon's
         * heap down with it.
         */
        const val MAX_BODY_READ_BYTES = 8 * 1024 * 1024
    }
}

/**
 * What a client needs to reach the running daemon: where it listens, which control protocol it speaks,
 * and the secret proving the caller is this user. Published as one file so there is no window in which
 * a client can read a port without its matching token (ADR-0059).
 */
internal data class DaemonHandshake(
    val controlPort: Int,
    val protocolVersion: Int,
    val token: ByteArray,
) {
    // ByteArray identity would make two equal handshakes compare unequal, which the tests rely on.
    override fun equals(other: Any?): Boolean = other is DaemonHandshake &&
        controlPort == other.controlPort &&
        protocolVersion == other.protocolVersion &&
        token.contentEquals(other.token)

    override fun hashCode(): Int =
        (controlPort * 31 + protocolVersion) * 31 + token.contentHashCode()
}

@Serializable
private data class DaemonHandshakeDto(
    val controlPort: Int,
    val protocolVersion: Int,
    val token: String,
)

internal interface DaemonHandshakeStore {
    fun write(handshake: DaemonHandshake)
    fun read(): DaemonHandshake
    fun delete(token: ByteArray)
}

internal class FileDaemonHandshakeStore(
    private val path: Path = wailoStateDir().resolve("daemon.json"),
) : DaemonHandshakeStore {
    override fun write(handshake: DaemonHandshake) {
        Files.createDirectories(path.parent)
        setOwnerOnly(path.parent, directory = true)
        val temporary = Files.createTempFile(path.parent, ".daemon-handshake-", ".tmp")
        try {
            // Owner-only before the contents land: the token is a bearer credential for full control of
            // capture, mocks, and breakpoint decisions.
            setOwnerOnly(temporary, directory = false)
            Files.writeString(
                temporary,
                DaemonJson.encodeToString(
                    DaemonHandshakeDto.serializer(),
                    DaemonHandshakeDto(
                        controlPort = handshake.controlPort,
                        protocolVersion = handshake.protocolVersion,
                        token = HexFormat.of().formatHex(handshake.token),
                    ),
                ),
            )
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun read(): DaemonHandshake {
        val dto = try {
            DaemonJson.decodeFromString(DaemonHandshakeDto.serializer(), Files.readString(path))
        } catch (failure: Exception) {
            throw IOException("Wailo daemon handshake is unavailable", failure)
        }
        val token = runCatching { HexFormat.of().parseHex(dto.token) }.getOrNull()
        if (token == null || token.size != TOKEN_BYTES || dto.controlPort !in 1..65535) {
            throw IOException("Wailo daemon handshake is invalid")
        }
        return DaemonHandshake(dto.controlPort, dto.protocolVersion, token)
    }

    override fun delete(token: ByteArray) {
        val current = runCatching { read().token }.getOrNull()
        if (current != null && MessageDigest.isEqual(current, token)) runCatching { Files.deleteIfExists(path) }
    }

    private fun setOwnerOnly(target: Path, directory: Boolean) {
        val permissions = if (directory) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        runCatching { Files.setPosixFilePermissions(target, permissions) }
    }
}

internal class MemoryDaemonHandshakeStore : DaemonHandshakeStore {
    @Volatile
    private var handshake: DaemonHandshake? = null

    override fun write(handshake: DaemonHandshake) {
        this.handshake = handshake
    }

    override fun read(): DaemonHandshake =
        handshake ?: throw IOException("Wailo daemon handshake is unavailable")

    override fun delete(token: ByteArray) {
        if (handshake?.let { MessageDigest.isEqual(it.token, token) } == true) handshake = null
    }
}

internal object DaemonWire {
    private const val MAGIC = 0x57414944
    private const val VERSION = 1
    private const val MAX_TOKEN_BYTES = 128
    private const val MAX_REQUEST_BYTES = 256 * 1024 * 1024
    private const val MAX_RESPONSE_BYTES = 256 * 1024 * 1024

    data class FramedRequest(val token: ByteArray, val json: String)

    fun writeRequest(output: DataOutputStream, token: ByteArray, json: String) {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        writeBytes(output, token)
        writeBytes(output, json.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    fun readRequest(input: DataInputStream): FramedRequest {
        require(input.readInt() == MAGIC) { "Not a Wailo daemon request" }
        require(input.readInt() == VERSION) { "Unsupported Wailo daemon protocol version" }
        return FramedRequest(
            token = readBytes(input, MAX_TOKEN_BYTES),
            json = readBytes(input, MAX_REQUEST_BYTES).toString(Charsets.UTF_8),
        )
    }

    fun writeResponse(output: DataOutputStream, json: String) {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        writeBytes(output, json.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    fun readResponse(input: DataInputStream): String {
        require(input.readInt() == MAGIC) { "Not a Wailo daemon response" }
        require(input.readInt() == VERSION) { "Unsupported Wailo daemon protocol version" }
        return readBytes(input, MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)
    }

    private fun writeBytes(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readBytes(input: DataInputStream, maximum: Int): ByteArray {
        val size = input.readInt()
        require(size in 0..maximum) { "Invalid daemon frame length" }
        return ByteArray(size).also(input::readFully)
    }
}

internal val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
