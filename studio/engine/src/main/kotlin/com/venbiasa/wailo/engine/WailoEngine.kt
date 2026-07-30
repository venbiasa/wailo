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
import com.venbiasa.wailo.protocol.MapLocalRule
import com.venbiasa.wailo.protocol.RuleSet
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import okio.ByteString.Companion.toByteString
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** One captured exchange plus the identity of the session that produced it. */
data class CapturedExchange(
    val deviceName: String,
    val appId: String,
    val platform: String,
    val exchange: HttpExchange,
)

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
 * by design: no Compose here, and no filesystem access beyond the provider seam.
 */
class WailoEngine(
    port: Int = DEFAULT_PORT,
    private val maxRetained: Int = DEFAULT_MAX_RETAINED,
    // How often to re-push to a device that hasn't acked the current epoch. Injectable so tests can
    // exercise the anti-entropy retry without waiting the production interval.
    private val ackRetryMs: Long = DEFAULT_ACK_RETRY_MS,
) {
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

    // Which session raised each paused exchange, keyed by its correlation id, so a resume/abort routes
    // the decision back to the exact device that is holding that request/response. Entries are removed
    // when the decision is sent or when the owning session disconnects.
    private val pausedSessions = ConcurrentHashMap<String, DeviceConnection>()

    // Rule pushes run off the caller's thread; serialized so frames to a session never interleave.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sendMutex = Mutex()

    // Written by listen()/stop(), read by the Bonjour registration coroutine and by [listening].
    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    /**
     * Whether the capture server is bound and accepting devices. False after a failed [start] or [rebind]
     * — the distinction a caller needs to tell "your new port was refused, the old one is still serving"
     * from "nothing is listening at all", which a boolean return from those calls can't express.
     */
    val listening: Boolean get() = server != null

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

    /** Per-connection sync state: the highest snapshot epoch this device has acknowledged applying. */
    private class SessionState {
        val ackedEpoch = AtomicLong(-1)
        val ackedFilterEpoch = AtomicLong(-1)
        val ackedBreakpointEpoch = AtomicLong(-1)
    }

    private class KtorServerConnection(
        private val session: DefaultWebSocketServerSession,
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

        override fun close() = Unit
    }

    /**
     * Bind the capture server on [port] and start accepting devices. Idempotent.
     *
     * Returns false when the port can't be bound, leaving the engine stopped rather than throwing: a port
     * that was free when it was chosen may be taken by the time it's used again, and that must be something
     * the caller can report, not something that takes the app down at launch.
     */
    fun start(): Boolean = listen()

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
            val previous = port
            stopServer(closeAttachedConnections = false)
            port = newPort
            if (listen()) return@withContext true
            // Never leave the app with nothing listening — go back to the port that was working.
            port = previous
            listen()
            false
        }
    }

    // The one bind path, behind both start() and rebind(). Ktor CIO binds on a background coroutine, so a
    // failed bind doesn't reliably surface out of start(wait = false) — it would leave `server` set with
    // nothing actually listening. Trial-binding first turns "that port is taken" into an answer a caller
    // can show the user; runCatching covers the rest, including the narrow race between probe and bind.
    private fun listen(): Boolean {
        if (server != null) return true
        if (!isBindable(port)) return false
        val bound = runCatching { buildServer().also { it.start(wait = false) } }.getOrNull() ?: return false
        server = bound
        val generation = bindGeneration.incrementAndGet()
        scope.launch(Dispatchers.IO) { registerBonjourService(generation) }
        return true
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
            install(WebSockets)
            routing {
                webSocket("/") {
                    handleConnection(KtorServerConnection(this))
                }
            }
        }

    private suspend fun handleConnection(connection: DeviceConnection) = coroutineScope {
        val state = SessionState()
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
                    identify(connection, it)
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

    fun stop() {
        stopServer(closeAttachedConnections = true)
    }

    private fun stopServer(closeAttachedConnections: Boolean) {
        // Invalidate any in-flight registration before anything else, so one that is still inside
        // JmDNS.create abandons its instance instead of publishing a port that is about to disappear.
        bindGeneration.incrementAndGet()
        if (closeAttachedConnections) sessions.keys.forEach(DeviceConnection::close)
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        server = null
        val advertiser = synchronized(bonjourLock) { jmdns.also { jmdns = null } }
        runCatching {
            advertiser?.unregisterAllServices()
            advertiser?.close()
        }
    }

    /** Pause/resume recording. Devices stay connected either way; paused just drops incoming traffic. */
    fun setCapturing(enabled: Boolean) {
        _capturing.value = enabled
    }

    /** Drop all captured exchanges. Recording state is unchanged. */
    fun clear() {
        _exchanges.value = emptyList()
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
        pausedSessions[hit.correlation_id] = session
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
     * no-op if the correlation id is unknown (already released by a disconnect).
     */
    fun resumeBreakpoint(
        correlationId: String,
        editedRequest: HttpRequest? = null,
        editedResponse: HttpResponse? = null,
    ) {
        sendDecision(
            BreakpointDecision(
                correlation_id = correlationId,
                action = BreakpointAction.BREAKPOINT_ACTION_PROCEED,
                edited_request = editedRequest,
                edited_response = editedResponse,
            ),
        )
    }

    /** Abort a paused exchange: the device fails the app's call. A no-op if the id is unknown. */
    fun abortBreakpoint(correlationId: String) {
        sendDecision(
            BreakpointDecision(
                correlation_id = correlationId,
                action = BreakpointAction.BREAKPOINT_ACTION_ABORT,
            ),
        )
    }

    // Send a decision to the session holding this exchange and drop it from the paused set. Removing
    // eagerly (before the send completes) is safe: if the socket is already gone the device has failed
    // open on its side, so the decision is moot.
    private fun sendDecision(decision: BreakpointDecision) {
        val session = pausedSessions.remove(decision.correlation_id) ?: return
        _pausedExchanges.update { list -> list.filterNot { it.correlationId == decision.correlation_id } }
        scope.launch {
            sendMutex.withLock {
                runCatching { session.send(Envelope(breakpoint_decision = decision).encode()) }
            }
        }
    }

    // Drop every exchange a disconnecting session was holding. The device fails those calls open on its
    // side when the link drops, so the desktop must not keep showing them as pausable.
    private fun releasePausedFor(session: DeviceConnection) {
        val orphaned = pausedSessions.entries.filter { it.value == session }.map { it.key }
        if (orphaned.isEmpty()) return
        orphaned.forEach { pausedSessions.remove(it) }
        _pausedExchanges.update { list -> list.filterNot { it.correlationId in orphaned } }
    }

    private fun identify(connection: DeviceConnection, hello: Hello) {
        val device = ConnectedDevice(
            connectionId = connection.id,
            deviceName = hello.device_name,
            appId = hello.app_id,
            platform = hello.platform,
            transport = connection.transport,
        )
        _connectedDevices.update { list -> list.filterNot { it.connectionId == connection.id } + device }
    }

    private fun record(hello: Hello?, exchange: HttpExchange) {
        if (!_capturing.value) return
        val row = CapturedExchange(
            deviceName = hello?.device_name ?: "unknown",
            appId = hello?.app_id ?: "unknown",
            platform = hello?.platform ?: "unknown",
            exchange = exchange,
        )
        _exchanges.update { (it + row).takeLast(maxRetained) }
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
            instance.registerService(
                ServiceInfo.create(BONJOUR_SERVICE_TYPE, serviceName, port, ""),
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

        private const val DEFAULT_MAX_RETAINED: Int = 10000
        private const val STOP_GRACE_MS: Long = 500L
        private const val STOP_TIMEOUT_MS: Long = 1000L
        private const val DEFAULT_ACK_RETRY_MS: Long = 2000L
        private const val BONJOUR_SERVICE_TYPE = "_wailo._tcp.local."
        private const val BONJOUR_FALLBACK_NAME = "Wailo"
    }
}
