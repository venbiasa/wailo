package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.BodyRequest
import com.venbiasa.wailo.protocol.BodyResponse
import com.venbiasa.wailo.protocol.BreakpointAction
import com.venbiasa.wailo.protocol.BreakpointDecision
import com.venbiasa.wailo.protocol.BreakpointHit
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.BreakpointRulesAck
import com.venbiasa.wailo.protocol.CaptureFilterAck
import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.protocol.RuleAck
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Streams captured exchanges to the desktop over a WebSocket as a *live tap*, and drives the desktop's
 * device-bound control channel back down the same socket: Map Local (ADR-0019), the capture filter
 * (ADR-0029), and breakpoints (ADR-0027). Only traffic captured while a connection is up is sent;
 * [onExchange] never blocks (it hands the exchange to the current connection's channel, or drops it when
 * disconnected), and nothing is retained across a disconnect so a reconnect never replays stale traffic
 * (ADR-0025). A background loop reconnects with a fixed backoff whenever the desktop isn't up; a
 * WebSocket ping keepalive self-heals a silently dropped idle link. Overflow on a live-but-slow link
 * drops the oldest exchange so a slow desktop never blocks or OOMs the host app.
 *
 * Inbound frames the desktop pushes are applied to the process-global stores ([WailoRuleStore],
 * [WailoCaptureFilterStore], [WailoBreakpointStore]) and acknowledged by epoch so a lost push
 * self-repairs (anti-entropy, mirroring the engine). The cached snapshots are dropped on every
 * disconnect so the desktop stays the single source of truth. As the [WailoBodyFetcher] and
 * [WailoBreakpointGate] it answers the interceptor's on-match calls: a Map Local match fetches the body
 * (bounded by a timeout), a breakpoint hit holds indefinitely for a decision; both fail open when the
 * link is (or goes) down, so a desktop that quits can never wedge the host app.
 *
 * The Kotlin analog of the iOS `WailoClient`.
 */
class WailoClient(
    private val hello: Hello,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val bufferCapacity: Int = DEFAULT_BUFFER,
    private val bodyTimeoutMs: Long = DEFAULT_BODY_TIMEOUT_MS,
) : CaptureSink, WailoBodyFetcher, WailoBreakpointGate, AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The live connection's exchange channel, or null while disconnected. Swapped in on connect and
    // cleared on disconnect, so an exchange captured while down is dropped (a live tap) rather than
    // buffered into a backlog that would replay on reconnect.
    @Volatile
    private var live: Channel<HttpExchange>? = null

    // The live connection's reliable outbound channel for control frames (acks, body requests,
    // breakpoint hits). Unlike the exchange tap it is UNLIMITED and never drops: a lost ack breaks
    // anti-entropy and a lost hit would hang a held call. Nulled BEFORE the pending maps are drained on
    // disconnect, so a fetch/pause registered mid-teardown always observes the drop and fails open.
    @Volatile
    private var control: Channel<Envelope>? = null

    // In-flight Map Local body fetches / breakpoint holds, keyed by a client-minted correlation id.
    // CompletableFuture bridges the client's coroutines (which complete each one from an inbound frame,
    // or with null from a disconnect drain = fail open) to the OkHttp interceptor threads that block on
    // them. Concurrent because registration happens on interceptor threads and completion on the socket
    // coroutine.
    private val pending = ConcurrentHashMap<String, CompletableFuture<BodyResponse?>>()
    private val pendingBreakpoints = ConcurrentHashMap<String, CompletableFuture<BreakpointDecision?>>()

    private val client = HttpClient(CIO) {
        // Actively probe the link so a silently dropped idle connection is noticed (the session closes
        // on pong timeout) and the loop reconnects, instead of sitting half-open until the next send.
        install(WebSockets) { pingIntervalMillis = PING_INTERVAL_MS }
    }

    fun start() {
        // Become the process-wide control authority so the interceptor can reach the desktop for Map
        // Local body fetches and breakpoint decisions. Mirrors iOS `Wailo.start` wiring the globals.
        WailoControlChannel.bodyFetcher = this
        WailoControlChannel.breakpointGate = this
        scope.launch { connectLoop() }
    }

    override fun onExchange(exchange: HttpExchange) {
        // Live tap: deliver only when a connection is up; drop otherwise.
        live?.trySend(exchange)
    }

    fun stop() {
        if (WailoControlChannel.bodyFetcher === this) WailoControlChannel.bodyFetcher = null
        if (WailoControlChannel.breakpointGate === this) WailoControlChannel.breakpointGate = null
        control = null
        dropCachedRules()
        drainPending()
        drainBreakpoints()
        scope.cancel()
        client.close()
    }

    /** Lets [WailoRuntime.install] tear this client down when it is replaced by another sink. */
    override fun close() = stop()

    // MARK: - Map Local body fetch (WailoBodyFetcher)

    override fun fetchBody(ruleId: String, url: String, method: String): WailoMappedResponse? {
        val correlationId = UUID.randomUUID().toString()
        val future = CompletableFuture<BodyResponse?>()
        pending[correlationId] = future
        val channel = control
        val request = Envelope(
            body_request = BodyRequest(correlation_id = correlationId, rule_id = ruleId, url = url, method = method),
        )
        if (channel == null || channel.trySend(request).isFailure) {
            // Not connected (or the link is closing): fall open to the real network.
            pending.remove(correlationId)
            return null
        }
        return try {
            val response = future.get(bodyTimeoutMs, TimeUnit.MILLISECONDS)
            if (response != null && response.found) {
                WailoMappedResponse(code = response.code, headers = response.headers, body = response.body)
            } else {
                null
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            // Timeout or drain: fall open to the real network.
            null
        } finally {
            pending.remove(correlationId)
        }
    }

    // MARK: - Breakpoints (WailoBreakpointGate)

    override fun pauseRequest(ruleId: String, request: HttpRequest): WailoRequestDecision {
        val decision = hold { correlationId ->
            BreakpointHit(
                correlation_id = correlationId,
                rule_id = ruleId,
                phase = BreakpointPhase.BREAKPOINT_PHASE_REQUEST,
                request = request,
            )
        } ?: return WailoRequestDecision.Proceed(null)
        return if (decision.action == BreakpointAction.BREAKPOINT_ACTION_ABORT) {
            WailoRequestDecision.Abort
        } else {
            WailoRequestDecision.Proceed(decision.edited_request)
        }
    }

    override fun pauseResponse(
        ruleId: String,
        request: HttpRequest,
        response: HttpResponse,
    ): WailoResponseDecision {
        val decision = hold { correlationId ->
            BreakpointHit(
                correlation_id = correlationId,
                rule_id = ruleId,
                phase = BreakpointPhase.BREAKPOINT_PHASE_RESPONSE,
                request = request,
                response = response,
            )
        } ?: return WailoResponseDecision.Proceed(null)
        return if (decision.action == BreakpointAction.BREAKPOINT_ACTION_ABORT) {
            WailoResponseDecision.Abort
        } else {
            WailoResponseDecision.Proceed(decision.edited_response)
        }
    }

    // Stream a breakpoint hit and block on the desktop's decision. No timeout — a human is deciding —
    // so the only ways out are the matching BreakpointDecision or a disconnect drain (returns null =
    // fail open). Registers the future before reading `control`, so a concurrent teardown (which nulls
    // `control` then drains) can never orphan the wait: either we see the null and fail open here, or
    // the drain completes our future with null.
    private fun hold(buildHit: (correlationId: String) -> BreakpointHit): BreakpointDecision? {
        val correlationId = UUID.randomUUID().toString()
        val future = CompletableFuture<BreakpointDecision?>()
        pendingBreakpoints[correlationId] = future
        val channel = control
        if (channel == null || channel.trySend(Envelope(breakpoint_hit = buildHit(correlationId))).isFailure) {
            pendingBreakpoints.remove(correlationId)
            return null
        }
        return try {
            future.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            pendingBreakpoints.remove(correlationId)
        }
    }

    private suspend fun connectLoop() {
        while (scope.isActive) {
            try {
                client.webSocket(host = host, port = port, path = PATH) {
                    val session = this
                    // A fresh pair of channels per connection: the exchange tap is bounded and drops the
                    // oldest under back-pressure, while control is unbounded and reliable.
                    val exchanges = Channel<HttpExchange>(bufferCapacity, BufferOverflow.DROP_OLDEST)
                    val controlChannel = Channel<Envelope>(Channel.UNLIMITED)
                    live = exchanges
                    control = controlChannel
                    try {
                        // Send Hello first, then start draining, so Hello is always the opening frame.
                        session.send(Frame.Binary(true, Envelope(hello = hello).encode()))
                        val exchangeDrainer = launch {
                            for (exchange in exchanges) {
                                if (!trySendFrame(session, Envelope(exchange = exchange))) break
                            }
                        }
                        val controlDrainer = launch {
                            for (envelope in controlChannel) {
                                if (!trySendFrame(session, envelope)) break
                            }
                        }
                        try {
                            // Drive the control channel and observe connection failure: this returns the
                            // moment the socket closes for any reason (desktop close, dropped link, or a
                            // ping/pong timeout on a dead idle link), which is what lets an otherwise idle
                            // connection reconnect.
                            for (frame in incoming) {
                                if (frame is Frame.Binary) handleIncoming(Envelope.ADAPTER.decode(frame.readBytes()))
                            }
                        } finally {
                            exchangeDrainer.cancel()
                            controlDrainer.cancel()
                        }
                    } finally {
                        // Order matters: null `control` before draining so a fetch/pause registered
                        // mid-teardown observes the drop and fails open rather than waiting on a future
                        // nobody will complete. Then stop accepting into this connection's channels.
                        control = null
                        live = null
                        controlChannel.close()
                        exchanges.close()
                        dropCachedRules()
                        drainPending()
                        drainBreakpoints()
                    }
                }
            } catch (_: Exception) {
                // Never established or link dropped before the block ran: ensure caches/holds are cleared
                // (idempotent with the finally above) then back off and retry.
                control = null
                dropCachedRules()
                drainPending()
                drainBreakpoints()
            }
            if (scope.isActive) delay(RECONNECT_DELAY_MS)
        }
    }

    // Encode + send a frame, funneling any failure into the same reconnect path as everything else by
    // returning false so the drainer stops (the closing socket ends the incoming loop above).
    private suspend fun trySendFrame(session: DefaultClientWebSocketSession, envelope: Envelope): Boolean =
        try {
            session.send(Frame.Binary(true, envelope.encode()))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    /**
     * Desktop -> device control frames. A pushed snapshot (rules / capture filter / breakpoint rules) is
     * applied wholesale to its store and its epoch acked so a lost push self-repairs; a BodyResponse or
     * BreakpointDecision resolves the matching in-flight hold. Runs on the socket coroutine.
     */
    private fun handleIncoming(envelope: Envelope) {
        envelope.rule_set?.let { ruleSet ->
            WailoRuleStore.replace(ruleSet.rules)
            control?.trySend(Envelope(rule_ack = RuleAck(epoch = ruleSet.epoch)))
        }
        envelope.capture_filter?.let { filter ->
            WailoCaptureFilterStore.replace(filter)
            control?.trySend(Envelope(capture_filter_ack = CaptureFilterAck(epoch = filter.epoch)))
        }
        envelope.breakpoint_rules?.let { rules ->
            WailoBreakpointStore.replace(rules.rules)
            control?.trySend(Envelope(breakpoint_rules_ack = BreakpointRulesAck(epoch = rules.epoch)))
        }
        envelope.body_response?.let { response ->
            pending.remove(response.correlation_id)?.complete(response)
        }
        envelope.breakpoint_decision?.let { decision ->
            pendingBreakpoints.remove(decision.correlation_id)?.complete(decision)
        }
    }

    /**
     * Drop the cached desktop snapshots on disconnect. The desktop is the source of truth for all three,
     * so with the connection gone there is no authority: Map Local matching stops, the capture filter
     * falls back to capture-everything, and breakpoints stop firing until a reconnect re-pushes.
     * Idempotent — every failed reconnect lands here.
     */
    private fun dropCachedRules() {
        WailoRuleStore.replace(emptyList())
        WailoCaptureFilterStore.reset()
        WailoBreakpointStore.replace(emptyList())
    }

    /** Fail open every in-flight body fetch: with the socket gone there is no authority to answer. */
    private fun drainPending() {
        for (id in pending.keys.toList()) pending.remove(id)?.complete(null)
    }

    /** Fail open every held request/response: with the socket gone there is no authority to decide. */
    private fun drainBreakpoints() {
        for (id in pendingBreakpoints.keys.toList()) pendingBreakpoints.remove(id)?.complete(null)
    }

    companion object {
        const val DEFAULT_HOST: String = "localhost"
        const val DEFAULT_PORT: Int = 8899
        private const val DEFAULT_BUFFER: Int = 512
        private const val DEFAULT_BODY_TIMEOUT_MS: Long = 10_000L
        private const val RECONNECT_DELAY_MS: Long = 2000L
        private const val PING_INTERVAL_MS: Long = 20_000L
        private const val PATH: String = "/"
    }
}
