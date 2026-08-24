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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // Loopback, deliberately unlike the shipped default (`DEFAULT_PROXY_LAN`): the callers that omit this
    // are tests, and a test has no business opening a port to the network it happens to be run on.
    proxyLan: Boolean = false,
    certificateAuthority: WailoCertificateAuthority = WailoCertificateAuthority(EphemeralCertificateAuthorityStore()),
    systemProxy: SystemProxyController = SystemProxyController.forThisMachine(),
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

    // The authored configuration, grouping included, in the shape every frontend reads it back in
    // (ADR-0081). The host below holds only what this compiles to — the flattened match set with each
    // group's switch already folded in — so a rule's own remembered state survives its group being off.
    //
    // Every edit is a read-modify-write of the whole list, and several frontends write at once, so each
    // family takes its lock for the whole edit-and-push. The host used to serialize this for us; now that
    // the authored copy lives up here, an unguarded write would drop one of two simultaneous rules.
    private val mapLocalMutex = Mutex()

    @Volatile
    var mapLocalNodes: List<DaemonRuleNode<MapLocalRuleDto>> = emptyList()
        private set

    private val breakpointMutex = Mutex()

    @Volatile
    var breakpointNodes: List<DaemonRuleNode<BreakpointRuleDto>> = emptyList()
        private set

    private val seedMutex = Mutex()

    @Volatile
    var seedNodes: List<DaemonRuleNode<SeedRuleDto>> = emptyList()
        private set

    // One host at a time rather than a whole-list replace: bookmarking is the one edit two frontends
    // plausibly make at once, and a read-modify-write of the list would drop one of them (ADR-0084).
    private val bookmarkLock = Any()

    @Volatile
    var bookmarkedHosts: List<String> = emptyList()
        private set

    // Derived from the fixtures store rather than injected beside it: a layout and the bodies it names
    // are one configuration, and two directories that could disagree is not a knob worth having.
    private val ruleBodies = DaemonRuleBodyStore(fixtures.directory)

    init {
        restoreFixtures()
    }

    /** A bounded slice of one authored body, for a frontend that is about to show or export it. */
    fun readRuleBody(family: String, id: String, offset: Long, length: Int): ByteArray {
        val body = ruleBodies.body(family, id)
        val from = offset.coerceIn(0, body.size.toLong()).toInt()
        val to = (from.toLong() + length.coerceAtLeast(0)).coerceAtMost(body.size.toLong()).toInt()
        return body.copyOfRange(from, to)
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
        val captureFilter = host.captureFilter.value
        val captureFilterHash = hash(listOf(captureFilter.toString(), host.isCaptureFilterEnabled()))
        val mapNodes = mapLocalNodes
        val mapHash = hash(mapNodes)
        val breakpointNodes = this.breakpointNodes
        val breakpointHash = hash(breakpointNodes)
        val seedNodes = this.seedNodes
        val seedHash = hash(seedNodes)
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
            // The authored filter, not the one the engine pushes to devices: a frontend has to see the
            // armed state the master is currently suppressing, or it cannot restore it (ADR-0082).
            captureFilterHash = captureFilterHash,
            captureFilterBase64 = if (request.captureFilterHash == captureFilterHash) {
                null
            } else {
                captureFilter.encodeBase64()
            },
            captureFilterEnabled = host.isCaptureFilterEnabled(),
            bookmarkedHosts = bookmarkedHosts,
            holdsHash = holdsHash,
            holds = holds.takeUnless { request.holdsHash == holdsHash }?.map { it.toDto() },
            mapLocalEnabled = host.isMapLocalEnabled(),
            mapLocalHash = mapHash,
            mapLocalNodes = mapNodes.takeUnless { request.mapLocalHash == mapHash },
            breakpointsEnabled = host.areBreakpointsEnabled(),
            breakpointHash = breakpointHash,
            breakpointNodes = breakpointNodes.takeUnless { request.breakpointHash == breakpointHash },
            seedsEnabled = host.areSeedsEnabled(),
            seedHash = seedHash,
            seedNodes = seedNodes.takeUnless { request.seedHash == seedHash },
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

    /**
     * Adopt a whole layout, taking only the bodies [bodies] actually carries (ADR-0086). Everything else
     * keeps the bytes already held under its id, which is what lets a reorder or a toggle cost nothing.
     */
    suspend fun replaceMapLocal(
        nodes: List<DaemonRuleNode<MapLocalRuleDto>>,
        enabled: Boolean,
        bodies: Map<String, ByteArray> = emptyMap(),
    ) = mapLocalMutex.withLock {
        bodies.forEach { (id, bytes) -> ruleBodies.put(RULE_FAMILY_MAP_LOCAL, id, bytes) }
        val known = mapLocalNodes.flattenRules().associateBy { it.id }
        mapLocalNodes = nodes.mapRules { rule ->
            val carried = known[rule.id].takeIf { rule.id !in bodies }
            if (carried == null) {
                val bytes = ruleBodies.body(RULE_FAMILY_MAP_LOCAL, rule.id)
                rule.copy(bodySize = bytes.size, bodyHash = bodyDigest(bytes), bodyBase64 = "")
            } else {
                rule.copy(bodySize = carried.bodySize, bodyHash = carried.bodyHash, bodyBase64 = "")
            }
        }
        pushMapLocal(enabled)
    }

    /**
     * Returns false when [groupId] names a group that does not exist — groups are addressed by id, so a
     * misspelling is reported rather than quietly creating a second group under a guessed name.
     */
    suspend fun upsertMapLocal(rule: MapLocalRuleDto, groupId: String?, body: ByteArray?): Boolean =
        mapLocalMutex.withLock {
            // A null body keeps whatever is stored, so an edit that only moves or renames the rule does
            // not blank it. The store is written after the group is validated, or a rejected upsert would
            // leave a body behind for a rule that was never filed.
            val bytes = body ?: ruleBodies.body(RULE_FAMILY_MAP_LOCAL, rule.id)
            val stored = rule.copy(bodySize = bytes.size, bodyHash = bodyDigest(bytes), bodyBase64 = "")
            val next = mapLocalNodes.upsertRule(stored, groupId) { it.id } ?: return@withLock false
            if (body != null) ruleBodies.put(RULE_FAMILY_MAP_LOCAL, rule.id, body)
            mapLocalNodes = next
            pushMapLocal(host.isMapLocalEnabled())
            true
        }

    suspend fun removeMapLocal(id: String): Boolean = mapLocalMutex.withLock {
        if (mapLocalNodes.findRule(id) { it.id } == null) return@withLock false
        mapLocalNodes = mapLocalNodes.removeRule(id) { it.id }
        pushMapLocal(host.isMapLocalEnabled())
        true
    }

    suspend fun setMapLocalEnabled(enabled: Boolean) = mapLocalMutex.withLock { pushMapLocal(enabled) }

    suspend fun replaceBreakpoints(nodes: List<DaemonRuleNode<BreakpointRuleDto>>, enabled: Boolean) =
        breakpointMutex.withLock {
            breakpointNodes = nodes
            pushBreakpoints(enabled)
        }

    suspend fun upsertBreakpoint(rule: BreakpointRuleDto, groupId: String?): Boolean = breakpointMutex.withLock {
        breakpointNodes = breakpointNodes.upsertRule(rule, groupId) { it.id } ?: return@withLock false
        pushBreakpoints(host.areBreakpointsEnabled())
        true
    }

    suspend fun removeBreakpoint(id: String): Boolean = breakpointMutex.withLock {
        if (breakpointNodes.findRule(id) { it.id } == null) return@withLock false
        breakpointNodes = breakpointNodes.removeRule(id) { it.id }
        pushBreakpoints(host.areBreakpointsEnabled())
        true
    }

    suspend fun setBreakpointsEnabled(enabled: Boolean) = breakpointMutex.withLock { pushBreakpoints(enabled) }

    suspend fun replaceSeeds(
        nodes: List<DaemonRuleNode<SeedRuleDto>>,
        enabled: Boolean,
        bodies: Map<String, ByteArray> = emptyMap(),
    ) = seedMutex.withLock {
        bodies.forEach { (id, bytes) -> ruleBodies.put(RULE_FAMILY_SEEDS, id, bytes) }
        val known = seedNodes.flattenRules().associateBy { it.id }
        seedNodes = nodes.mapRules { seed ->
            val carried = known[seed.id].takeIf { seed.id !in bodies }
            if (carried == null) {
                val bytes = ruleBodies.body(RULE_FAMILY_SEEDS, seed.id)
                seed.copy(bodySize = bytes.size, bodyHash = bodyDigest(bytes), bodyBase64 = "")
            } else {
                seed.copy(bodySize = carried.bodySize, bodyHash = carried.bodyHash, bodyBase64 = "")
            }
        }
        pushSeeds(enabled)
    }

    suspend fun upsertSeed(seed: SeedRuleDto, groupId: String?, body: ByteArray?): Boolean =
        seedMutex.withLock {
            val bytes = body ?: ruleBodies.body(RULE_FAMILY_SEEDS, seed.id)
            val stored = seed.copy(bodySize = bytes.size, bodyHash = bodyDigest(bytes), bodyBase64 = "")
            val next = seedNodes.upsertRule(stored, groupId) { it.id } ?: return@withLock false
            if (body != null) ruleBodies.put(RULE_FAMILY_SEEDS, seed.id, body)
            seedNodes = next
            pushSeeds(host.areSeedsEnabled())
            true
        }

    suspend fun removeSeed(id: String): Boolean = seedMutex.withLock {
        if (seedNodes.findRule(id) { it.id } == null) return@withLock false
        seedNodes = seedNodes.removeRule(id) { it.id }
        pushSeeds(host.areSeedsEnabled())
        true
    }

    suspend fun setSeedsEnabled(enabled: Boolean) = seedMutex.withLock { pushSeeds(enabled) }

    /** Creates or edits a group in [family]. Unknown families are rejected by the caller. */
    suspend fun setRuleGroup(family: String, group: DaemonRuleGroup) {
        when (family) {
            RULE_FAMILY_MAP_LOCAL -> mapLocalMutex.withLock {
                mapLocalNodes = mapLocalNodes.upsertGroup(group)
                pushMapLocal(host.isMapLocalEnabled())
            }
            RULE_FAMILY_BREAKPOINTS -> breakpointMutex.withLock {
                breakpointNodes = breakpointNodes.upsertGroup(group)
                pushBreakpoints(host.areBreakpointsEnabled())
            }
            RULE_FAMILY_SEEDS -> seedMutex.withLock {
                seedNodes = seedNodes.upsertGroup(group)
                pushSeeds(host.areSeedsEnabled())
            }
        }
    }

    suspend fun removeRuleGroup(family: String, id: String, withRules: Boolean): Boolean = when (family) {
        RULE_FAMILY_MAP_LOCAL -> mapLocalMutex.withLock {
            mapLocalNodes.removeGroup(id, withRules)?.let {
                mapLocalNodes = it
                pushMapLocal(host.isMapLocalEnabled())
                true
            } ?: false
        }
        RULE_FAMILY_BREAKPOINTS -> breakpointMutex.withLock {
            breakpointNodes.removeGroup(id, withRules)?.let {
                breakpointNodes = it
                pushBreakpoints(host.areBreakpointsEnabled())
                true
            } ?: false
        }
        RULE_FAMILY_SEEDS -> seedMutex.withLock {
            seedNodes.removeGroup(id, withRules)?.let {
                seedNodes = it
                pushSeeds(host.areSeedsEnabled())
                true
            } ?: false
        }
        else -> false
    }

    fun listRuleGroups(family: String): List<DaemonRuleGroup> = when (family) {
        RULE_FAMILY_MAP_LOCAL -> mapLocalNodes.groups()
        RULE_FAMILY_BREAKPOINTS -> breakpointNodes.groups()
        RULE_FAMILY_SEEDS -> seedNodes.groups()
        else -> emptyList()
    }

    // Every mutation lands through one of these, so the sweep for bodies whose rule is gone sits here
    // rather than at each call site — a group deleted with its rules drops its fixtures like a single
    // removal does, without either path having to remember to say so.
    private suspend fun pushMapLocal(enabled: Boolean) {
        ruleBodies.retain(RULE_FAMILY_MAP_LOCAL, mapLocalNodes.flattenRules().map { it.id })
        host.replaceMapLocalRules(mapLocalNodes.toHostRules(::mapLocalBody), enabled)
        persistMapLocal()
    }

    private suspend fun pushBreakpoints(enabled: Boolean) {
        host.replaceBreakpointRules(breakpointNodes.toHostBreakpointRules(), enabled)
        persistBreakpoints()
    }

    private suspend fun pushSeeds(enabled: Boolean) {
        ruleBodies.retain(RULE_FAMILY_SEEDS, seedNodes.flattenRules().map { it.id })
        host.replaceSeeds(seedNodes.toHostSeeds(::seedBody), enabled)
        persistSeeds()
    }

    private fun mapLocalBody(id: String): ByteArray = ruleBodies.body(RULE_FAMILY_MAP_LOCAL, id)

    private fun seedBody(id: String): ByteArray = ruleBodies.body(RULE_FAMILY_SEEDS, id)

    fun updateCaptureFilter(
        allowlistEnabled: Boolean,
        allowPatterns: List<String>,
        blocklistEnabled: Boolean,
        blockPatterns: List<String>,
        masterEnabled: Boolean? = null,
    ) {
        host.updateCaptureFilter(
            allowlistEnabled,
            allowPatterns,
            blocklistEnabled,
            blockPatterns,
            masterEnabled,
        )
        persistCaptureFilter()
    }

    fun setCaptureFilterEnabled(enabled: Boolean) {
        host.setCaptureFilterEnabled(enabled)
        persistCaptureFilter()
    }

    /** Adds or removes one host, keeping authoring order. Idempotent, so a repeat is not an error. */
    fun setBookmarked(host: String, bookmarked: Boolean) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        synchronized(bookmarkLock) {
            val current = bookmarkedHosts
            val next = when {
                bookmarked && trimmed !in current -> current + trimmed
                !bookmarked -> current - trimmed
                else -> return
            }
            bookmarkedHosts = next
            fixtures.saveBookmarks(PersistedBookmarks(next))
        }
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
                val restored = mapLocal.resolvedNodes()
                ruleBodies.load(RULE_FAMILY_MAP_LOCAL, restored.flattenRules().map { it.id })
                val migrated = adoptInlineMapLocalBodies(restored)
                mapLocalNodes = migrated ?: restored
                ruleBodies.retain(RULE_FAMILY_MAP_LOCAL, mapLocalNodes.flattenRules().map { it.id })
                host.replaceMapLocalRules(mapLocalNodes.toHostRules(::mapLocalBody), mapLocal.enabled)
                // The rewrite is what drops the inline copies, so it only runs where there were some.
                if (migrated != null) persistMapLocal()
            }
            fixtures.loadBreakpointsIfPresent()?.let { breakpoints ->
                breakpointNodes = breakpoints.resolvedNodes()
                host.replaceBreakpointRules(breakpointNodes.toHostBreakpointRules(), breakpoints.enabled)
            }
            fixtures.loadSeedsIfPresent()?.let { seeds ->
                val restored = seeds.resolvedNodes()
                ruleBodies.load(RULE_FAMILY_SEEDS, restored.flattenRules().map { it.id })
                val migrated = adoptInlineSeedBodies(restored)
                seedNodes = migrated ?: restored
                ruleBodies.retain(RULE_FAMILY_SEEDS, seedNodes.flattenRules().map { it.id })
                host.replaceSeeds(seedNodes.toHostSeeds(::seedBody), seeds.enabled)
                if (migrated != null) persistSeeds()
            }
            fixtures.loadCaptureFilterIfPresent()?.let { filter ->
                host.updateCaptureFilter(
                    filter.allowlistEnabled,
                    filter.allowPatterns,
                    filter.blocklistEnabled,
                    filter.blockPatterns,
                    filter.masterEnabled,
                )
            }
            bookmarkedHosts = fixtures.loadBookmarks().hosts
        }
    }

    /**
     * Move bodies a build before ADR-0086 wrote inline into the body store, and hand back the layout with
     * each one replaced by its reference. Null when nothing was inline, which is what keeps this from
     * rewriting the file on every start — and what makes it a one-shot rather than a versioned migration.
     *
     * Twinned with [adoptInlineSeedBodies] rather than shared: the two DTOs have no supertype, and an
     * interface plus three lambdas to spare a dozen lines is a worse trade than the repetition. They are
     * deleted together whenever the legacy field goes.
     */
    private fun adoptInlineMapLocalBodies(
        nodes: List<DaemonRuleNode<MapLocalRuleDto>>,
    ): List<DaemonRuleNode<MapLocalRuleDto>>? {
        if (nodes.flattenRules().none { it.bodyBase64.isNotEmpty() }) return null
        return nodes.mapRules { rule ->
            if (rule.bodyBase64.isEmpty()) {
                rule
            } else {
                val bytes = rule.bodyBase64.decodeInlineBody()
                ruleBodies.put(RULE_FAMILY_MAP_LOCAL, rule.id, bytes)
                rule.copy(bodySize = bytes.size, bodyHash = bodyDigest(bytes), bodyBase64 = "")
            }
        }
    }

    private fun adoptInlineSeedBodies(
        nodes: List<DaemonRuleNode<SeedRuleDto>>,
    ): List<DaemonRuleNode<SeedRuleDto>>? {
        if (nodes.flattenRules().none { it.bodyBase64.isNotEmpty() }) return null
        return nodes.mapRules { seed ->
            if (seed.bodyBase64.isEmpty()) {
                seed
            } else {
                val bytes = seed.bodyBase64.decodeInlineBody()
                ruleBodies.put(RULE_FAMILY_SEEDS, seed.id, bytes)
                seed.copy(bodySize = bytes.size, bodyHash = bodyDigest(bytes), bodyBase64 = "")
            }
        }
    }

    private fun persistCaptureFilter() {
        val authored = host.captureFilter.value
        fixtures.saveCaptureFilter(
            PersistedCaptureFilter(
                masterEnabled = host.isCaptureFilterEnabled(),
                allowlistEnabled = authored.allowlist_enabled,
                allowPatterns = authored.allow_patterns,
                blocklistEnabled = authored.blocklist_enabled,
                blockPatterns = authored.block_patterns,
            ),
        )
    }

    private fun persistMapLocal() {
        fixtures.saveMapLocal(
            PersistedMapLocal(enabled = host.isMapLocalEnabled(), nodes = mapLocalNodes),
        )
    }

    private fun persistBreakpoints() {
        fixtures.saveBreakpoints(
            PersistedBreakpoints(enabled = host.areBreakpointsEnabled(), nodes = breakpointNodes),
        )
    }

    private fun persistSeeds() {
        fixtures.saveSeeds(PersistedSeeds(enabled = host.areSeedsEnabled(), nodes = seedNodes))
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
        // Named rather than silently created: a group is addressed by id, so a caller that guessed one
        // wrong should hear about it instead of watching a duplicate group appear in the panel.
        fun unknownGroup(id: String?) =
            RpcResponse(ok = false, error = "No such group: ${id.orEmpty()}. Create it with set_rule_group.")
        fun unknownFamily(family: String) =
            RpcResponse(ok = false, error = "Unknown rule family: $family. Expected one of ${RULE_FAMILIES.joinToString()}.")
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
                    value.enabled,
                )
                success()
            }
            "set_capture_filter_enabled" -> {
                runtime.setCaptureFilterEnabled(request.decode(BooleanValue.serializer()).value)
                success()
            }
            "set_bookmark" -> {
                val value = request.decode(BookmarkRequest.serializer())
                runtime.setBookmarked(value.host, value.bookmarked)
                success()
            }
            "read_rule_body" -> {
                val value = request.decode(ReadRuleBodyRequest.serializer())
                if (value.family !in RULE_FAMILIES) {
                    unknownFamily(value.family)
                } else {
                    // Not clamped the way `read_body` is: that cap keeps a caller from pulling a spooled
                    // multi-gigabyte capture into the heap, while a rule body is already resident here.
                    // Clamping could only ever shorten a fixture the caller is about to save back.
                    val bytes = runtime.readRuleBody(value.family, value.id, value.offset, value.length)
                    success(DaemonJson.encodeToJsonElement(ReadRuleBodyResponse(bytes.encodeBase64())))
                }
            }
            "replace_map_local" -> {
                val value = request.decode(ReplaceMapLocalRequest.serializer())
                runtime.replaceMapLocal(value.nodes, value.enabled, value.bodies.decodeBodies())
                success()
            }
            "upsert_map_local" -> {
                val value = request.decode(UpsertMapLocalRequest.serializer())
                if (runtime.upsertMapLocal(value.rule, value.groupId, value.body?.decodeInlineBody())) {
                    success()
                } else {
                    unknownGroup(value.groupId)
                }
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
                runtime.replaceBreakpoints(value.nodes, value.enabled)
                success()
            }
            "upsert_breakpoint" -> {
                val value = request.decode(UpsertBreakpointRequest.serializer())
                if (runtime.upsertBreakpoint(value.rule, value.groupId)) {
                    success()
                } else {
                    unknownGroup(value.groupId)
                }
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
                runtime.replaceSeeds(value.nodes, value.enabled, value.bodies.decodeBodies())
                success()
            }
            "upsert_seed" -> {
                val value = request.decode(UpsertSeedRequest.serializer())
                if (runtime.upsertSeed(value.seed, value.groupId, value.body?.decodeInlineBody())) {
                    success()
                } else {
                    unknownGroup(value.groupId)
                }
            }
            "set_rule_group" -> {
                val value = request.decode(SetRuleGroupRequest.serializer())
                if (value.family !in RULE_FAMILIES) {
                    unknownFamily(value.family)
                } else {
                    runtime.setRuleGroup(value.family, value.group)
                    success()
                }
            }
            "remove_rule_group" -> {
                val value = request.decode(RemoveRuleGroupRequest.serializer())
                if (value.family !in RULE_FAMILIES) {
                    unknownFamily(value.family)
                } else {
                    success(
                        DaemonJson.encodeToJsonElement(
                            BooleanValue(runtime.removeRuleGroup(value.family, value.id, value.withRules)),
                        ),
                    )
                }
            }
            "list_rule_groups" -> {
                val value = request.decode(ListRuleGroupsRequest.serializer())
                if (value.family !in RULE_FAMILIES) {
                    unknownFamily(value.family)
                } else {
                    success(
                        DaemonJson.encodeToJsonElement(
                            RuleGroupListDto(runtime.listRuleGroups(value.family)),
                        ),
                    )
                }
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
