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
import com.venbiasa.wailo.protocol.RevokeDevice
import com.venbiasa.wailo.protocol.SealedFrame
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
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
 * It also decides *which* desktop to dial, re-resolved on every attempt (see [WailoEndpointResolver]),
 * and how much it has to prove to talk to it. A loopback peer — `adb reverse`, the emulator — is this
 * machine by construction and starts at `Hello` as it always did; anything else is WiFi and must
 * complete the identity-first handshake of ADR-0060 first, after which every frame is sealed. Because the
 * endpoint and the trust are read fresh per attempt rather than captured at construction, a first
 * contact that succeeds is simply found in the store by the next reconnect — the reconnect failure
 * ADR-0046 records on iOS cannot arise here.
 *
 * Inbound frames the desktop pushes are applied to the process-global stores ([WailoRuleStore],
 * [WailoCaptureFilterStore], [WailoBreakpointStore]) and acknowledged by epoch so a lost push
 * self-repairs (anti-entropy, mirroring the engine). The cached snapshots are dropped on every
 * disconnect so the desktop stays the single source of truth. As the [WailoBodyFetcher] and
 * [WailoBreakpointGate] it answers the interceptor's on-match calls: a Map Local match fetches the body
 * (bounded by a timeout), a breakpoint hit holds indefinitely for a decision; both fail open when the
 * link is (or goes) down, so a desktop that quits can never wedge the host app.
 *
 * The Kotlin analog of the iOS `WailoClient` + `WailoCoordinator`.
 */
class WailoClient(
    private val hello: Hello,
    /** Pins the desktop for this client's whole life, outranking anything the panel or discovery says. */
    private val host: String? = null,
    private val port: Int? = null,
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

    // The socket currently open, so a settings change can drop it rather than let the user stare at a
    // "connected" badge for the address they just replaced.
    @Volatile
    private var liveSession: DefaultClientWebSocketSession? = null

    // In-flight Map Local body fetches / breakpoint holds, keyed by a client-minted correlation id.
    // CompletableFuture bridges the client's coroutines (which complete each one from an inbound frame,
    // or with null from a disconnect drain = fail open) to the OkHttp interceptor threads that block on
    // them. Concurrent because registration happens on interceptor threads and completion on the socket
    // coroutine.
    private val pending = ConcurrentHashMap<String, CompletableFuture<BodyResponse?>>()
    private val pendingBreakpoints = ConcurrentHashMap<String, CompletableFuture<BreakpointDecision?>>()
    private val pendingRevocations = ConcurrentHashMap<String, () -> Unit>()

    @Volatile
    private var activeStudioId: String? = null

    private val lock = Any()

    /** Set by [pair], cleared once the handshake it authorises succeeds. Outranks everything else. */
    private var pendingInvite: WailoPairingInvite? = null
    private val forgetting = mutableSetOf<String>()
    private var forgettingAll = false

    /**
     * Hosts the user has said "yes, that new identity is fine" about, cleared once one is taken. In
     * memory only: accepting a changed identity is a decision about right now, and a stale one
     * surviving a relaunch would silently widen it.
     */
    private val acceptedIdentityChanges = mutableSetOf<String>()

    /** The address a refusal came from, so the 2-second loop stops walking back into the same "no". */
    private var refusedHost: String? = null

    /** Forget is a stop action; only a later explicit Connect/pair action may resume dialling. */
    private var connectionSuppressedAfterForget = false

    private var discovery: WailoDiscovery? = null

    // Process-global rather than per-client, because the panel that reads it is a separate artifact
    // with no handle on this instance. One client is active at a time (`Wailo.active`), so there is
    // nothing to reconcile.
    private val _status get() = Wailo.mutableStatus

    /** Everything the on-device panel binds to. Updated from the connect loop and the control methods. */
    val status: StateFlow<WailoStatus> get() = Wailo.status

    /**
     * Wakes the connect loop out of its backoff. Conflated because "reconnect now" does not stack: any
     * number of settings changes between two attempts still means exactly one re-dial.
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Serialises seal-and-send. The exchange and control drainers both write to one socket, and a
     * sealed frame's sequence number is assigned at seal time — so without this, two frames sealed in
     * order could still reach the wire reversed, and the far end would read the lower one as a replay
     * and drop the session.
     */
    private val sending = Mutex()

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
        Wailo.active = this
        startDiscovery()
        refreshSettings()
        scope.launch { connectLoop() }
    }

    override fun onExchange(exchange: HttpExchange) {
        // Live tap: deliver only when a connection is up; drop otherwise.
        live?.trySend(exchange)
    }

    fun stop() {
        if (WailoControlChannel.bodyFetcher === this) WailoControlChannel.bodyFetcher = null
        if (WailoControlChannel.breakpointGate === this) WailoControlChannel.breakpointGate = null
        val wasActive = Wailo.active === this
        if (wasActive) Wailo.active = null
        control = null
        discovery?.stop()
        discovery = null
        dropCachedRules()
        drainPending()
        drainBreakpoints()
        drainRevocations()
        scope.cancel()
        client.close()
        // Only the client the status describes may blank it — a replaced sink is stopped *after* its
        // replacement has started (`WailoRuntime.install`), and would otherwise erase the live one.
        if (wasActive) _status.value = WailoStatus()
    }

    /** Lets [WailoRuntime.install] tear this client down when it is replaced by another sink. */
    override fun close() = stop()

    // MARK: - control surface (the on-device panel, and anything the host app wires itself)

    /**
     * Persist a manual override (or null to hand the address back to discovery) and re-dial at once.
     *
     * Returns whether the address was taken. Text that names nothing diallable changes nothing: a
     * working address has to survive a typo, and above all a value that cannot be dialled must never
     * reach preferences, where every later launch would read it back (ADR-0035).
     */
    fun setHost(host: String?, port: Int? = null, expectedStudioId: String? = null): Boolean {
        if (port != null && port !in WailoAddress.PORT_RANGE) return false
        val address = if (host == null) null else WailoAddress.parse(host) ?: return false
        synchronized(lock) {
            connectionSuppressedAfterForget = false
            WailoHostStore.host = address?.host
            // A port typed into the address itself is the more specific answer, and splitting it out
            // here keeps the store holding a bare host — the shape everything downstream expects.
            WailoHostStore.port = address?.port ?: port
            WailoHostStore.expectedStudioId = if (address == null) null else expectedStudioId
            // A warning about a different address has nothing to say about this one, and leaving it up
            // would attach it to whatever the user typed next.
            if (_status.value.identityChange?.host != address?.host) clearIdentityChangeLocked()
            refusedHost = null
        }
        _status.update { it.copy(refusal = null, phase = WailoConnectionPhase.DIALLING) }
        refreshSettings()
        redial()
        return true
    }

    /**
     * Take a scanned QR or a typed code and dial the Studio it names. The key is derived from the
     * invite and only persisted once the handshake proves the peer really is that Studio, so a mistyped
     * code leaves nothing behind.
     */
    fun pair(invite: WailoPairingInvite) {
        synchronized(lock) {
            connectionSuppressedAfterForget = false
            pendingInvite = invite
            refusedHost = null
            // A pinned manual address would otherwise outrank the invite we were just handed.
            WailoHostStore.host = null
            WailoHostStore.expectedStudioId = null
        }
        _status.update { it.copy(refusal = null, phase = WailoConnectionPhase.DIALLING) }
        refreshSettings()
        redial()
    }

    fun forget(studioId: String) {
        val shouldRevoke = synchronized(lock) {
            if (forgettingAll) return
            if (!forgetting.add(studioId)) return
            connectionSuppressedAfterForget = true
            activeStudioId == studioId
        }
        val finish = { finishForget(studioId) }
        if (shouldRevoke) revokeThen(finish) else finish()
    }

    private fun finishForget(studioId: String) {
        synchronized(lock) {
            if (!forgetting.remove(studioId)) return
            connectionSuppressedAfterForget = true
            unpinAddressLocked(listOfNotNull(WailoPairingStore.pairing(studioId)))
            WailoPairingStore.forget(studioId)
            if (pendingInvite?.studioId == studioId) pendingInvite = null
            refusedHost = null
            _status.value.identityChange?.takeIf { it.expected == studioId }?.let { change ->
                acceptedIdentityChanges -= change.host
                clearIdentityChangeLocked()
            }
        }
        activeStudioId = null
        _status.update {
            it.copy(
                connected = false,
                activeAddress = null,
                handshakeWaived = false,
                refusal = null,
                phase = WailoConnectionPhase.STOPPED,
            )
        }
        refreshSettings()
        redial()
    }

    fun forgetAllPairings() {
        val shouldRevoke = synchronized(lock) {
            if (forgettingAll) return
            forgettingAll = true
            connectionSuppressedAfterForget = true
            activeStudioId != null
        }
        val finish = ::finishForgetAll
        if (shouldRevoke) revokeThen(finish) else finish()
    }

    private fun finishForgetAll() {
        synchronized(lock) {
            if (!forgettingAll) return
            forgettingAll = false
            forgetting.clear()
            connectionSuppressedAfterForget = true
            unpinAddressLocked(WailoPairingStore.all())
            WailoPairingStore.forgetAll()
            pendingInvite = null
            refusedHost = null
            acceptedIdentityChanges.clear()
            clearIdentityChangeLocked()
        }
        activeStudioId = null
        _status.update {
            it.copy(
                connected = false,
                activeAddress = null,
                handshakeWaived = false,
                refusal = null,
                phase = WailoConnectionPhase.STOPPED,
            )
        }
        refreshSettings()
        redial()
    }

    private fun revokeThen(finish: () -> Unit) {
        val requestId = UUID.randomUUID().toString()
        val channel = control
        if (channel == null) {
            finish()
            return
        }
        pendingRevocations[requestId] = finish
        if (channel.trySend(Envelope(revoke_device = RevokeDevice(request_id = requestId))).isFailure) {
            pendingRevocations.remove(requestId)?.invoke()
            return
        }
        scope.launch {
            delay(REVOKE_TIMEOUT_MS)
            pendingRevocations.remove(requestId)?.invoke()
        }
    }

    /**
     * Clears the "this Studio does not know you" latch so the device tries once more. Deliberately a
     * human action because repeated signed refusals still need a different action, usually Forget.
     */
    fun retryAfterRefusal() {
        synchronized(lock) {
            refusedHost = null
            for (pairing in WailoPairingStore.all()) {
                if (pairing.refused) WailoPairingStore.save(pairing.copy(refused = false))
            }
        }
        _status.update { it.copy(refusal = null, phase = WailoConnectionPhase.DIALLING) }
        refreshSettings()
        redial()
    }

    /**
     * Take the new identity at that address: drop the old pin and let the next connection establish a
     * fresh one. Only ever an explicit user action, which is what separates "my Mac changed hands" from
     * "someone is in the path" — nothing on the wire can tell them apart (ADR-0040).
     */
    fun acceptIdentityChange() {
        synchronized(lock) {
            val change = _status.value.identityChange ?: return
            acceptedIdentityChanges += change.host
            clearIdentityChangeLocked()
        }
        _status.update { it.copy(phase = WailoConnectionPhase.DIALLING) }
        redial()
    }

    /** Leave the pin alone and stop dialling that address. */
    fun rejectIdentityChange() {
        synchronized(lock) {
            val change = _status.value.identityChange ?: return
            acceptedIdentityChanges -= change.host
            clearIdentityChangeLocked()
            // Hand the address back to discovery, which only reconnects to identities already known.
            if (WailoHostStore.host == change.host) WailoHostStore.host = null
            if (WailoHostStore.host == null) WailoHostStore.expectedStudioId = null
        }
        refreshSettings()
        _status.update { it.copy(phase = WailoConnectionPhase.STOPPED) }
        redial()
    }

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

    // MARK: - connect loop

    /** One connection's plan: where to dial, and the handshake it owes (null on loopback). */
    private class Attempt(val endpoint: WailoEndpoint, val handshake: WailoHandshake?) {
        val url: String get() = "ws://${endpoint.host}:${endpoint.port}$PATH"
    }

    private suspend fun connectLoop() {
        while (scope.isActive) {
            // A wake queued while the last attempt was still running has already been answered by
            // re-entering this loop; leaving it would turn the next backoff into a busy retry.
            wake.tryReceive()
            val attempt = nextAttempt()
            if (attempt == null) {
                // Nothing worth saying to whoever is on the other end, so do not open the socket at all
                // — even a `Hello` names the app and the device to it.
                _status.update {
                    it.copy(
                        connected = false,
                        activeAddress = null,
                        handshakeWaived = false,
                        phase = when {
                            it.refusal != null -> WailoConnectionPhase.REFUSED
                            it.identityChange != null -> WailoConnectionPhase.IDENTITY_MISMATCH
                            else -> WailoConnectionPhase.STOPPED
                        },
                    )
                }
            } else {
                _status.update {
                    it.copy(
                        activeAddress = attempt.endpoint.toString(),
                        handshakeWaived = attempt.handshake == null,
                        phase = WailoConnectionPhase.DIALLING,
                    )
                }
                try {
                    connect(attempt)
                } catch (e: CancellationException) {
                    // `redial` cancels only the live WebSocket session. That surfaces here as the
                    // same exception used to stop this client's scope, so distinguish the two or the
                    // first settings change/Forget would kill the reconnect loop permanently.
                    if (!scope.isActive) throw e
                } catch (_: Exception) {
                    // Never established or the link dropped before the block ran: ensure caches/holds
                    // are cleared (idempotent with the finally below) then back off and retry.
                    control = null
                    dropCachedRules()
                    drainPending()
                    drainBreakpoints()
                }
                _status.update {
                    it.copy(
                        connected = false,
                        phase = when {
                            it.refusal != null -> WailoConnectionPhase.REFUSED
                            it.identityChange != null -> WailoConnectionPhase.IDENTITY_MISMATCH
                            else -> WailoConnectionPhase.DIALLING
                        },
                    )
                }
                activeStudioId = null
            }
            if (scope.isActive) withTimeoutOrNull(RECONNECT_DELAY_MS) { wake.receive() }
        }
    }

    /**
     * Where to dial next and what it costs to be heard there, or null for "dial nothing".
     *
     * Everything here is read fresh: the pinned host, the pairing, the session counter the last
     * handshake advanced. That is deliberate — this runs again on every reconnect, and a value captured
     * one connection ago is exactly what ADR-0046 records going wrong.
     */
    private fun nextAttempt(): Attempt? {
        val discovered = _status.value.discovered
        val invite = synchronized(lock) {
            if (connectionSuppressedAfterForget) return null
            pendingInvite
        }
        val endpoint = if (invite != null) {
            WailoEndpoint(invite.host, invite.port, invite.studioId)
        } else {
            WailoEndpointResolver.resolve(host, port, discovered)
        }

        // Loopback proves itself: `adb reverse` and the emulator both land here, and the kernel already
        // guarantees the peer is this machine. Resolved the same way the engine resolves it, so the two
        // ends never disagree about whether the session opens with `AuthClientHelloV3` or `Hello`.
        if (!forcesHandshakeForTesting && endpoint.isLoopback()) return Attempt(endpoint, null)

        synchronized(lock) {
            if (endpoint.host == refusedHost) return null
            val change = _status.value.identityChange
            if (change != null && change.host == endpoint.host && change.host !in acceptedIdentityChanges) {
                return null
            }
            val accepted = endpoint.host in acceptedIdentityChanges
            val expectedPairing = endpoint.studioId?.let(WailoPairingStore::pairing)
            if (!accepted && expectedPairing?.refused == true) return null
            val pairing = expectedPairing?.takeIf { !accepted }
            val trust = WailoHandshake.trustFor(
                pairing = pairing,
                invite = invite?.takeIf { it.host == endpoint.host },
                candidates = if (isChosen(endpoint) && pairing == null) {
                    WailoPairingStore.all().filter { !it.refused }
                } else {
                    emptyList()
                },
                expectedStudioId = endpoint.studioId?.takeUnless { accepted },
            )
            // An address nobody chose, that nothing is known about, is not worth a word. Discovery is
            // already filtered to trusted desktops, so this only ever catches a programming error —
            // but the cost of getting it wrong is naming the app to a stranger.
            if (trust is WailoHandshake.Trust.FirstContact && !isChosen(endpoint)) return null
            return Attempt(endpoint, WailoHandshake(trust, endpoint.host))
        }
    }

    /**
     * Whether a human put this address here. It is the whole basis for a lenient first contact: an
     * address someone typed or scanned carries an intent that an mDNS advertisement never does.
     */
    private fun isChosen(endpoint: WailoEndpoint): Boolean =
        host != null || WailoHostStore.host == endpoint.host || pendingInvite?.host == endpoint.host

    private suspend fun connect(attempt: Attempt) {
        client.webSocket(urlString = attempt.url) {
            val session = this
            val codec = if (attempt.handshake == null) {
                null
            } else {
                authenticate(session, attempt.handshake, attempt.endpoint) ?: return@webSocket
            }
            // A fresh pair of channels per connection: the exchange tap is bounded and drops the
            // oldest under back-pressure, while control is unbounded and reliable.
            val exchanges = Channel<HttpExchange>(bufferCapacity, BufferOverflow.DROP_OLDEST)
            val controlChannel = Channel<Envelope>(Channel.UNLIMITED)
            live = exchanges
            control = controlChannel
            liveSession = session
            try {
                // Send Hello first, then start draining, so Hello is always the opening frame after
                // whatever the handshake needed.
                if (!trySendFrame(session, Envelope(hello = hello), codec)) return@webSocket
                _status.update { it.copy(connected = true, phase = WailoConnectionPhase.CONNECTED) }
                val exchangeDrainer = launch {
                    for (exchange in exchanges) {
                        if (!trySendFrame(session, Envelope(exchange = exchange), codec)) break
                    }
                }
                val controlDrainer = launch {
                    for (envelope in controlChannel) {
                        if (!trySendFrame(session, envelope, codec)) break
                    }
                }
                try {
                    // Drive the control channel and observe connection failure: this returns the
                    // moment the socket closes for any reason (desktop close, dropped link, or a
                    // ping/pong timeout on a dead idle link), which is what lets an otherwise idle
                    // connection reconnect.
                    for (frame in incoming) {
                        if (frame !is Frame.Binary) continue
                        // Once a session is sealed, a frame that arrives unsealed, replayed or altered
                        // is an attack rather than a glitch, and the session is not worth continuing.
                        handleIncoming(openFrame(frame.readBytes(), codec) ?: break)
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
                liveSession = null
                controlChannel.close()
                exchanges.close()
                dropCachedRules()
                drainPending()
                drainBreakpoints()
                drainRevocations()
            }
        }
    }

    // MARK: - handshake

    /**
     * Runs the WiFi handshake to completion, returning the codec every later frame is sealed with, or
     * null when the session must not continue. Straight-line rather than a callback state machine
     * because Ktor hands us a suspending frame channel: [WailoHandshake] stays pure, this owns the I/O.
     */
    private suspend fun authenticate(
        session: DefaultClientWebSocketSession,
        handshake: WailoHandshake,
        endpoint: WailoEndpoint,
    ): WailoFrameCodec? {
        _status.update { it.copy(phase = WailoConnectionPhase.AUTHENTICATING) }
        // Auth frames bypass sealing (there is no session key yet) and must not flip `connected`.
        session.send(Frame.Binary(true, handshake.begin().encode()))
        for (frame in session.incoming) {
            if (frame !is Frame.Binary) continue
            val envelope = runCatching { Envelope.ADAPTER.decode(frame.readBytes()) }.getOrNull() ?: return null
            when (val step = handshake.handle(envelope)) {
                is WailoHandshake.Step.Send -> session.send(Frame.Binary(true, step.envelope.encode()))
                is WailoHandshake.Step.Done -> {
                    if (!established(step.established.pairing)) return null
                    return WailoFrameCodec(
                        sessionKey = step.established.sessionKey,
                        sealing = WailoFrameCodec.Direction.DEVICE_TO_STUDIO,
                        opening = WailoFrameCodec.Direction.STUDIO_TO_DEVICE,
                    )
                }

                is WailoHandshake.Step.Refused -> {
                    refused(step.reason, endpoint)
                    return null
                }

                is WailoHandshake.Step.IdentityChanged -> {
                    identityChanged(endpoint.host, step.expected, step.actual)
                    return null
                }

                WailoHandshake.Step.Failed -> return null
                WailoHandshake.Step.Ignore -> Unit
            }
        }
        return null
    }

    private fun established(pairing: WailoPairing): Boolean {
        synchronized(lock) {
            // Forget may race the last result frame. The latch check and persistence must share this
            // lock with Forget or a completed handshake could resurrect the relationship and send Hello.
            if (connectionSuppressedAfterForget) return false
            // A previous entry for this address is stale the moment a different identity is accepted
            // there; leaving it would keep resolving the host back to a Studio that has moved on.
            if (acceptedIdentityChanges.remove(pairing.lastHost)) {
                for (stale in WailoPairingStore.all()) {
                    if (stale.lastHost == pairing.lastHost && stale.studioId != pairing.studioId) {
                        WailoPairingStore.forget(stale.studioId)
                    }
                }
            }
            WailoPairingStore.save(pairing)
            if (WailoHostStore.host == pairing.lastHost) {
                WailoHostStore.expectedStudioId = pairing.studioId
            }
            activeStudioId = pairing.studioId
            if (pendingInvite?.studioId == pairing.studioId) pendingInvite = null
            refusedHost = null
        }
        _status.update {
            it.copy(
                refusal = null,
                identityChange = it.identityChange?.takeIf { change -> change.host != pairing.lastHost },
            )
        }
        refreshSettings()
        return true
    }

    /**
     * Studio will not take this device. The key stays until the human chooses Forget; Retry keeps the
     * relationship and makes one deliberate new attempt.
     */
    private fun refused(reason: String, endpoint: WailoEndpoint) {
        synchronized(lock) {
            refusedHost = endpoint.host
            WailoEndpointResolver.pairingFor(endpoint.host, endpoint.studioId, _status.value.discovered)
                ?.let { WailoPairingStore.save(it.copy(refused = true)) }
            pendingInvite = null
        }
        _status.update { it.copy(refusal = reason, phase = WailoConnectionPhase.REFUSED) }
        refreshSettings()
    }

    private fun identityChanged(host: String, expected: String, actual: String) {
        _status.update {
            it.copy(
                identityChange = WailoIdentityChange(host, expected, actual),
                phase = WailoConnectionPhase.IDENTITY_MISMATCH,
            )
        }
    }

    // MARK: - discovery

    private fun startDiscovery() {
        val context = Wailo.appContext ?: return
        if (discovery != null) return
        discovery = WailoDiscovery(context).apply {
            onChange = { desktops -> discoveryChanged(desktops) }
            start()
        }
    }

    private fun discoveryChanged(desktops: List<WailoDesktop>) {
        _status.update { it.copy(discovered = desktops) }
        // A trusted desktop that just appeared is worth dialling now rather than at the end of the
        // backoff, and a live connection is left alone — the loop only re-resolves once it drops.
        if (!_status.value.connected) redial()
    }

    // MARK: - wire

    /**
     * Encode + send a frame, sealing it once the session has a key. Any failure is funnelled into the
     * same reconnect path as everything else by returning false so the drainer stops (the closing
     * socket ends the incoming loop above).
     */
    private suspend fun trySendFrame(
        session: DefaultClientWebSocketSession,
        envelope: Envelope,
        codec: WailoFrameCodec?,
    ): Boolean = try {
        sending.withLock {
            session.send(Frame.Binary(true, sealFrame(envelope, codec)))
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun sealFrame(envelope: Envelope, codec: WailoFrameCodec?): ByteArray {
        val plaintext = envelope.encode()
        if (codec == null) return plaintext
        val sealed = codec.seal(plaintext)
        return Envelope(
            sealed_frame = SealedFrame(seq = sealed.seq, ciphertext = sealed.ciphertext.toByteString()),
        ).encode()
    }

    /** Null when a sealed session receives something it should not have; the caller hangs up. */
    private fun openFrame(bytes: ByteArray, codec: WailoFrameCodec?): Envelope? {
        val envelope = Envelope.ADAPTER.decode(bytes)
        if (codec == null) return envelope
        val sealed = envelope.sealed_frame ?: return null
        val plaintext = runCatching { codec.open(sealed.seq, sealed.ciphertext.toByteArray()) }.getOrNull()
            ?: return null
        return runCatching { Envelope.ADAPTER.decode(plaintext) }.getOrNull()
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
        envelope.revoke_device_ack?.let { ack ->
            pendingRevocations.remove(ack.request_id)?.invoke()
        }
    }

    // MARK: - housekeeping

    /** Drop the live connection, if any, and re-resolve immediately instead of after the backoff. */
    private fun redial() {
        // Cancelling the session closes the socket, which ends the frame loop and returns the connect
        // block — the same path a desktop quitting takes, so no teardown is special-cased for this.
        runCatching { liveSession?.cancel() }
        wake.trySend(Unit)
    }

    private fun refreshSettings() {
        _status.update {
            it.copy(
                configuredHost = host ?: WailoHostStore.host,
                configuredPort = port ?: WailoHostStore.port,
                pairings = WailoPairingStore.all(),
            )
        }
    }

    private fun clearIdentityChangeLocked() {
        _status.update { it.copy(identityChange = null) }
    }

    /**
     * Forgetting a desktop has to let go of its address too.
     *
     * A pinned address outranks everything else, and a first contact at an address the user named is
     * taken at its word (ADR-0040) — so dropping only the key would re-trust the same desktop on the
     * next dial two seconds later. The separate suppression latch keeps Forget stopped until another
     * explicit Connect (ADR-0060).
     */
    private fun unpinAddressLocked(pairings: List<WailoPairing>) {
        val pinned = WailoHostStore.host ?: return
        if (pairings.any { it.lastHost == pinned }) {
            WailoHostStore.host = null
            WailoHostStore.expectedStudioId = null
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

    private fun drainRevocations() {
        for (id in pendingRevocations.keys.toList()) pendingRevocations.remove(id)?.invoke()
    }

    companion object {
        const val DEFAULT_PORT: Int = 8899
        private const val DEFAULT_BUFFER: Int = 512
        private const val DEFAULT_BODY_TIMEOUT_MS: Long = 10_000L
        private const val RECONNECT_DELAY_MS: Long = 2000L
        private const val REVOKE_TIMEOUT_MS: Long = 350L
        private const val PING_INTERVAL_MS: Long = 20_000L
        private const val PATH: String = "/"

        /**
         * Treats a loopback address as WiFi, so the guarded path can be driven from a test.
         *
         * The only peer a unit test can dial is this machine, and loopback is precisely the case the
         * handshake is skipped for — so without this the whole device half of ADR-0039/0040 is left to
         * manual smoke, which is how the reconnect bug in ADR-0046 survived on iOS.
         */
        @Volatile
        internal var forcesHandshakeForTesting: Boolean = false
    }
}
