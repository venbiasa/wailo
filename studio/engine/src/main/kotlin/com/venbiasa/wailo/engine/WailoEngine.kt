package com.venbiasa.wailo.engine

import com.venbiasa.wailo.protocol.BodyRequest
import com.venbiasa.wailo.protocol.BodyResponse
import com.venbiasa.wailo.protocol.BreakpointAction
import com.venbiasa.wailo.protocol.BreakpointDecision
import com.venbiasa.wailo.protocol.BreakpointHit
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.BreakpointRule
import com.venbiasa.wailo.protocol.BreakpointRules
import com.venbiasa.wailo.protocol.CaptureFilter
import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.engine.pairing.Admission
import com.venbiasa.wailo.engine.pairing.DeviceAdmission
import com.venbiasa.wailo.engine.pairing.InMemoryPairingKeyStore
import com.venbiasa.wailo.engine.pairing.PairingManager
import com.venbiasa.wailo.engine.pairing.RefusedDevice
import com.venbiasa.wailo.protocol.MapLocalRule
import com.venbiasa.wailo.protocol.RuleSet
import com.venbiasa.wailo.protocol.RevokeDeviceAck
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.time.Duration.Companion.seconds

/**
 * Which capture path produced a row (ADR-0070). Deliberately not on the protobuf: a proxied exchange is
 * built here and never crosses the device wire, so putting the field there would push a change through
 * the consumer-pinned SDK build to describe something no device can send.
 */
enum class CaptureSource {
    SDK,
    PROXY,
}

/**
 * One captured exchange plus the identity of the session that produced it.
 *
 * [exchange] carries the metadata only: its request/response `body` fields are empty by construction,
 * and the bytes live in the engine's [BodyStore] behind [requestBody]/[responseBody]. Everything that
 * describes a body without being one — `body_size`, `body_truncated`, `Content-Type` — is still on the
 * protobuf, so a list row costs nothing to render. A null handle means the body was empty.
 */
data class CapturedExchange(
    val deviceName: String,
    val appId: String,
    val platform: String,
    val exchange: HttpExchange,
    val requestBody: BodyRef? = null,
    val responseBody: BodyRef? = null,
    val source: CaptureSource = CaptureSource.SDK,
) {
    internal val bodyRefs: List<BodyRef> get() = listOfNotNull(requestBody, responseBody)
}

/**
 * A request/response a device has paused at a breakpoint and is holding open until the desktop decides
 * (resume — optionally edited — or abort). [phase] says whether the device paused before sending the
 * request or before delivering the response; [request] is always present (the request in flight, or the
 * one that produced [response]) and [response] is set only for the RESPONSE phase. [correlationId] pairs
 * this with the [BreakpointDecision] the engine sends back to the exact session that raised it.
 */
data class PausedExchange(
    val correlationId: String,
    val deviceName: String,
    val appId: String,
    val platform: String,
    val phase: BreakpointPhase,
    val request: HttpRequest?,
    val response: HttpResponse?,
)

/**
 * Where a hold's decision has to be delivered. A device holds its own request open and is told over the
 * wire; the bundled proxy is holding a socket inside this process and is told by resuming the thread
 * that parked on it (ADR-0067 — the daemon decides, whichever path raised the hold).
 */
private sealed interface HoldRoute {
    class Device(val session: DeviceConnection) : HoldRoute

    class Local(val deliver: (BreakpointDecision) -> Unit) : HoldRoute
}

/** A synthesized Map Local response the desktop serves for a matched request (ADR-0019). */
class ServedBody(
    val code: Int,
    val headers: List<Header>,
    val body: ByteArray,
)

/**
 * Resolves a matched Map Local rule to the bytes to serve. The seam that keeps the engine headless: it
 * knows nothing about files or paths — the desktop supplies an implementation that reads the file for a
 * given rule id. Returns null when the rule is gone/disabled or the file can't be read, which the device
 * treats as "fall open to the real network".
 */
fun interface MapLocalBodyProvider {
    suspend fun serve(ruleId: String, url: String, method: String): ServedBody?
}

/**
 * Headless capture engine: accepts LAN WebSockets plus transport-neutral connections supplied through
 * [attach], decodes the protobuf stream, and publishes exchanges as a [StateFlow] for any frontend
 * (desktop UI now; CLI/MCP later). It also owns
 * the reverse direction — Map Local (ADR-0019): it pushes the match-metadata [RuleSet] to every device
 * (versioned by [RuleSet.epoch], re-pushed until the device acks so a lost push self-repairs), and
 * answers a device's [BodyRequest] by reading the body through [bodyProvider]. It likewise pushes
 * [BreakpointRules] and, when a device pauses a matching request/response, surfaces it via
 * [pausedExchanges] and releases it through [resumeBreakpoint]/[abortBreakpoint] (ADR-0027). UI-agnostic
 * by design: no Compose here, and no filesystem access beyond the [bodyProvider] and [BodyStore] seams.
 */
class WailoEngine(
    port: Int = DEFAULT_PORT,
    maxRetained: Int = DEFAULT_MAX_RETAINED,
    // How often to re-push to a device that hasn't acked the current epoch. Injectable so tests can
    // exercise the anti-entropy retry without waiting the production interval.
    private val ackRetryMs: Long = DEFAULT_ACK_RETRY_MS,
    // How often [watchHost] re-checks the LAN address and the bound socket. Injectable for the same
    // reason as [ackRetryMs].
    private val hostWatchIntervalMs: Long = DEFAULT_HOST_WATCH_INTERVAL_MS,
    /**
     * Who this Studio is and which devices it trusts (ADR-0039). Defaults to an in-memory store so a
     * test gets a throwaway identity; the desktop passes one backed by the macOS Keychain, because a
     * signing key that does not survive a restart would re-pair every device every launch.
     */
    val pairings: PairingManager = PairingManager(InMemoryPairingKeyStore()),
    requirePairing: Boolean = false,
    /**
     * Where captured bodies go once decoded. Defaults to the heap so a bare engine behaves as it always
     * did; the daemon passes the encrypted on-disk spool, which is what makes a capture bounded by disk
     * rather than by RAM. The engine never closes it — it belongs to whoever built it.
     */
    private val bodyStore: BodyStore = InMemoryBodyStore(),
) {
    private val _requirePairing = MutableStateFlow(requirePairing)

    /**
     * Whether a WiFi device must have paired through a QR or a typed code before it is let in
     * (ADR-0040). Off by default: the common case is one developer and one Mac, where the address was
     * typed by the person who owns both, and a ceremony there buys nothing. On, it refuses anything it
     * has not been introduced to — for a shared or untrusted network.
     *
     * Devices already trusted stay trusted when this goes on. It gates the first contact, which is the
     * only moment it could have made a difference; dropping known devices would just be a surprise.
     */
    val requirePairing: StateFlow<Boolean> = _requirePairing.asStateFlow()

    fun setRequirePairing(enabled: Boolean) {
        _requirePairing.value = enabled
    }

    private val admission = DeviceAdmission(pairings) { _requirePairing.value }

    private val _refusedDevices = MutableStateFlow<List<RefusedDevice>>(emptyList())

    /**
     * Devices that dialled in and were turned away. Surfaced rather than logged: a device the user
     * expects to see connected but does not is otherwise indistinguishable from a network problem.
     */
    val refusedDevices: StateFlow<List<RefusedDevice>> = _refusedDevices.asStateFlow()

    private val _suspectedClones = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Devices whose session counter went backwards, meaning the same key authenticated from somewhere
     * else. Nothing here can stop it — both ends hold the same secret — but a silent compromise is
     * worse than a visible one.
     */
    val suspectedClones: StateFlow<Set<String>> = _suspectedClones.asStateFlow()

    /** Clear the refusal notice once the user has read it or paired the device. */
    fun dismissRefusal(deviceId: String) {
        _refusedDevices.update { list -> list.filterNot { it.deviceId == deviceId } }
    }

    fun dismissCloneWarning(deviceId: String) {
        _suspectedClones.update { it - deviceId }
    }

    /**
     * Stop trusting a device and hang up on it if it is connected right now. Revoking the key alone
     * would only take effect on its next reconnect, which is not what "forget" means when the user is
     * looking at a device that is currently streaming their traffic.
     */
    fun forgetDevice(deviceId: String) {
        pairings.forget(deviceId)
        dismissRefusal(deviceId)
        dismissCloneWarning(deviceId)
        disconnectAuthenticated { it == deviceId }
    }

    fun forgetAllDevices() {
        pairings.forgetAll()
        disconnectAuthenticated { true }
    }

    /**
     * Rotate this Studio's identity: forget every device, generate a new keypair, and re-advertise
     * under the new fingerprint so devices that pinned the old one stop finding a Studio that looks
     * like the one they know.
     */
    fun resetIdentity() {
        pairings.resetIdentity()
        _refusedDevices.value = emptyList()
        _suspectedClones.value = emptySet()
        disconnectAuthenticated { true }
        readvertise()
    }

    // Only sessions that had to prove a key are dropped. Loopback and USB never paired, so revocation
    // has nothing to say about them and cutting them would just interrupt a working capture.
    private fun disconnectAuthenticated(matching: (String) -> Boolean) {
        sessions.entries
            .filter { (_, state) -> state.pairedDeviceId?.let(matching) == true }
            .forEach { (connection, _) -> connection.close() }
    }

    /**
     * The port the capture server is listening on. Movable at runtime via [rebind] rather than fixed at
     * construction, because everything worth keeping — the captured exchanges, the rule/filter/breakpoint
     * snapshots, their epochs — hangs off this instance, and rebuilding the engine to change a port would
     * throw all of it away. Read from the registration coroutine, so volatile.
     */
    @Volatile
    var port: Int = port
        private set

    private val _exchanges = MutableStateFlow<List<CapturedExchange>>(emptyList())
    val exchanges: StateFlow<List<CapturedExchange>> = _exchanges.asStateFlow()

    private val _maxRetained = MutableStateFlow(maxRetained.coerceIn(RETAINED_RANGE))

    /**
     * How many exchanges [exchanges] holds before the oldest fall off. Some cap has to exist — a capture
     * has no natural end — but where it belongs is a judgement only the person watching the traffic can
     * make, so it moves at runtime via [setMaxRetained]. It counts exchanges rather than bytes because
     * the bytes are in the [BodyStore], which bounds itself against the volume it is spooling to.
     */
    val maxRetained: StateFlow<Int> = _maxRetained.asStateFlow()

    private val _capturing = MutableStateFlow(true)

    /** Whether new exchanges are being recorded. Paused keeps the server up but drops incoming traffic. */
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    private val _rules = MutableStateFlow(RuleSet())

    /** The active Map Local rules (match-metadata only) currently pushed to devices. Frontends observe this. */
    val rules: StateFlow<RuleSet> = _rules.asStateFlow()

    private val _captureFilter = MutableStateFlow(CaptureFilter())

    /**
     * The capture filter (allow/block host lists) currently pushed to devices. Devices decide per request
     * whether to capture and stream the whole exchange (ADR-0029); the engine only relays the config and
     * records whatever it receives, so it never re-filters here. Frontends observe this. Both lists off
     * (the default) means capture everything.
     */
    val captureFilter: StateFlow<CaptureFilter> = _captureFilter.asStateFlow()

    private val _breakpointRules = MutableStateFlow(BreakpointRules())

    /**
     * The active breakpoint rules (match-metadata + which phase to break on) pushed to devices, like
     * [rules]. Frontends observe this. On a match a device holds the in-flight request/response and
     * raises a [BreakpointHit]; unlike Map Local there is no body cache — the "authority" is a human.
     */
    val breakpointRules: StateFlow<BreakpointRules> = _breakpointRules.asStateFlow()

    private val _pausedExchanges = MutableStateFlow<List<PausedExchange>>(emptyList())

    /**
     * Exchanges currently paused at a breakpoint, awaiting a desktop decision. A frontend observes this,
     * shows an editor, and calls [resumeBreakpoint]/[abortBreakpoint] to release each one. Entries for a
     * device are dropped when it disconnects (it fails open on its side), so this never wedges.
     */
    val pausedExchanges: StateFlow<List<PausedExchange>> = _pausedExchanges.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<ConnectedDevice>>(emptyList())

    /** SDK sessions that completed their Hello, regardless of whether they arrived over LAN or USB. */
    val connectedDevices: StateFlow<List<ConnectedDevice>> = _connectedDevices.asStateFlow()

    /** Set by the frontend (the desktop) to resolve a matched rule's body on demand. */
    @Volatile
    var bodyProvider: MapLocalBodyProvider? = null

    // Monotonic version stamped on every snapshot; the device echoes it in a RuleAck. Only used for
    // ack-matching/retry, never to gate the device's apply, so a restart resetting it is harmless.
    private val epochCounter = AtomicLong(0)

    // Separate monotonic version for the capture filter, acked independently of the rule epoch.
    private val captureFilterEpochCounter = AtomicLong(0)

    // Separate monotonic version for the breakpoint rules, acked independently of the others.
    private val breakpointEpochCounter = AtomicLong(0)

    // Live device connections + how far each is acked, so a rule change reaches every attached SDK and a
    // silently lost push is re-sent. The transport-neutral key lets the same protocol loop serve inbound
    // LAN sockets and outbound USB sockets.
    private val sessions = ConcurrentHashMap<DeviceConnection, SessionState>()

    // Where each paused exchange's decision has to go, keyed by its correlation id. Entries are removed
    // when the decision is sent or when the owning session disconnects.
    private val pausedRoutes = ConcurrentHashMap<String, HoldRoute>()

    // Rule pushes run off the caller's thread; serialized so frames to a session never interleave.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sendMutex = Mutex()

    // Every edit to [_exchanges] that also frees bodies takes this, so exactly one caller decides which
    // rows were dropped and is therefore the only one allowed to release their bytes.
    private val retentionLock = Any()

    // Written by listen()/stop(), read by the Bonjour registration coroutine and by [listening].
    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    private val _listening = MutableStateFlow(false)

    /**
     * Whether the capture server is bound and accepting devices. False after a failed [start] or [rebind]
     * — the distinction a caller needs to tell "your new port was refused, the old one is still serving"
     * from "nothing is listening at all", which a boolean return from those calls can't express.
     *
     * A flow rather than a getter because the answer changes with nobody asking: [watchHost] clears it when
     * the socket goes out from under a server this still holds, which is what a host that slept through a
     * network change looks like. Sampled once, it would keep promising an endpoint nothing can reach.
     */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _lanAddress = MutableStateFlow(resolveLanAddress())

    /**
     * The address a device on the same network dials, re-resolved on every [watchHost] tick. Wake a laptop
     * on a different network and the one resolved at launch is simply wrong — in the top bar, and baked
     * into any pairing QR rendered from it — with nothing about it that reads as stale.
     */
    val lanAddress: StateFlow<String> = _lanAddress.asStateFlow()

    // Bumped by every bind and every teardown. Each bind launches one Bonjour registration, which blocks
    // ~1s inside JmDNS.create; comparing generations on the way out is how a registration that straddled a
    // [rebind] knows it is stale. Checking `server != null` instead cannot tell — a rebind puts a *new*
    // server there while the old registration is still in flight, so the stale one would adopt it and
    // strand an advertiser for a dead port that nothing tracks or closes.
    private val bindGeneration = AtomicLong(0)

    // Guards the check-then-assign of a freshly created JmDNS against a concurrent stop(). Without it the
    // window between "my generation is still current" and the assignment is enough to strand an advertiser.
    // Every read and write of [jmdns] goes through it, which is also what makes the field visible across
    // the registration coroutine and the caller's thread.
    private val bonjourLock = Any()
    private var jmdns: JmDNS? = null

    // Serializes every bind and teardown. Without it [watchHost]'s recovery and a user's [rebind] can
    // interleave and disagree about which server the field above holds — one of them tearing down the
    // other's freshly bound socket. Held across stop-then-listen pairs, never across a suspension.
    private val bindLock = Any()

    // The health/address watcher, armed by [start] and disarmed by [stop] so a discarded engine leaves
    // nothing ticking. Guarded by [bindLock].
    private var watcher: Job? = null

    /** Per-connection sync state: the highest snapshot epoch this device has acknowledged applying. */
    private class SessionState(
        /** Null for a connection admitted on trust (loopback/USB) rather than by proving a key. */
        val pairedDeviceId: String?,
    ) {
        val ackedEpoch = AtomicLong(-1)
        val ackedFilterEpoch = AtomicLong(-1)
        val ackedBreakpointEpoch = AtomicLong(-1)
    }

    private class KtorServerConnection(
        private val session: DefaultWebSocketServerSession,
        override val isTrusted: Boolean,
    ) : DeviceConnection {
        override val id: String = "lan:${UUID.randomUUID()}"
        override val transport: DeviceTransport = DeviceTransport.LAN

        override suspend fun receive(): ByteArray? {
            while (true) {
                val frame = session.incoming.receiveCatching().getOrNull() ?: return null
                if (frame is Frame.Binary) return frame.readBytes()
            }
        }

        override suspend fun send(bytes: ByteArray) {
            session.outgoing.send(Frame.Binary(true, bytes))
        }

        override fun close() {
            session.cancel()
        }
    }

    /**
     * Bind the capture server on [port] and start accepting devices. Idempotent.
     *
     * Returns false when the port can't be bound, leaving the engine stopped rather than throwing: a port
     * that was free when it was chosen may be taken by the time it's used again, and that must be something
     * the caller can report, not something that takes the app down at launch.
     */
    fun start(): Boolean {
        val bound = listen()
        // Armed here rather than in an initializer so a bare `WailoEngine(...)` — what every unit test
        // builds — never spins a background probe the test has to know to shut down. It runs even when the
        // bind above failed: the address it tracks is displayed either way.
        synchronized(bindLock) {
            if (watcher?.isActive != true) watcher = scope.launch(Dispatchers.IO) { watchHost() }
        }
        return bound
    }

    /**
     * Bind the capture server again on the current port, whether or not it is currently up. This is the
     * recovery path for the one state the engine can't fix by itself — the port was taken when [start] ran,
     * and by the time anyone notices, whatever took it may be long gone.
     *
     * Suspending for the same reason as [rebind]: the teardown blocks for up to [STOP_TIMEOUT_MS].
     */
    suspend fun restart(): Boolean = withContext(Dispatchers.IO) {
        refreshLanAddress()
        synchronized(bindLock) {
            stopServer(closeAttachedConnections = false)
            listen()
        }
    }

    /**
     * Serve a device connection opened by another reachability layer, currently macOS usbmuxd. The
     * caller owns reconnects; this suspends until the connection closes and applies the same protocol
     * semantics and cleanup as an inbound LAN WebSocket.
     */
    suspend fun attach(connection: DeviceConnection) {
        handleConnection(connection)
    }

    /**
     * Move the capture server to [newPort], keeping everything this instance holds — captured exchanges,
     * rules, filter, breakpoints, epochs — which is the whole reason this exists instead of rebuilding the
     * engine. LAN devices are dropped and have to redial; Bonjour re-advertises on the new port, so
     * devices that discover rather than pin will find it themselves. Connections supplied through
     * [attach] use their own reachability layer and stay live.
     *
     * Returns false and stays on the current port when [newPort] is outside [PORT_RANGE] or can't be bound.
     * Suspending because tearing the old server down blocks for up to [STOP_TIMEOUT_MS], which must not
     * happen on a UI thread.
     */
    suspend fun rebind(newPort: Int): Boolean {
        if (newPort !in PORT_RANGE) return false
        if (newPort == port && server != null) return true
        return withContext(Dispatchers.IO) {
            synchronized(bindLock) {
                val previous = port
                stopServer(closeAttachedConnections = false)
                port = newPort
                if (listen()) return@synchronized true
                // Never leave the app with nothing listening — go back to the port that was working.
                port = previous
                listen()
                false
            }
        }
    }

    // The one bind path, behind both start() and rebind(). Ktor CIO binds on a background coroutine, so a
    // failed bind doesn't reliably surface out of start(wait = false) — it would leave `server` set with
    // nothing actually listening. Trial-binding first turns "that port is taken" into an answer a caller
    // can show the user; runCatching covers the rest, including the narrow race between probe and bind.
    private fun listen(): Boolean = synchronized(bindLock) {
        if (server != null) return@synchronized true
        if (!isBindable(port)) return@synchronized false
        val bound = runCatching { buildServer().also { it.start(wait = false) } }.getOrNull()
            ?: return@synchronized false
        server = bound
        _listening.value = true
        val generation = bindGeneration.incrementAndGet()
        scope.launch(Dispatchers.IO) { registerBonjourService(generation) }
        true
    }

    // Trial-bind with the same address reuse a server sets, so a port our own previous bind left in
    // TIME_WAIT still reads as free, while one another process is actively listening on reads as taken.
    private fun isBindable(port: Int): Boolean = runCatching {
        ServerSocket().use { probe ->
            probe.reuseAddress = true
            probe.bind(InetSocketAddress(port))
        }
    }.isSuccess

    private fun buildServer(): EmbeddedServer<*, *> =
        embeddedServer(CIO, port = port) {
            install(WebSockets) {
                pingPeriod = 15.seconds
                timeout = 15.seconds
            }
            routing {
                webSocket("/") {
                    handleConnection(
                        KtorServerConnection(this, isTrusted = isLoopbackPeer(call.request.local.remoteAddress)),
                    )
                }
            }
        }

    /**
     * A peer on loopback reached this process through the kernel, not the network, so it is this
     * machine by construction: the Simulator, `adb reverse`, or the usbmux tunnel. Everything else came
     * over WiFi and has to pair (ADR-0039).
     */
    private fun isLoopbackPeer(remoteAddress: String): Boolean =
        runCatching { InetAddress.getByName(remoteAddress.substringAfterLast('%')).isLoopbackAddress }
            .getOrDefault(false)

    private suspend fun handleConnection(unadmitted: DeviceConnection) = coroutineScope {
        // Nothing below this line may run for a peer that has not proved itself: the three pushes that
        // follow describe every host being intercepted and every Map Local path, and they used to go
        // out the instant a socket opened (ADR-0039).
        val admitted = when (val admission = admission.admit(unadmitted)) {
            is Admission.Sealed -> {
                if (admission.suspectedClone) _suspectedClones.update { it + admission.deviceId }
                admission
            }

            is Admission.Refused -> {
                _refusedDevices.update { list -> list.filterNot { it.deviceId == admission.refusal.deviceId } + admission.refusal }
                unadmitted.close()
                return@coroutineScope
            }

            Admission.Rejected -> {
                unadmitted.close()
                return@coroutineScope
            }
        }
        val connection = admitted.connection

        val state = SessionState(pairedDeviceId = admitted.deviceId.takeUnless { unadmitted.isTrusted })
        sessions[connection] = state
        // Re-push to a lagging device until it acks the current epoch, so a silently dropped snapshot
        // self-repairs without waiting for the next edit or reconnect (ADR-0019).
        val reconciler = launch { reconcile(connection, state) }
        try {
            pushRules(connection)
            pushCaptureFilter(connection)
            pushBreakpointRules(connection)
            var hello: Hello? = null
            while (true) {
                val bytes = connection.receive() ?: break
                val envelope = Envelope.ADAPTER.decode(bytes)
                envelope.hello?.let {
                    hello = it
                    // Read off the pre-admission connection: for a LAN peer `isTrusted` is exactly
                    // "arrived on loopback", which is the one thing the sealed wrapper no longer says.
                    identify(connection, it, loopback = unadmitted.isTrusted && unadmitted.transport == DeviceTransport.LAN)
                    // The paired list is keyed by an opaque device id; Hello is the first and only
                    // place a human-readable name for it appears.
                    pairings.known(admitted.deviceId)?.let { paired ->
                        pairings.remember(
                            deviceId = paired.deviceId,
                            key = paired.key,
                            name = it.device_name,
                            sessionCounter = paired.sessionCounter,
                            trustedOnFirstUse = paired.trustedOnFirstUse,
                        )
                    }
                }
                envelope.exchange?.let { record(hello, it) }
                envelope.rule_ack?.let { ack ->
                    state.ackedEpoch.updateAndGet { cur -> maxOf(cur, ack.epoch) }
                }
                envelope.capture_filter_ack?.let { ack ->
                    state.ackedFilterEpoch.updateAndGet { cur -> maxOf(cur, ack.epoch) }
                }
                envelope.breakpoint_rules_ack?.let { ack ->
                    state.ackedBreakpointEpoch.updateAndGet { cur -> maxOf(cur, ack.epoch) }
                }
                val revoke = envelope.revoke_device
                if (revoke != null && state.pairedDeviceId != null) {
                    // Ack under the still-live session key, then remove the alias. The local device
                    // forget does not depend on receiving this best-effort cleanup (ADR-0060).
                    connection.send(
                        Envelope(
                            revoke_device_ack = RevokeDeviceAck(request_id = revoke.request_id),
                        ).encode(),
                    )
                    pairings.forget(state.pairedDeviceId)
                    dismissRefusal(state.pairedDeviceId)
                    dismissCloneWarning(state.pairedDeviceId)
                    break
                }
                // Keep a slow body read off this connection's receive loop.
                envelope.body_request?.let { request -> launch { serveBody(connection, request) } }
                envelope.breakpoint_hit?.let { hit -> recordBreakpointHit(hello, hit, connection) }
            }
        } finally {
            reconciler.cancel()
            sessions.remove(connection)
            _connectedDevices.update { list -> list.filterNot { it.connectionId == connection.id } }
            releasePausedFor(connection)
            connection.close()
        }
    }

    /**
     * Release everything this engine holds: the bound port, the Bonjour record, the watcher, and every
     * device session. Call it when the engine is being discarded, not just at exit — a replaced instance
     * that is never stopped keeps its socket, and the port it strands is the one its replacement is about
     * to ask for. [start] can arm the same engine again afterwards.
     */
    fun stop() {
        synchronized(bindLock) {
            watcher?.cancel()
            watcher = null
        }
        stopServer(closeAttachedConnections = true)
    }

    private fun stopServer(closeAttachedConnections: Boolean) = synchronized(bindLock) {
        // Invalidate any in-flight registration before anything else, so one that is still inside
        // JmDNS.create abandons its instance instead of publishing a port that is about to disappear.
        bindGeneration.incrementAndGet()
        if (closeAttachedConnections) sessions.keys.forEach(DeviceConnection::close)
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        server = null
        _listening.value = false
        val advertiser = synchronized(bonjourLock) { jmdns.also { jmdns = null } }
        runCatching {
            advertiser?.unregisterAllServices()
            advertiser?.close()
        }
    }

    /**
     * Keeps the two things a sleeping host quietly invalidates honest: the address devices are told to
     * dial, and whether the socket behind [listening] is still there.
     *
     * The liveness test is the trial bind [isBindable] already does, read the other way round — a live
     * listener makes its own port unbindable, so a bind that *succeeds* while a server is still held means
     * the socket underneath it is gone. That costs two syscalls and, unlike dialling the port, can't be
     * confused by a full accept backlog. Ktor binds asynchronously though, so a bind that has not landed
     * yet reads exactly like one that died; requiring [LOSSES_BEFORE_REBIND] consecutive misses puts that
     * window (milliseconds) far out of reach of the interval.
     */
    private suspend fun watchHost() {
        var losses = 0
        while (true) {
            delay(hostWatchIntervalMs)
            if (refreshLanAddress()) readvertise()
            if (server == null || !isBindable(port)) {
                losses = 0
                continue
            }
            losses += 1
            if (losses < LOSSES_BEFORE_REBIND) continue
            losses = 0
            // Drop the dead server before rebinding so its Bonjour record goes with it. A rebind that
            // can't take the port back leaves [listening] false, which is the caller's cue to offer a retry.
            synchronized(bindLock) {
                stopServer(closeAttachedConnections = false)
                listen()
            }
        }
    }

    // True when the address moved, which is the only time it is worth republishing Bonjour: JmDNS is
    // pinned to the interface it was created on, so an address change leaves it advertising a dead one.
    private fun refreshLanAddress(): Boolean {
        val current = resolveLanAddress()
        if (current == _lanAddress.value) return false
        _lanAddress.value = current
        return true
    }

    // Republish the Bonjour record. The TXT carries the studio fingerprint, so a rotated identity has to
    // go back out or devices keep browsing for a Studio that no longer exists. Takes the same generation
    // path as a bind, which is what makes a registration still inside JmDNS.create abandon its instance.
    private fun readvertise() {
        if (server == null) return
        val stale = synchronized(bonjourLock) { jmdns.also { jmdns = null } }
        val generation = bindGeneration.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            runCatching {
                stale?.unregisterAllServices()
                stale?.close()
            }
            registerBonjourService(generation)
        }
    }

    /** Pause/resume recording. Devices stay connected either way; paused just drops incoming traffic. */
    fun setCapturing(enabled: Boolean) {
        _capturing.value = enabled
    }

    /** Drop all captured exchanges and their spooled bodies. Recording state is unchanged. */
    fun clear() {
        val dropped = synchronized(retentionLock) {
            _exchanges.value.also { _exchanges.value = emptyList() }
        }
        releaseBodies(dropped)
    }

    /**
     * Move the retention cap, trimming what is already held to fit. Lowering it discards the oldest
     * exchanges now rather than at the next request, because the reason to lower it is memory that is
     * already spent. Clamped to [RETAINED_RANGE], so a caller can't leave the engine keeping nothing.
     */
    fun setMaxRetained(max: Int) {
        val capped = max.coerceIn(RETAINED_RANGE)
        _maxRetained.value = capped
        val evicted = synchronized(retentionLock) {
            val current = _exchanges.value
            val overflow = (current.size - capped).coerceAtLeast(0)
            if (overflow > 0) _exchanges.value = current.subList(overflow, current.size)
            current.subList(0, overflow)
        }
        releaseBodies(evicted)
    }

    /**
     * Replace the active Map Local rules (match-metadata only) and push the new snapshot to every device.
     * Stamps a fresh [RuleSet.epoch] so devices can ack it and the reconciler can detect a lost push. The
     * bodies are resolved later, per match, via [bodyProvider] — they are never in the snapshot (ADR-0019).
     */
    fun updateRules(rules: List<MapLocalRule>) {
        _rules.value = RuleSet(rules = rules, epoch = epochCounter.incrementAndGet())
        scope.launch { sessions.keys.forEach { pushRules(it) } }
    }

    // Reads _rules under the lock at send time, so a connect-push racing a broadcast still converges
    // on the newest snapshot regardless of which wins the race.
    private suspend fun pushRules(session: DeviceConnection) {
        sendMutex.withLock {
            val bytes = Envelope(rule_set = _rules.value).encode()
            runCatching { session.send(bytes) }
        }
    }

    /**
     * Replace the capture filter (allow/block host lists + their enabled flags) and push the new snapshot
     * to every device. Stamps a fresh [CaptureFilter.epoch] so devices can ack it and the reconciler can
     * detect a lost push. Devices apply the gate per request and drop whole exchanges that don't pass
     * (ADR-0029); the engine keeps recording whatever it still receives.
     */
    fun updateCaptureFilter(
        allowlistEnabled: Boolean,
        allowPatterns: List<String>,
        blocklistEnabled: Boolean,
        blockPatterns: List<String>,
    ) {
        _captureFilter.value = CaptureFilter(
            allowlist_enabled = allowlistEnabled,
            allow_patterns = allowPatterns,
            blocklist_enabled = blocklistEnabled,
            block_patterns = blockPatterns,
            epoch = captureFilterEpochCounter.incrementAndGet(),
        )
        scope.launch { sessions.keys.forEach { pushCaptureFilter(it) } }
    }

    // Mirrors pushRules: reads _captureFilter under the lock at send time so a connect-push racing a
    // broadcast still converges on the newest snapshot.
    private suspend fun pushCaptureFilter(session: DeviceConnection) {
        sendMutex.withLock {
            val bytes = Envelope(capture_filter = _captureFilter.value).encode()
            runCatching { session.send(bytes) }
        }
    }

    /**
     * Replace the active breakpoint rules and push the new snapshot to every device. Stamps a fresh
     * [BreakpointRules.epoch] so devices can ack it and the reconciler can detect a lost push, mirroring
     * [updateRules]. A device pauses only requests matching an enabled rule at the requested phase.
     */
    fun updateBreakpointRules(rules: List<BreakpointRule>) {
        _breakpointRules.value = BreakpointRules(rules = rules, epoch = breakpointEpochCounter.incrementAndGet())
        scope.launch { sessions.keys.forEach { pushBreakpointRules(it) } }
    }

    // Mirrors pushRules/pushCaptureFilter: reads _breakpointRules under the lock at send time so a
    // connect-push racing a broadcast still converges on the newest snapshot.
    private suspend fun pushBreakpointRules(session: DeviceConnection) {
        sendMutex.withLock {
            val bytes = Envelope(breakpoint_rules = _breakpointRules.value).encode()
            runCatching { session.send(bytes) }
        }
    }

    // Re-push the current snapshot to a device that hasn't acked it yet. Cancelled when the connection
    // ends (the launching coroutine is a child of the session handler).
    private suspend fun reconcile(session: DeviceConnection, state: SessionState) {
        while (true) {
            delay(ackRetryMs)
            if (state.ackedEpoch.get() < _rules.value.epoch) pushRules(session)
            if (state.ackedFilterEpoch.get() < _captureFilter.value.epoch) pushCaptureFilter(session)
            if (state.ackedBreakpointEpoch.get() < _breakpointRules.value.epoch) pushBreakpointRules(session)
        }
    }

    // Answer a matched request by reading the body through the provider, or found=false so the device
    // falls open to the real network. The provider owns any IO dispatch.
    private suspend fun serveBody(session: DeviceConnection, request: BodyRequest) {
        val served = runCatching {
            bodyProvider?.serve(request.rule_id, request.url, request.method)
        }.getOrNull()
        val response = if (served != null) {
            BodyResponse(
                correlation_id = request.correlation_id,
                found = true,
                code = served.code,
                headers = served.headers,
                body = served.body.toByteString(),
            )
        } else {
            BodyResponse(correlation_id = request.correlation_id, found = false)
        }
        sendMutex.withLock {
            runCatching { session.send(Envelope(body_response = response).encode()) }
        }
    }

    // Record a device's paused request/response and remember which session raised it, so a later
    // resume/abort can route the decision straight back to that connection.
    private fun recordBreakpointHit(hello: Hello?, hit: BreakpointHit, session: DeviceConnection) {
        pausedRoutes[hit.correlation_id] = HoldRoute.Device(session)
        val paused = PausedExchange(
            correlationId = hit.correlation_id,
            deviceName = hello?.device_name ?: "unknown",
            appId = hello?.app_id ?: "unknown",
            platform = hello?.platform ?: "unknown",
            phase = hit.phase,
            request = hit.request,
            response = hit.response,
        )
        _pausedExchanges.update { it + paused }
    }

    /**
     * Release a paused exchange, letting the device proceed — with [editedRequest] on a request-phase
     * hit or [editedResponse] on a response-phase hit (null = proceed with the device's original). A
     * Returns false if the correlation id is unknown (already released by a disconnect), so a headless
     * caller does not report a successful decision for a hold that no longer exists.
     */
    fun resumeBreakpoint(
        correlationId: String,
        editedRequest: HttpRequest? = null,
        editedResponse: HttpResponse? = null,
    ): Boolean =
        sendDecision(
            BreakpointDecision(
                correlation_id = correlationId,
                action = BreakpointAction.BREAKPOINT_ACTION_PROCEED,
                edited_request = editedRequest,
                edited_response = editedResponse,
            ),
        )

    /** Abort a paused exchange. Returns false when the id is unknown. */
    fun abortBreakpoint(correlationId: String): Boolean =
        sendDecision(
            BreakpointDecision(
                correlation_id = correlationId,
                action = BreakpointAction.BREAKPOINT_ACTION_ABORT,
            ),
        )

    // Send a decision to whatever is holding this exchange and drop it from the paused set. Removing
    // eagerly (before the send completes) is safe: if the socket is already gone the device has failed
    // open on its side, so the decision is moot.
    private fun sendDecision(decision: BreakpointDecision): Boolean {
        val route = pausedRoutes.remove(decision.correlation_id) ?: return false
        _pausedExchanges.update { list -> list.filterNot { it.correlationId == decision.correlation_id } }
        when (route) {
            // Delivered inline: the proxy thread is parked on this, and a relay that resumes one tick
            // later than it could is a stall the user reads as a hung request.
            is HoldRoute.Local -> runCatching { route.deliver(decision) }
            is HoldRoute.Device -> scope.launch {
                sendMutex.withLock {
                    runCatching { route.session.send(Envelope(breakpoint_decision = decision).encode()) }
                }
            }
        }
        return true
    }

    /**
     * Park an exchange this engine is holding itself — today, one the bundled proxy is relaying — and be
     * told through [deliver] when a frontend, a seed, or the CLI decides. Unlike a device hold there is
     * no fail-open: nothing else can complete this request, so the caller must resolve every hold it
     * opens, including on shutdown.
     */
    fun holdExternal(
        correlationId: String,
        client: String,
        phase: BreakpointPhase,
        request: HttpRequest?,
        response: HttpResponse?,
        deliver: (BreakpointDecision) -> Unit,
    ) {
        pausedRoutes[correlationId] = HoldRoute.Local(deliver)
        _pausedExchanges.update {
            it + PausedExchange(
                correlationId = correlationId,
                deviceName = "Proxy",
                appId = client,
                platform = "proxy",
                phase = phase,
                request = request,
                response = response,
            )
        }
    }

    /** Forget a hold whose holder gave up — a proxy client that hung up while waiting for a decision. */
    fun releaseExternalHold(correlationId: String) {
        if (pausedRoutes.remove(correlationId) == null) return
        _pausedExchanges.update { list -> list.filterNot { it.correlationId == correlationId } }
    }

    // Drop every exchange a disconnecting session was holding. The device fails those calls open on its
    // side when the link drops, so the desktop must not keep showing them as pausable.
    private fun releasePausedFor(session: DeviceConnection) {
        val orphaned = pausedRoutes.entries
            .filter { (it.value as? HoldRoute.Device)?.session == session }
            .map { it.key }
        if (orphaned.isEmpty()) return
        orphaned.forEach { pausedRoutes.remove(it) }
        _pausedExchanges.update { list -> list.filterNot { it.correlationId in orphaned } }
    }

    private fun identify(connection: DeviceConnection, hello: Hello, loopback: Boolean) {
        val device = ConnectedDevice(
            connectionId = connection.id,
            deviceName = hello.device_name,
            appId = hello.app_id,
            platform = hello.platform,
            transport = connection.transport,
            loopback = loopback,
        )
        _connectedDevices.update { list -> list.filterNot { it.connectionId == connection.id } + device }
    }

    private fun record(hello: Hello?, exchange: HttpExchange) {
        if (!_capturing.value) return
        // Spool before the row is published, so nothing can observe an exchange whose bytes are not
        // fetchable yet. The decoded payload goes out of scope with this frame, which is the point:
        // the protobuf that arrived is the last thing holding it.
        val request = spool(exchange.request?.body)
        val response = spool(exchange.response?.body)
        val row = CapturedExchange(
            deviceName = hello?.device_name ?: "unknown",
            appId = hello?.app_id ?: "unknown",
            platform = hello?.platform ?: "unknown",
            exchange = exchange.stripped(lostRequest = request.lost, lostResponse = response.lost),
            requestBody = request.ref,
            responseBody = response.ref,
        )
        append(row)
    }

    private class Spooled(val ref: BodyRef?, val lost: Boolean)

    /**
     * Hand one side's bytes to the store. A store that cannot take them — a full volume, most likely —
     * must not take the exchange down with it, and must not leave a row claiming the body was empty
     * either. Dropping the handle while marking that side truncated says what actually happened: this
     * many bytes existed and none of them are here, which is the same thing a device says when it gives
     * up mid-capture.
     */
    private fun spool(body: ByteString?): Spooled {
        if (body == null || body.size == 0) return Spooled(null, lost = false)
        val ref = runCatching { bodyStore.put(body) }.getOrNull()
        return Spooled(ref, lost = ref == null)
    }

    /**
     * Publish [row] and retire whatever it pushed past the retention cap.
     *
     * Serialized rather than done through [MutableStateFlow.update], because the release is not part of
     * the value: `update` re-runs its block on contention, and a block that had already freed the bytes
     * of the exchanges it dropped would free them again — or free the wrong ones — on the retry.
     */
    private fun append(row: CapturedExchange) {
        val evicted = synchronized(retentionLock) {
            val next = _exchanges.value + row
            val overflow = (next.size - _maxRetained.value).coerceAtLeast(0)
            _exchanges.value = if (overflow == 0) next else next.subList(overflow, next.size)
            next.subList(0, overflow)
        }
        releaseBodies(evicted)
    }

    private fun releaseBodies(rows: List<CapturedExchange>) {
        if (rows.isEmpty()) return
        runCatching { bodyStore.release(rows.flatMap { it.bodyRefs }) }
    }

    /**
     * Retire the [count] oldest exchanges and free their bodies. The store calls this when the volume it
     * spools to is running out of room: the alternative is to stop capturing, and a live capture that
     * quietly drops the newest traffic is worse than one that forgets the oldest — which is what the
     * retention cap already does, just for a different reason.
     */
    fun evictOldest(count: Int) {
        if (count <= 0) return
        val evicted = synchronized(retentionLock) {
            val current = _exchanges.value
            val drop = minOf(count, current.size)
            if (drop == 0) return
            _exchanges.value = current.subList(drop, current.size)
            current.subList(0, drop)
        }
        releaseBodies(evicted)
    }

    private fun HttpExchange.stripped(lostRequest: Boolean, lostResponse: Boolean) = copy(
        request = request?.let { it.copy(body = ByteString.EMPTY, body_truncated = it.body_truncated || lostRequest) },
        response = response?.let { it.copy(body = ByteString.EMPTY, body_truncated = it.body_truncated || lostResponse) },
    )

    /**
     * Read back the bytes behind a [CapturedExchange]'s handle. Goes through the engine rather than
     * exposing the store, so a frontend cannot write to it or release someone else's rows.
     */
    fun readBody(ref: BodyRef, offset: Long, length: Int): ByteArray =
        runCatching { bodyStore.read(ref, offset, length) }.getOrDefault(ByteArray(0))

    /** Stream a whole body, for an export or a proxied resume that must not materialize it. */
    fun openBody(ref: BodyRef): InputStream =
        runCatching { bodyStore.open(ref) }.getOrElse { ByteArrayInputStream(ByteArray(0)) }

    /**
     * Open a body sink for a capture path that produces its bytes over time rather than receiving them
     * whole (ADR-0070). The proxy writes into one of these while the same bytes are on their way to the
     * client, so a response larger than memory never becomes a value anywhere.
     */
    fun openBodySink(): BodySink = bodyStore.openSink()

    /**
     * Record an exchange this engine did not receive over the device wire — today, one the bundled proxy
     * relayed. Its bodies are already in the store (see [openBodySink]), so this only publishes the row
     * and applies the same retention every captured exchange gets.
     *
     * Refuses while capture is paused, and drops the bodies rather than leaking them: the caller has
     * already spooled by the time it can be told no.
     */
    fun record(row: CapturedExchange) {
        if (!_capturing.value) {
            releaseBodies(listOf(row))
            return
        }
        append(row)
    }

    // Best-effort: JmDNS init can block ~1s and fails without a usable network interface; never wedge a bind.
    // [generation] is the bind this registration belongs to — see [bindGeneration] for why a stop or a
    // rebind during that blocking init has to abandon the instance rather than publish it.
    private suspend fun registerBonjourService(generation: Long) {
        runCatching {
            if (bindGeneration.get() != generation) return
            val lanAddress = resolveLanAddress()
            if (lanAddress == "localhost") return
            val instance = JmDNS.create(InetAddress.getByName(lanAddress))
            // Publish only if this is still the current bind, and do the check and the handover together
            // so a stop() can't slip between them and lose track of the instance it would have closed.
            val current = synchronized(bonjourLock) {
                (bindGeneration.get() == generation).also { if (it) jmdns = instance }
            }
            if (!current) {
                instance.close()
                return
            }
            // A DNS-SD instance name is a single label, and macOS hands back an FQDN here
            // ("<hostname>.local"). Registering that verbatim publishes an instance whose name
            // carries a dotted `.local` suffix, which Apple's mDNSResponder ignores outright — so iOS,
            // which browses through that same stack, never sees the desktop. JmDNS is lax enough to
            // publish and resolve it either way, so a Kotlin-to-Kotlin check looks perfectly healthy.
            val serviceName = runCatching { InetAddress.getLocalHost().hostName }
                .getOrNull()
                ?.substringBefore('.')
                ?.takeUnless { it.isBlank() }
                ?: BONJOUR_FALLBACK_NAME
            // The TXT record carries this Studio's public-key fingerprint so a device can tell, before
            // opening a socket, whether this is one it has paired with — and skip everyone else's
            // Studio on the same WiFi rather than dialling whichever sorted first (ADR-0039). The value
            // is unauthenticated here, but the handshake binds it: only the matching private key can
            // sign, and the fingerprint is of that key.
            instance.registerService(
                ServiceInfo.create(
                    BONJOUR_SERVICE_TYPE,
                    serviceName,
                    port,
                    0,
                    0,
                    mapOf(BONJOUR_STUDIO_ID_KEY to pairings.studioId),
                ),
            )
        }
    }

    companion object {
        const val DEFAULT_PORT: Int = 8899

        /**
         * Ports [rebind] will accept. 0 is excluded because the OS reads it as "any free port", which would
         * bind fine and then leave the engine listening somewhere nobody was told about.
         */
        val PORT_RANGE: IntRange = 1..65535

        const val DEFAULT_MAX_RETAINED: Int = 10000

        /**
         * What [setMaxRetained] will accept. The floor keeps the list long enough to still be a record of
         * a session rather than of the last few seconds; past the ceiling it is the metadata of that many
         * exchanges, not the cap, that decides what a capture costs.
         */
        val RETAINED_RANGE: IntRange = 100..100000

        private const val STOP_GRACE_MS: Long = 500L
        private const val STOP_TIMEOUT_MS: Long = 1000L

        // How often [watchHost] re-checks the address and the socket. Two syscalls a tick, so the interval
        // is set by how long a stale address or a dead port may go unnoticed after a wake, not by cost.
        private const val DEFAULT_HOST_WATCH_INTERVAL_MS: Long = 5_000L
        private const val LOSSES_BEFORE_REBIND: Int = 2
        private const val DEFAULT_ACK_RETRY_MS: Long = 2000L
        private const val BONJOUR_SERVICE_TYPE = "_wailo._tcp.local."
        private const val BONJOUR_FALLBACK_NAME = "Wailo"
        private const val BONJOUR_STUDIO_ID_KEY = "sid"
    }
}
