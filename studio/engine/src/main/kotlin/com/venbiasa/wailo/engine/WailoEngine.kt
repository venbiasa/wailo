package com.venbiasa.wailo.engine

import com.venbiasa.wailo.protocol.BodyRequest
import com.venbiasa.wailo.protocol.BodyResponse
import com.venbiasa.wailo.protocol.BreakpointAction
import com.venbiasa.wailo.protocol.BreakpointDecision
import com.venbiasa.wailo.protocol.BreakpointHit
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.BreakpointRule
import com.venbiasa.wailo.protocol.BreakpointRules
import com.venbiasa.wailo.protocol.CaptureAllowlist
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
 * Headless capture server: accepts device WebSocket connections, decodes the protobuf stream, and
 * publishes exchanges as a [StateFlow] for any frontend (desktop UI now; CLI/MCP later). It also owns
 * the reverse direction — Map Local (ADR-0019): it pushes the match-metadata [RuleSet] to every device
 * (versioned by [RuleSet.epoch], re-pushed until the device acks so a lost push self-repairs), and
 * answers a device's [BodyRequest] by reading the body through [bodyProvider]. It likewise pushes
 * [BreakpointRules] and, when a device pauses a matching request/response, surfaces it via
 * [pausedExchanges] and releases it through [resumeBreakpoint]/[abortBreakpoint] (ADR-0027). UI-agnostic
 * by design: no Compose here, and no filesystem access beyond the provider seam.
 */
class WailoEngine(
    val port: Int = DEFAULT_PORT,
    private val maxRetained: Int = DEFAULT_MAX_RETAINED,
    // How often to re-push to a device that hasn't acked the current epoch. Injectable so tests can
    // exercise the anti-entropy retry without waiting the production interval.
    private val ackRetryMs: Long = DEFAULT_ACK_RETRY_MS,
) {
    private val _exchanges = MutableStateFlow<List<CapturedExchange>>(emptyList())
    val exchanges: StateFlow<List<CapturedExchange>> = _exchanges.asStateFlow()

    private val _capturing = MutableStateFlow(true)

    /** Whether new exchanges are being recorded. Paused keeps the server up but drops incoming traffic. */
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    private val _rules = MutableStateFlow(RuleSet())

    /** The active Map Local rules (match-metadata only) currently pushed to devices. Frontends observe this. */
    val rules: StateFlow<RuleSet> = _rules.asStateFlow()

    private val _allowlist = MutableStateFlow(CaptureAllowlist())

    /**
     * Hosts whose request/response bodies devices capture. Metadata is always captured; this gates only
     * body bytes (Proxyman-style "unlock"), so memory stays bounded to the unlocked hosts. Pushed to
     * devices like [rules]; frontends observe this. Empty means capture no bodies (the default).
     */
    val allowlist: StateFlow<CaptureAllowlist> = _allowlist.asStateFlow()

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

    /** Set by the frontend (the desktop) to resolve a matched rule's body on demand. */
    @Volatile
    var bodyProvider: MapLocalBodyProvider? = null

    // Monotonic version stamped on every snapshot; the device echoes it in a RuleAck. Only used for
    // ack-matching/retry, never to gate the device's apply, so a restart resetting it is harmless.
    private val epochCounter = AtomicLong(0)

    // Separate monotonic version for the capture allowlist, acked independently of the rule epoch.
    private val allowlistEpochCounter = AtomicLong(0)

    // Separate monotonic version for the breakpoint rules, acked independently of the others.
    private val breakpointEpochCounter = AtomicLong(0)

    // Live device connections + how far each is acked, so a rule change reaches every attached SDK and a
    // silently lost push is re-sent. Concurrent because Ktor runs one handler coroutine per connection.
    private val sessions = ConcurrentHashMap<DefaultWebSocketServerSession, SessionState>()

    // Which session raised each paused exchange, keyed by its correlation id, so a resume/abort routes
    // the decision back to the exact device that is holding that request/response. Entries are removed
    // when the decision is sent or when the owning session disconnects.
    private val pausedSessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    // Rule pushes run off the caller's thread; serialized so frames to a session never interleave.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sendMutex = Mutex()

    private var server: EmbeddedServer<*, *>? = null

    /** Per-connection sync state: the highest snapshot epoch this device has acknowledged applying. */
    private class SessionState {
        val ackedEpoch = AtomicLong(-1)
        val ackedAllowlistEpoch = AtomicLong(-1)
        val ackedBreakpointEpoch = AtomicLong(-1)
    }

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    val state = SessionState()
                    sessions[this] = state
                    // Re-push to a lagging device until it acks the current epoch, so a silently dropped
                    // snapshot self-repairs without waiting for the next edit or reconnect (ADR-0019).
                    val reconciler = launch { reconcile(this@webSocket, state) }
                    try {
                        // Sync the freshly connected (or reconnected) device with the current rules,
                        // capture allowlist, and breakpoint rules.
                        pushRules(this)
                        pushAllowlist(this)
                        pushBreakpointRules(this)
                        var hello: Hello? = null
                        for (frame in incoming) {
                            if (frame !is Frame.Binary) continue
                            val envelope = Envelope.ADAPTER.decode(frame.readBytes())
                            envelope.hello?.let { hello = it }
                            envelope.exchange?.let { record(hello, it) }
                            envelope.rule_ack?.let { ack ->
                                state.ackedEpoch.updateAndGet { cur -> maxOf(cur, ack.epoch) }
                            }
                            envelope.capture_allowlist_ack?.let { ack ->
                                state.ackedAllowlistEpoch.updateAndGet { cur -> maxOf(cur, ack.epoch) }
                            }
                            envelope.breakpoint_rules_ack?.let { ack ->
                                state.ackedBreakpointEpoch.updateAndGet { cur -> maxOf(cur, ack.epoch) }
                            }
                            // Serve bodies off the read loop so a slow file read can't stall this
                            // connection's incoming frames; the sendMutex still serializes the reply.
                            envelope.body_request?.let { req -> launch { serveBody(this@webSocket, req) } }
                            envelope.breakpoint_hit?.let { hit -> recordBreakpointHit(hello, hit, this@webSocket) }
                        }
                    } finally {
                        reconciler.cancel()
                        sessions.remove(this)
                        releasePausedFor(this)
                    }
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        server = null
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
    private suspend fun pushRules(session: DefaultWebSocketServerSession) {
        sendMutex.withLock {
            val bytes = Envelope(rule_set = _rules.value).encode()
            runCatching { session.outgoing.send(Frame.Binary(true, bytes)) }
        }
    }

    /**
     * Replace the set of hosts whose bodies devices capture and push the new snapshot to every device.
     * Stamps a fresh [CaptureAllowlist.epoch] so devices can ack it and the reconciler can detect a lost
     * push. Metadata is always captured regardless; this gates only body bytes (Proxyman-style "unlock").
     */
    fun updateAllowlist(hostPatterns: List<String>) {
        _allowlist.value = CaptureAllowlist(host_patterns = hostPatterns, epoch = allowlistEpochCounter.incrementAndGet())
        scope.launch { sessions.keys.forEach { pushAllowlist(it) } }
    }

    // Mirrors pushRules: reads _allowlist under the lock at send time so a connect-push racing a
    // broadcast still converges on the newest snapshot.
    private suspend fun pushAllowlist(session: DefaultWebSocketServerSession) {
        sendMutex.withLock {
            val bytes = Envelope(capture_allowlist = _allowlist.value).encode()
            runCatching { session.outgoing.send(Frame.Binary(true, bytes)) }
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

    // Mirrors pushRules/pushAllowlist: reads _breakpointRules under the lock at send time so a
    // connect-push racing a broadcast still converges on the newest snapshot.
    private suspend fun pushBreakpointRules(session: DefaultWebSocketServerSession) {
        sendMutex.withLock {
            val bytes = Envelope(breakpoint_rules = _breakpointRules.value).encode()
            runCatching { session.outgoing.send(Frame.Binary(true, bytes)) }
        }
    }

    // Re-push the current snapshot to a device that hasn't acked it yet. Cancelled when the connection
    // ends (the launching coroutine is a child of the session handler).
    private suspend fun reconcile(session: DefaultWebSocketServerSession, state: SessionState) {
        while (true) {
            delay(ackRetryMs)
            if (state.ackedEpoch.get() < _rules.value.epoch) pushRules(session)
            if (state.ackedAllowlistEpoch.get() < _allowlist.value.epoch) pushAllowlist(session)
            if (state.ackedBreakpointEpoch.get() < _breakpointRules.value.epoch) pushBreakpointRules(session)
        }
    }

    // Answer a matched request by reading the body through the provider, or found=false so the device
    // falls open to the real network. The provider owns any IO dispatch.
    private suspend fun serveBody(session: DefaultWebSocketServerSession, request: BodyRequest) {
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
            runCatching { session.outgoing.send(Frame.Binary(true, Envelope(body_response = response).encode())) }
        }
    }

    // Record a device's paused request/response and remember which session raised it, so a later
    // resume/abort can route the decision straight back to that connection.
    private fun recordBreakpointHit(hello: Hello?, hit: BreakpointHit, session: DefaultWebSocketServerSession) {
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
                runCatching {
                    session.outgoing.send(Frame.Binary(true, Envelope(breakpoint_decision = decision).encode()))
                }
            }
        }
    }

    // Drop every exchange a disconnecting session was holding. The device fails those calls open on its
    // side when the link drops, so the desktop must not keep showing them as pausable.
    private fun releasePausedFor(session: DefaultWebSocketServerSession) {
        val orphaned = pausedSessions.entries.filter { it.value == session }.map { it.key }
        if (orphaned.isEmpty()) return
        orphaned.forEach { pausedSessions.remove(it) }
        _pausedExchanges.update { list -> list.filterNot { it.correlationId in orphaned } }
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

    companion object {
        const val DEFAULT_PORT: Int = 8899
        private const val DEFAULT_MAX_RETAINED: Int = 10000
        private const val STOP_GRACE_MS: Long = 500L
        private const val STOP_TIMEOUT_MS: Long = 1000L
        private const val DEFAULT_ACK_RETRY_MS: Long = 2000L
    }
}
