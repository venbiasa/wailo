import Foundation
import WailoProtocol
import Wire

/// Streams captured exchanges to the desktop over a WebSocket. The Swift port of `core.WailoClient`:
/// `onExchange` only enqueues; a background loop drains the buffer and reconnects whenever the
/// desktop isn't up yet. On overflow the oldest is dropped so a slow/absent desktop never blocks or
/// grows memory without bound in the host app.
///
/// State is confined to a private serial queue, so the class is safe to call from any thread.
final class WailoClient: CaptureSink, @unchecked Sendable {

    private let hello: Hello
    private let url: URL
    private let bufferCapacity: Int
    private let reconnectDelay: TimeInterval

    private let queue = DispatchQueue(label: "com.venbiasa.wailo.client")
    private let session: URLSession = {
        // The transport's own session must never be intercepted. ws:// is skipped by the protocol's
        // scheme check anyway, but keep the interceptor off this config as defense in depth.
        URLSession(configuration: .ephemeral)
    }()

    private var task: URLSessionWebSocketTask?
    private var buffer: [HttpExchange] = []
    private var started = false
    private var connected = false
    private var sending = false

    init(
        hello: Hello,
        host: String,
        port: Int,
        bufferCapacity: Int = 512,
        reconnectDelay: TimeInterval = 2.0
    ) {
        self.hello = hello
        self.url = URL(string: "ws://\(host):\(port)/")!
        self.bufferCapacity = bufferCapacity
        self.reconnectDelay = reconnectDelay
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
            self.task?.cancel(with: .goingAway, reason: nil)
            self.task = nil
        }
    }

    // MARK: - queue-confined

    private func connect() {
        guard started, task == nil else { return }
        let task = session.webSocketTask(with: url)
        self.task = task
        task.resume()
        listen(task)
        // Open with Hello; success flips `connected` and drains whatever is buffered.
        send(Envelope { $0.message = .hello(hello) }, isHandshake: true)
    }

    /// Receives (and discards) frames purely to observe connection failure and trigger reconnect.
    private func listen(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            guard let self else { return }
            self.queue.async {
                guard task === self.task else { return }
                switch result {
                case .success:
                    self.listen(task)
                case .failure:
                    self.handleDisconnect()
                }
            }
        }
    }

    private func pump() {
        guard connected, !sending, task != nil, !buffer.isEmpty else { return }
        sending = true
        let next = buffer.removeFirst()
        send(Envelope { $0.message = .exchange(next) }, isHandshake: false)
    }

    private func send(_ envelope: Envelope, isHandshake: Bool) {
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
                guard task === self.task else { return }
                if error != nil {
                    self.handleDisconnect()
                    return
                }
                if isHandshake {
                    self.connected = true
                } else {
                    self.sending = false
                }
                self.pump()
            }
        }
    }

    private func handleDisconnect() {
        connected = false
        sending = false
        task?.cancel(with: .abnormalClosure, reason: nil)
        task = nil
        guard started else { return }
        // Desktop unreachable; back off and retry. Buffered exchanges wait (drop-oldest on overflow).
        queue.asyncAfter(deadline: .now() + reconnectDelay) { [weak self] in
            self?.connect()
        }
    }
}
