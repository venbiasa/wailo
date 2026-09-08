import Foundation
import WailoProtocol
import Wire

/// Shared device-side session over any WebSocket link. Owns the live tap, Map Local / capture-filter /
/// breakpoint control channel, and the exchange pump — identical whether the SDK dials Studio over LAN
/// or accepts Studio through a USB tunnel.
protocol WailoTransportLink: AnyObject {
    func send(_ data: Data, completion: @escaping (Error?) -> Void)
    func startKeepalive(interval: TimeInterval, onFailure: @escaping () -> Void)
    func stopKeepalive()
    func closeLink()
}

/// Whether this session has to prove anything before it talks (ADR-0039).
///
/// `open` is loopback and USB — the Simulator, `adb reverse`, the usbmux tunnel. A peer on 127.0.0.1
/// cannot be another machine, so there is nothing a key would establish and the session starts at
/// `Hello` exactly as it always did. `guarded` is WiFi, where the peer is whoever answered an mDNS
/// advertisement. The factory runs per connection because each handshake needs its own nonce.
enum WailoSessionSecurity {
    case open
    case guarded(() -> WailoHandshake)
}

// A newer bind must outrank late frames from a transport that is still retiring.
final class WailoSnapshotOwner: @unchecked Sendable {

    private final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var value: UInt64 = 0

        func increment() -> UInt64 {
            lock.lock()
            defer { lock.unlock() }
            value += 1
            return value
        }
    }

    private static let counter = Counter()

    let generation: UInt64

    private init(generation: UInt64) {
        self.generation = generation
    }

    static func next() -> WailoSnapshotOwner {
        WailoSnapshotOwner(generation: counter.increment())
    }
}

final class WailoTransportSession: CaptureSink, WailoBodyFetcher, WailoBreakpointGate, @unchecked Sendable {

    private let hello: Hello
    private let security: WailoSessionSecurity
    private let bufferCapacity: Int
    private let bodyTimeout: TimeInterval
    private let pingInterval: TimeInterval
    private let queue: DispatchQueue
    private let queueKey = DispatchSpecificKey<Void>()

    private weak var link: WailoTransportLink?
    private var snapshotOwner: WailoSnapshotOwner?
    private var buffer: [HttpExchange] = []
    private var pending: [String: (WailoMappedResponse?) -> Void] = [:]
    private var pendingBreakpoints: [String: (BreakpointDecision?) -> Void] = [:]
    private var pendingRevocations: [String: (Bool) -> Void] = [:]
    /// Non-nil only between dialling and `AuthResultV3`; every frame in that window is an auth frame.
    private var handshake: WailoHandshake?
    /// Installed the moment auth succeeds, which is also the moment anything else may be sent.
    private var codec: WailoFrameCodec?
    private var bound = false
    private var connected = false {
        didSet {
            guard connected != oldValue else { return }
            onConnectionChange?(connected)
        }
    }
    private var sending = false
    private var generation = 0

    /// Fires on the session queue when the link opens or closes.
    var onConnectionChange: ((Bool) -> Void)?

    /// A pairing worth persisting: the whole record on a first pairing, the advanced session counter
    /// afterwards.
    /// Return false to abort before Hello, for example when Forget raced the final auth result.
    var onHandshakeEstablished: ((WailoPairing) -> Bool)?

    /// Studio answered that it does not know this device. Distinct from a plain disconnect because the
    /// caller has to stop retrying rather than reconnect into the same refusal every two seconds.
    var onHandshakeRefused: ((String) -> Void)?

    /// A different identity is answering at an address this device has a pinned key for. Never
    /// reconciled down here: only a human can say whether the machine changed hands or someone is in
    /// the path (ADR-0040).
    var onIdentityChanged: ((String, String) -> Void)?

    var onAuthenticationStarted: (() -> Void)?

    init(
        hello: Hello,
        queue: DispatchQueue,
        security: WailoSessionSecurity = .open,
        bufferCapacity: Int = 512,
        bodyTimeout: TimeInterval = 10.0,
        pingInterval: TimeInterval = 20.0
    ) {
        self.hello = hello
        self.security = security
        self.queue = queue
        self.bufferCapacity = bufferCapacity
        self.bodyTimeout = bodyTimeout
        self.pingInterval = pingInterval
        queue.setSpecific(key: queueKey, value: ())
    }

    /// Attach a live link, send Hello, and begin streaming. Re-binding replaces any prior link.
    func bind(to link: WailoTransportLink) {
        onQueue { self.bindLocked(to: link) }
    }

    func unbind() {
        onQueue { self.unbindLocked() }
    }

    #if DEBUG
    func unbindAndWait() {
        queue.sync { self.unbindLocked() }
    }
    #endif

    func onExchange(_ exchange: HttpExchange) {
        onQueue {
            guard self.link != nil else { return }
            self.buffer.append(exchange)
            let overflow = self.buffer.count - self.bufferCapacity
            if overflow > 0 { self.buffer.removeFirst(overflow) }
            self.pump()
        }
    }

    func receive(_ data: Data) {
        onQueue {
            guard self.link != nil, let envelope = self.decodeFromWire(data) else { return }
            if self.handshake != nil {
                self.advanceHandshake(envelope)
            } else {
                self.handleIncoming(envelope)
            }
        }
    }

    func linkDidClose() {
        onQueue { self.unbindLocked() }
    }

    /// Ask the authenticated Studio to remove this session's alias. Completion is best-effort and
    /// bounded: local Forget must never retain a credential because the peer is offline (ADR-0060).
    func revoke(completion: @escaping (Bool) -> Void) {
        onQueue {
            guard self.connected, self.codec != nil else { completion(false); return }
            let requestId = UUID().uuidString
            self.pendingRevocations[requestId] = completion
            self.sendControl(Envelope {
                $0.message = .revoke_device(RevokeDevice(request_id: requestId))
            })
            self.queue.asyncAfter(deadline: .now() + 0.35) { [weak self] in
                guard let self, let timedOut = self.pendingRevocations.removeValue(forKey: requestId) else {
                    return
                }
                timedOut(false)
            }
        }
    }

    private func onQueue(_ work: @escaping () -> Void) {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            work()
        } else {
            queue.async(execute: work)
        }
    }

    private func bindLocked(to link: WailoTransportLink) {
        unbindLocked()
        snapshotOwner = .next()
        generation += 1
        let gen = generation
        self.link = link
        bound = true
        switch security {
        case .open:
            send(Envelope { $0.message = .hello(hello) }, isHandshake: true, gen: gen)
        case let .guarded(makeHandshake):
            let handshake = makeHandshake()
            self.handshake = handshake
            onAuthenticationStarted?()
            sendAuth(handshake.begin(), gen: gen)
        }
    }

    /// Auth frames bypass the exchange pump entirely: they must not flip `connected`, must not be
    /// sealed (there is no session key yet), and must not queue behind buffered traffic.
    private func sendAuth(_ envelope: Envelope, gen: Int) {
        guard let link, let data = try? ProtoEncoder().encode(envelope) else { return }
        link.send(data) { [weak self] error in
            guard let self, error != nil else { return }
            self.queue.async { if gen == self.generation { self.unbindLocked() } }
        }
    }

    private func advanceHandshake(_ envelope: Envelope) {
        guard let handshake else { return }
        let gen = generation
        switch handshake.handle(envelope) {
        case let .send(response):
            sendAuth(response, gen: gen)
        case let .established(result):
            self.handshake = nil
            codec = WailoFrameCodec(
                sessionKey: result.sessionKey, sealing: .deviceToStudio, opening: .studioToDevice
            )
            if onHandshakeEstablished?(result.pairing) == false {
                unbindLocked()
                return
            }
            send(Envelope { $0.message = .hello(hello) }, isHandshake: true, gen: gen)
        case let .refused(reason):
            self.handshake = nil
            onHandshakeRefused?(reason)
            unbindLocked()
        case let .identityChanged(expected, actual):
            self.handshake = nil
            onIdentityChanged?(expected, actual)
            unbindLocked()
        case .failed:
            unbindLocked()
        case .ignore:
            break
        }
    }

    // MARK: - WailoBodyFetcher

    func fetchBody(ruleId: String, url: String, method: String, completion: @escaping (WailoMappedResponse?) -> Void) {
        queue.async {
            guard self.link != nil else { completion(nil); return }
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

    // MARK: - WailoBreakpointGate

    func pauseRequest(ruleId: String, request: HttpRequest, completion: @escaping (WailoRequestDecision) -> Void) {
        queue.async {
            guard self.link != nil else { completion(.proceed(nil)); return }
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

    func pauseResponse(
        ruleId: String,
        request: HttpRequest,
        response: HttpResponse,
        completion: @escaping (WailoResponseDecision) -> Void
    ) {
        queue.async {
            guard self.link != nil else { completion(.proceed(nil)); return }
            let correlationId = UUID().uuidString
            self.pendingBreakpoints[correlationId] = { decision in
                guard let decision else { completion(.proceed(nil)); return }
                switch decision.action {
                case .BREAKPOINT_ACTION_ABORT:
                    completion(.abort)
                default:
                    self.afterResponseDelay(decision.delay_ms) {
                        completion(.proceed(decision.edited_response))
                    }
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

    // MARK: - queue-confined

    private func unbindLocked() {
        generation += 1
        connected = false
        sending = false
        bound = false
        handshake = nil
        codec = nil
        buffer.removeAll()
        dropCachedRules()
        drainPending()
        drainBreakpoints()
        drainRevocations()
        link?.stopKeepalive()
        link?.closeLink()
        link = nil
    }

    private func handleIncoming(_ envelope: Envelope) {
        guard let snapshotOwner else { return }
        switch envelope.message {
        case let .rule_set(ruleSet)?:
            WailoRuleStore.shared.replace(ruleSet.rules, owner: snapshotOwner)
            sendControl(Envelope { $0.message = .rule_ack(RuleAck(epoch: ruleSet.epoch)) })
        case let .capture_filter(filter)?:
            WailoCaptureFilterStore.shared.replace(filter, owner: snapshotOwner)
            sendControl(Envelope { $0.message = .capture_filter_ack(CaptureFilterAck(epoch: filter.epoch)) })
        case let .body_response(response)?:
            resolvePending(response)
        case let .breakpoint_rules(rules)?:
            WailoBreakpointStore.shared.replace(rules.rules, owner: snapshotOwner)
            sendControl(Envelope { $0.message = .breakpoint_rules_ack(BreakpointRulesAck(epoch: rules.epoch)) })
        case let .breakpoint_decision(decision)?:
            resolveBreakpoint(decision)
        case let .revoke_device_ack(ack)?:
            pendingRevocations.removeValue(forKey: ack.request_id)?(true)
        default:
            break
        }
    }

    private func dropCachedRules() {
        guard let snapshotOwner else { return }
        WailoRuleStore.shared.reset(owner: snapshotOwner)
        WailoCaptureFilterStore.shared.reset(owner: snapshotOwner)
        WailoBreakpointStore.shared.reset(owner: snapshotOwner)
        self.snapshotOwner = nil
    }

    private func resolvePending(_ response: BodyResponse) {
        guard let completion = pending.removeValue(forKey: response.correlation_id) else { return }
        guard response.found else {
            completion(nil)
            return
        }
        let result = WailoMappedResponse(code: Int(response.code), headers: response.headers, body: response.body)
        afterResponseDelay(response.delay_ms) {
            completion(result)
        }
    }

    private func afterResponseDelay(_ milliseconds: Int32, perform action: @escaping () -> Void) {
        let delay = max(0, Int(milliseconds))
        if delay == 0 {
            action()
        } else {
            queue.asyncAfter(deadline: .now() + .milliseconds(delay), execute: action)
        }
    }

    private func drainPending() {
        let waiting = pending.values
        pending.removeAll()
        for completion in waiting { completion(nil) }
    }

    private func resolveBreakpoint(_ decision: BreakpointDecision) {
        guard let resolver = pendingBreakpoints.removeValue(forKey: decision.correlation_id) else { return }
        resolver(decision)
    }

    private func drainBreakpoints() {
        let waiting = pendingBreakpoints.values
        pendingBreakpoints.removeAll()
        for resolver in waiting { resolver(nil) }
    }

    private func drainRevocations() {
        let waiting = pendingRevocations.values
        pendingRevocations.removeAll()
        for completion in waiting { completion(false) }
    }

    /// Seals once the session has a key. Before that — loopback for its whole life, or WiFi during the
    /// handshake — an envelope goes out as itself.
    private func wireData(_ envelope: Envelope) -> Data? {
        guard let plaintext = try? ProtoEncoder().encode(envelope) else { return nil }
        guard let codec else { return plaintext }
        guard let sealed = try? codec.seal(plaintext) else { return nil }
        return try? ProtoEncoder().encode(Envelope {
            $0.message = .sealed_frame(SealedFrame(seq: sealed.seq, ciphertext: sealed.ciphertext))
        })
    }

    private func decodeFromWire(_ data: Data) -> Envelope? {
        guard let envelope = try? ProtoDecoder().decode(Envelope.self, from: data) else { return nil }
        guard let codec else { return envelope }
        guard case let .sealed_frame(frame)? = envelope.message,
              let plaintext = try? codec.open(seq: frame.seq, ciphertext: frame.ciphertext),
              let inner = try? ProtoDecoder().decode(Envelope.self, from: plaintext) else {
            // Once a session is sealed, a frame that arrives unsealed, replayed or altered is an
            // attack rather than a glitch, and the session is not worth continuing.
            unbindLocked()
            return nil
        }
        return inner
    }

    private func sendControl(_ envelope: Envelope) {
        guard let link, let data = wireData(envelope) else { return }
        let gen = generation
        link.send(data) { [weak self] error in
            guard let self, error != nil else { return }
            self.queue.async { if gen == self.generation { self.unbindLocked() } }
        }
    }

    private func pump() {
        guard connected, !sending, link != nil, !buffer.isEmpty else { return }
        sending = true
        let next = buffer.removeFirst()
        send(Envelope { $0.message = .exchange(next) }, isHandshake: false, gen: generation)
    }

    private func send(_ envelope: Envelope, isHandshake: Bool, gen: Int) {
        guard let link else { return }
        guard let data = wireData(envelope) else {
            sending = false
            return
        }
        link.send(data) { [weak self] error in
            guard let self else { return }
            self.queue.async {
                guard gen == self.generation else { return }
                if error != nil {
                    self.unbindLocked()
                    return
                }
                if isHandshake {
                    self.connected = true
                    self.startKeepalive(gen: gen)
                } else {
                    self.sending = false
                }
                self.pump()
            }
        }
    }

    private func startKeepalive(gen: Int) {
        link?.startKeepalive(interval: pingInterval) { [weak self] in
            guard let self else { return }
            self.queue.async { if gen == self.generation { self.unbindLocked() } }
        }
    }
}
