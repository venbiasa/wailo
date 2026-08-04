import Foundation
import WailoProtocol
import Wire

/// Outbound WebSocket client: dials a desktop over LAN and delegates session semantics to
/// `WailoTransportSession`. Reconnects whenever the link drops unless suspended for USB takeover.
///
/// Reconnection is deliberately hard to wedge. `URLSessionWebSocketTask` does not guarantee that the
/// `send`/`receive` completion handlers fire on every failure, so every path that can end a connection
/// funnels through `handleDisconnect`, which always schedules the next attempt when allowed.
final class WailoClient: NSObject, CaptureSink, WailoBodyFetcher, WailoBreakpointGate, URLSessionWebSocketDelegate, WailoTransportLink, @unchecked Sendable {

    private let url: URL
    private let reconnectDelay: TimeInterval
    private let queue = DispatchQueue(label: "com.venbiasa.wailo.client")
    private let session: WailoTransportSession

    private var urlSession: URLSession?
    private var task: URLSessionWebSocketTask?
    private var started = false
    private var suspended = false
    private var generation = 0
    private var pingGeneration = 0

    var onConnectionChange: ((Bool) -> Void)? {
        get { session.onConnectionChange }
        set { session.onConnectionChange = newValue }
    }

    var onHandshakeEstablished: ((WailoPairing) -> Void)? {
        get { session.onHandshakeEstablished }
        set { session.onHandshakeEstablished = newValue }
    }

    var onHandshakeRefused: ((String) -> Void)? {
        get { session.onHandshakeRefused }
        set { session.onHandshakeRefused = newValue }
    }

    var onIdentityChanged: ((String, String) -> Void)? {
        get { session.onIdentityChanged }
        set { session.onIdentityChanged = newValue }
    }

    /// Takes a URL rather than a host and port: whether free text names a diallable address is
    /// `WailoAddress`'s question, and building the URL here meant a force-unwrap that crashed the host
    /// app on a mistyped address.
    init(
        hello: Hello,
        url: URL,
        security: WailoSessionSecurity = .open,
        bufferCapacity: Int = 512,
        reconnectDelay: TimeInterval = 2.0,
        pingInterval: TimeInterval = 20.0,
        bodyTimeout: TimeInterval = 10.0
    ) {
        self.url = url
        self.reconnectDelay = reconnectDelay
        self.session = WailoTransportSession(
            hello: hello,
            queue: queue,
            security: security,
            bufferCapacity: bufferCapacity,
            bodyTimeout: bodyTimeout,
            pingInterval: pingInterval
        )
        super.init()
    }

    func start() {
        queue.async {
            guard !self.started else { return }
            self.started = true
            self.suspended = false
            self.connect()
        }
    }

    func suspend() {
        queue.async {
            guard !self.suspended else { return }
            self.suspended = true
            self.teardownConnection(scheduleReconnect: false)
            self.session.unbind()
        }
    }

    func resume() {
        queue.async {
            guard self.started, self.suspended else { return }
            self.suspended = false
            self.connect()
        }
    }

    func onExchange(_ exchange: HttpExchange) { session.onExchange(exchange) }

    func fetchBody(ruleId: String, url: String, method: String, completion: @escaping (WailoMappedResponse?) -> Void) {
        session.fetchBody(ruleId: ruleId, url: url, method: method, completion: completion)
    }

    func pauseRequest(ruleId: String, request: HttpRequest, completion: @escaping (WailoRequestDecision) -> Void) {
        session.pauseRequest(ruleId: ruleId, request: request, completion: completion)
    }

    func pauseResponse(
        ruleId: String,
        request: HttpRequest,
        response: HttpResponse,
        completion: @escaping (WailoResponseDecision) -> Void
    ) {
        session.pauseResponse(ruleId: ruleId, request: request, response: response, completion: completion)
    }

    func stop() {
        queue.async {
            self.started = false
            self.suspended = false
            self.teardownConnection(scheduleReconnect: false)
            self.session.unbind()
            self.urlSession?.invalidateAndCancel()
            self.urlSession = nil
        }
    }

    #if DEBUG
    func stopAndWaitForTeardown() {
        stop()
        queue.sync {}
    }
    #endif

    // MARK: - WailoTransportLink

    func send(_ data: Data, completion: @escaping (Error?) -> Void) {
        guard let task else {
            completion(NSError(domain: "WailoClient", code: -1))
            return
        }
        task.send(.data(data)) { error in completion(error) }
    }

    func startKeepalive(interval: TimeInterval, onFailure: @escaping () -> Void) {
        pingGeneration += 1
        let gen = pingGeneration
        schedulePing(interval: interval, gen: gen, onFailure: onFailure)
    }

    func stopKeepalive() {
        pingGeneration += 1
    }

    func closeLink() {
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
    }

    // MARK: - queue-confined

    private func connect() {
        guard started, !suspended, task == nil else { return }
        generation += 1
        let gen = generation
        let urlSession = self.urlSession ?? URLSession(configuration: transportConfiguration(), delegate: self, delegateQueue: nil)
        self.urlSession = urlSession
        let task = urlSession.webSocketTask(with: url)
        self.task = task
        task.resume()
        listen(task, gen)
        session.bind(to: self)
    }

    private func transportConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = (configuration.protocolClasses ?? []).filter { $0 != WailoURLProtocol.self }
        return configuration
    }

    private func listen(_ task: URLSessionWebSocketTask, _ gen: Int) {
        task.receive { [weak self] result in
            guard let self else { return }
            self.queue.async {
                guard gen == self.generation else { return }
                switch result {
                case let .success(message):
                    if case let .data(data) = message { self.session.receive(data) }
                    self.listen(task, gen)
                case .failure:
                    self.handleDisconnect(gen)
                }
            }
        }
    }

    private func schedulePing(interval: TimeInterval, gen: Int, onFailure: @escaping () -> Void) {
        queue.asyncAfter(deadline: .now() + interval) { [weak self] in
            guard let self, gen == self.pingGeneration, let task = self.task else { return }
            task.sendPing { [weak self] error in
                guard let self else { return }
                self.queue.async {
                    guard gen == self.pingGeneration else { return }
                    if error != nil {
                        onFailure()
                    } else {
                        self.schedulePing(interval: interval, gen: gen, onFailure: onFailure)
                    }
                }
            }
        }
    }

    private func handleDisconnect(_ gen: Int) {
        guard gen == generation else { return }
        teardownConnection(scheduleReconnect: started && !suspended)
    }

    private func teardownConnection(scheduleReconnect: Bool) {
        generation += 1
        session.linkDidClose()
        task?.cancel(with: .abnormalClosure, reason: nil)
        task = nil
        pingGeneration += 1
        guard scheduleReconnect else { return }
        queue.asyncAfter(deadline: .now() + reconnectDelay) { [weak self] in
            self?.connect()
        }
    }

    // MARK: - URLSessionTaskDelegate / URLSessionWebSocketDelegate

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        queue.async {
            guard task === self.task else { return }
            self.handleDisconnect(self.generation)
        }
    }

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
