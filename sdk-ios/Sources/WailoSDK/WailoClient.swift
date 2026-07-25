import Foundation
import WailoProtocol
import Wire

/// Streams captured exchanges to the desktop over a WebSocket, and drives the Map Local control channel
/// back down the same socket (ADR-0019): it caches the desktop's match-metadata snapshots (dropping them
/// whenever the connection is lost, so the desktop stays the single source of truth), acknowledges each
/// snapshot's epoch so a lost push can be re-sent, and — as the `WailoBodyFetcher` — fetches a matched
/// rule's body on demand rather than caching bodies. The Swift port of `core.WailoClient`, and a *live
/// tap*: `onExchange` enqueues only while a connection exists, and the buffer is cleared on disconnect,
/// so traffic captured while the desktop is down is dropped rather than replayed on reconnect (ADR-0025).
/// A background loop reconnects whenever the desktop isn't up, so a dropped/stale link self-heals for
/// future traffic. On overflow the oldest is dropped so a slow desktop never blocks or grows memory
/// without bound in the host app.
///
/// Reconnection is deliberately hard to wedge. `URLSessionWebSocketTask` does not guarantee that the
/// `send`/`receive` completion handlers fire on every failure (notably a connection that never
/// establishes, or a silently dropped idle link), so relying on them alone can leave the client
/// "waiting" forever with no retry ever scheduled. To close that gap, every way a connection can end
/// -- a failed `send`/`receive`, the `URLSessionTaskDelegate` completion, a server close, or a
/// ping/pong timeout -- funnels through `handleDisconnect`, which always schedules the next attempt.
///
/// State is confined to a private serial queue, so the class is safe to call from any thread.
final class WailoClient: NSObject, CaptureSink, WailoBodyFetcher, WailoBreakpointGate, URLSessionWebSocketDelegate, @unchecked Sendable {

    private let hello: Hello
    private let url: URL
    private let bufferCapacity: Int
    private let reconnectDelay: TimeInterval
    private let pingInterval: TimeInterval
    private let bodyTimeout: TimeInterval

    private let queue = DispatchQueue(label: "com.venbiasa.wailo.client")
    // Built on first connect so it can carry `self` as delegate; torn down in `stop()`. Its config
    // strips the interceptor (see `transportConfiguration`) so the client can't capture its own socket.
    private var session: URLSession?

    private var task: URLSessionWebSocketTask?
    private var buffer: [HttpExchange] = []
    // In-flight Map Local body fetches, keyed by correlation id (queue-confined). Each completion is
    // called exactly once — by the matching BodyResponse, a timeout, or a disconnect drain (fail-open).
    private var pending: [String: (WailoMappedResponse?) -> Void] = [:]
    // In-flight breakpoint holds, keyed by correlation id (queue-confined). Each resolver is called
    // exactly once — by the matching BreakpointDecision or by a disconnect drain (fail-open). Unlike a
    // body fetch there is NO timeout: a human is deciding, so the hold lasts until a decision arrives or
    // the link drops.
    private var pendingBreakpoints: [String: (BreakpointDecision?) -> Void] = [:]
    private var started = false
    private var connected = false
    private var sending = false
    // Bumped on every connect and every disconnect. Callbacks capture the generation of the attempt
    // that armed them and no-op once it moves, so a late/duplicate failure signal can't fire a second
    // reconnect and each disconnect retries exactly once.
    private var generation = 0

    init(
        hello: Hello,
        host: String,
        port: Int,
        bufferCapacity: Int = 512,
        reconnectDelay: TimeInterval = 2.0,
        pingInterval: TimeInterval = 20.0,
        bodyTimeout: TimeInterval = 10.0
    ) {
        self.hello = hello
        self.url = URL(string: "ws://\(host):\(port)/")!
        self.bufferCapacity = bufferCapacity
        self.reconnectDelay = reconnectDelay
        self.pingInterval = pingInterval
        self.bodyTimeout = bodyTimeout
        super.init()
    }

    func start() {
        queue.async {
            guard !self.started else { return }
            self.started = true
            self.connect()
        }
    }

    func onExchange(_ exchange: HttpExchange) {
        queue.async {
            // Live tap: only accept while a connection exists (the buffer is cleared on disconnect, so
            // it never holds a backlog across a drop). Traffic captured while the desktop is down is
            // dropped rather than hoarded to replay on reconnect.
            guard self.task != nil else { return }
            self.buffer.append(exchange)
            let overflow = self.buffer.count - self.bufferCapacity
            if overflow > 0 {
                self.buffer.removeFirst(overflow)
            }
            self.pump()
        }
    }

    func stop() {
        queue.async {
            self.started = false
            self.connected = false
            self.dropCachedRules()
            self.drainPending()
            self.drainBreakpoints()
            self.generation += 1
            self.task?.cancel(with: .goingAway, reason: nil)
            self.task = nil
            // Break the session's strong hold on this delegate; otherwise a replaced client (e.g. a
            // second `Wailo.start`) would leak a still-live WebSocket.
            self.session?.invalidateAndCancel()
            self.session = nil
        }
    }

    #if DEBUG
    /// Test-support: stop, then block until the serial queue has drained the teardown. Since a
    /// client's reconnect loop now clears the process-global `WailoRuleStore` on every disconnect, a
    /// client left churning by one test could wipe rules a later test just set (the store is shared).
    /// Draining guarantees all of this client's store mutations happen before the owning test returns;
    /// after `stop()` no further clear is scheduled (reconnect no-ops once `started` is false).
    func stopAndWaitForTeardown() {
        stop()
        queue.sync {}
    }
    #endif

    // MARK: - queue-confined

    private func connect() {
        guard started, task == nil else { return }
        generation += 1
        let gen = generation
        let session = self.session ?? URLSession(configuration: transportConfiguration(), delegate: self, delegateQueue: nil)
        self.session = session
        let task = session.webSocketTask(with: url)
        self.task = task
        task.resume()
        listen(task, gen)
        // Open with Hello; success flips `connected`, starts the keepalive, and drains the buffer.
        send(Envelope { $0.message = .hello(hello) }, isHandshake: true, gen: gen)
    }

    /// A config that can't intercept this transport's own socket. `.ephemeral` is one of the getters
    /// `Wailo.start` swizzles to inject `WailoURLProtocol`, and a `ws://` upgrade reaches the
    /// `URLProtocol` layer as `http(s)://` — so the scheme check can't skip it. Without stripping the
    /// interceptor here, the client would capture and replay its own handshake, wedging reconnects.
    private func transportConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = (configuration.protocolClasses ?? []).filter { $0 != WailoURLProtocol.self }
        return configuration
    }

    /// Receives frames to observe connection failure (triggering reconnect) and to drive the Map Local
    /// control channel (rule snapshots and body-fetch replies) the desktop sends back down this socket.
    private func listen(_ task: URLSessionWebSocketTask, _ gen: Int) {
        task.receive { [weak self] result in
            guard let self else { return }
            self.queue.async {
                guard gen == self.generation else { return }
                switch result {
                case let .success(message):
                    self.handleIncoming(message)
                    self.listen(task, gen)
                case .failure:
                    self.handleDisconnect(gen)
                }
            }
        }
    }

    /// Desktop -> device control frames: a RuleSet snapshot (apply wholesale, then ack its epoch so a
    /// lost push self-repairs) or a BodyResponse (resolve the matching in-flight fetch). Anything else
    /// is ignored. Runs on the queue via `listen`.
    private func handleIncoming(_ message: URLSessionWebSocketTask.Message) {
        guard case let .data(data) = message,
              let envelope = try? ProtoDecoder().decode(Envelope.self, from: data)
        else { return }
        switch envelope.message {
        case let .rule_set(ruleSet)?:
            WailoRuleStore.shared.replace(ruleSet.rules)
            sendControl(Envelope { $0.message = .rule_ack(RuleAck(epoch: ruleSet.epoch)) })
        case let .capture_filter(filter)?:
            WailoCaptureFilterStore.shared.replace(filter)
            sendControl(Envelope { $0.message = .capture_filter_ack(CaptureFilterAck(epoch: filter.epoch)) })
        case let .body_response(response)?:
            resolvePending(response)
        case let .breakpoint_rules(rules)?:
            WailoBreakpointStore.shared.replace(rules.rules)
            sendControl(Envelope { $0.message = .breakpoint_rules_ack(BreakpointRulesAck(epoch: rules.epoch)) })
        case let .breakpoint_decision(decision)?:
            resolveBreakpoint(decision)
        default:
            break
        }
    }

    /// Drop the cached desktop snapshots (Map Local rules + capture filter + breakpoint rules). The
    /// desktop is the source of truth for all of them, so once the connection is gone there is no
    /// authority: Map Local matching falls back to pass-through and the capture filter falls back to
    /// capture-everything until a reconnect re-pushes the current set. Idempotent — every failed
    /// reconnect lands here.
    private func dropCachedRules() {
        WailoRuleStore.shared.replace([])
        WailoCaptureFilterStore.shared.reset()
        WailoBreakpointStore.shared.replace([])
    }

    // MARK: - Map Local body fetch (WailoBodyFetcher)

    /// Ask the desktop for a matched rule's response. Fails open (completion(nil)) when the socket is
    /// down, and arms a timeout so a lost/slow reply can't hang the request forever. Safe to call from
    /// the interceptor on any thread; the pending map and send are marshaled onto the queue.
    func fetchBody(ruleId: String, url: String, method: String, completion: @escaping (WailoMappedResponse?) -> Void) {
        queue.async {
            guard self.task != nil else { completion(nil); return }
            let correlationId = UUID().uuidString
            self.pending[correlationId] = completion
            self.sendControl(Envelope {
                $0.message = .body_request(BodyRequest(
                    correlation_id: correlationId,
                    rule_id: ruleId,
                    url: url,
                    method: method
                ))
            })
            self.queue.asyncAfter(deadline: .now() + self.bodyTimeout) { [weak self] in
                guard let self else { return }
                if let timedOut = self.pending.removeValue(forKey: correlationId) { timedOut(nil) }
            }
        }
    }

    private func resolvePending(_ response: BodyResponse) {
        guard let completion = pending.removeValue(forKey: response.correlation_id) else { return }
        if response.found {
            completion(WailoMappedResponse(code: Int(response.code), headers: response.headers, body: response.body))
        } else {
            completion(nil)
        }
    }

    /// Fail open every in-flight fetch: with the socket gone there is no authority to answer, so each
    /// matched request falls back to the real network instead of hanging until its timeout.
    private func drainPending() {
        let waiting = pending.values
        pending.removeAll()
        for completion in waiting { completion(nil) }
    }

    // MARK: - Breakpoints (WailoBreakpointGate)

    /// Pause a matched request and stream it to the desktop as a REQUEST-phase hit. Fails open
    /// (`.proceed(nil)`) when the socket is down; otherwise holds — with NO timeout, since a human is
    /// deciding — until the matching `BreakpointDecision` resolves it or a disconnect drains it.
    func pauseRequest(ruleId: String, request: HttpRequest, completion: @escaping (WailoRequestDecision) -> Void) {
        queue.async {
            guard self.task != nil else { completion(.proceed(nil)); return }
            let correlationId = UUID().uuidString
            self.pendingBreakpoints[correlationId] = { decision in
                guard let decision else { completion(.proceed(nil)); return }
                switch decision.action {
                case .BREAKPOINT_ACTION_ABORT: completion(.abort)
                default: completion(.proceed(decision.edited_request))
                }
            }
            self.sendControl(Envelope {
                $0.message = .breakpoint_hit(BreakpointHit(
                    correlation_id: correlationId,
                    rule_id: ruleId,
                    phase: .BREAKPOINT_PHASE_REQUEST
                ) { $0.request = request })
            })
        }
    }

    /// Pause a matched response and stream it (with the originating request, for context) as a
    /// RESPONSE-phase hit. Same hold/fail-open contract as `pauseRequest`.
    func pauseResponse(
        ruleId: String,
        request: HttpRequest,
        response: HttpResponse,
        completion: @escaping (WailoResponseDecision) -> Void
    ) {
        queue.async {
            guard self.task != nil else { completion(.proceed(nil)); return }
            let correlationId = UUID().uuidString
            self.pendingBreakpoints[correlationId] = { decision in
                guard let decision else { completion(.proceed(nil)); return }
                switch decision.action {
                case .BREAKPOINT_ACTION_ABORT: completion(.abort)
                default: completion(.proceed(decision.edited_response))
                }
            }
            self.sendControl(Envelope {
                $0.message = .breakpoint_hit(BreakpointHit(
                    correlation_id: correlationId,
                    rule_id: ruleId,
                    phase: .BREAKPOINT_PHASE_RESPONSE
                ) {
                    $0.request = request
                    $0.response = response
                })
            })
        }
    }

    private func resolveBreakpoint(_ decision: BreakpointDecision) {
        guard let resolver = pendingBreakpoints.removeValue(forKey: decision.correlation_id) else { return }
        resolver(decision)
    }

    /// Fail open every held request/response: with the socket gone there is no authority to decide, so
    /// each proceeds with its original message instead of hanging for a decision that can't arrive.
    private func drainBreakpoints() {
        let waiting = pendingBreakpoints.values
        pendingBreakpoints.removeAll()
        for resolver in waiting { resolver(nil) }
    }

    /// Send a control frame (ack / body request) without touching the exchange pump's send state. A
    /// send failure funnels into the same reconnect path as everything else.
    private func sendControl(_ envelope: Envelope) {
        guard let task, let data = try? ProtoEncoder().encode(envelope) else { return }
        let gen = generation
        task.send(.data(data)) { [weak self] error in
            guard let self, error != nil else { return }
            self.queue.async { if gen == self.generation { self.handleDisconnect(gen) } }
        }
    }

    private func pump() {
        guard connected, !sending, task != nil, !buffer.isEmpty else { return }
        sending = true
        let next = buffer.removeFirst()
        send(Envelope { $0.message = .exchange(next) }, isHandshake: false, gen: generation)
    }

    private func send(_ envelope: Envelope, isHandshake: Bool, gen: Int) {
        guard let task else { return }
        let data: Data
        do {
            data = try ProtoEncoder().encode(envelope)
        } catch {
            sending = false
            return
        }
        task.send(.data(data)) { [weak self] error in
            guard let self else { return }
            self.queue.async {
                guard gen == self.generation else { return }
                if error != nil {
                    self.handleDisconnect(gen)
                    return
                }
                if isHandshake {
                    self.connected = true
                    self.schedulePing(gen)
                } else {
                    self.sending = false
                }
                self.pump()
            }
        }
    }

    /// Active liveness probe. A silently dropped idle link (no FIN/RST) never fails `receive`, so
    /// without this the socket could sit "connected" forever; a failed ping forces a reconnect.
    private func schedulePing(_ gen: Int) {
        queue.asyncAfter(deadline: .now() + pingInterval) { [weak self] in
            guard let self, gen == self.generation, let task = self.task else { return }
            task.sendPing { [weak self] error in
                guard let self else { return }
                self.queue.async {
                    guard gen == self.generation else { return }
                    if error != nil {
                        self.handleDisconnect(gen)
                    } else {
                        self.schedulePing(gen)
                    }
                }
            }
        }
    }

    private func handleDisconnect(_ gen: Int) {
        // Only the first failure signal for this attempt acts; bumping the generation makes every
        // other in-flight callback (another send/receive, the delegate, the pinger) a no-op, so
        // exactly one reconnect is scheduled.
        guard gen == generation else { return }
        generation += 1
        connected = false
        sending = false
        // Live tap: drop any not-yet-sent exchanges so the next connection starts clean and never
        // replays a backlog captured across the drop (ADR-0025).
        buffer.removeAll()
        dropCachedRules()
        drainPending()
        drainBreakpoints()
        task?.cancel(with: .abnormalClosure, reason: nil)
        task = nil
        guard started else { return }
        // Desktop unreachable; back off and retry. Only traffic captured while the next connection is
        // live will be sent.
        queue.asyncAfter(deadline: .now() + reconnectDelay) { [weak self] in
            self?.connect()
        }
    }

    // MARK: - URLSessionTaskDelegate / URLSessionWebSocketDelegate

    /// The authoritative "task finished" signal, and the reason reconnect can't wedge: it fires even
    /// when the `send`/`receive` handlers don't (e.g. a connection that is refused or never
    /// establishes), so a failed attempt still schedules a retry.
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        queue.async {
            guard task === self.task else { return }
            self.handleDisconnect(self.generation)
        }
    }

    /// A clean server-side close (e.g. the desktop app quitting) also drives a reconnect.
    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        queue.async {
            guard webSocketTask === self.task else { return }
            self.handleDisconnect(self.generation)
        }
    }
}
