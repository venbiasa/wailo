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

final class WailoTransportSession: CaptureSink, WailoBodyFetcher, WailoBreakpointGate, @unchecked Sendable {

    private let hello: Hello
    private let bufferCapacity: Int
    private let bodyTimeout: TimeInterval
    private let pingInterval: TimeInterval
    private let queue: DispatchQueue
    private let queueKey = DispatchSpecificKey<Void>()

    private weak var link: WailoTransportLink?
    private var buffer: [HttpExchange] = []
    private var pending: [String: (WailoMappedResponse?) -> Void] = [:]
    private var pendingBreakpoints: [String: (BreakpointDecision?) -> Void] = [:]
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

    init(
        hello: Hello,
        queue: DispatchQueue,
        bufferCapacity: Int = 512,
        bodyTimeout: TimeInterval = 10.0,
        pingInterval: TimeInterval = 20.0
    ) {
        self.hello = hello
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
            guard self.link != nil,
                  let envelope = try? ProtoDecoder().decode(Envelope.self, from: data)
            else { return }
            self.handleIncoming(envelope)
        }
    }

    func linkDidClose() {
        onQueue { self.unbindLocked() }
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
        generation += 1
        let gen = generation
        self.link = link
        bound = true
        send(Envelope { $0.message = .hello(hello) }, isHandshake: true, gen: gen)
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

    // MARK: - queue-confined

    private func unbindLocked() {
        generation += 1
        connected = false
        sending = false
        bound = false
        buffer.removeAll()
        dropCachedRules()
        drainPending()
        drainBreakpoints()
        link?.stopKeepalive()
        link?.closeLink()
        link = nil
    }

    private func handleIncoming(_ envelope: Envelope) {
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

    private func dropCachedRules() {
        WailoRuleStore.shared.replace([])
        WailoCaptureFilterStore.shared.reset()
        WailoBreakpointStore.shared.replace([])
    }

    private func resolvePending(_ response: BodyResponse) {
        guard let completion = pending.removeValue(forKey: response.correlation_id) else { return }
        if response.found {
            completion(WailoMappedResponse(code: Int(response.code), headers: response.headers, body: response.body))
        } else {
            completion(nil)
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

    private func sendControl(_ envelope: Envelope) {
        guard let link, let data = try? ProtoEncoder().encode(envelope) else { return }
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
        let data: Data
        do {
            data = try ProtoEncoder().encode(envelope)
        } catch {
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
