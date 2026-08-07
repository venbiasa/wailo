import Network
import Wire
import WailoProtocol
import XCTest

enum LoopbackBindError: Error { case notReady }

/// Binds a WebSocket listener on an ephemeral port, reads the assigned port, then releases it so a
/// `WailoClient` can target a port that is *not yet* accepting — the "desktop starts late" setup.
func reserveEphemeralPort() throws -> UInt16 {
    let probe = LoopbackWebSocketServer()
    let port = try probe.start()
    probe.stop()
    return port
}

/// What the coordinator hands `WailoClient` for a loopback server. Force-unwrapped because the literal
/// is fixed here; in the SDK the same construction is `WailoAddress`'s job precisely because a typed
/// address is not.
func loopbackURL(_ port: UInt16) -> URL {
    URL(string: "ws://127.0.0.1:\(port)/")!
}

/// Minimal WebSocket server for tests. Accepts one connection and decodes each binary frame as an
/// `Envelope`, exactly as the real engine does.
final class LoopbackWebSocketServer {

    var onEnvelope: ((Envelope) -> Void)?

    private let queue = DispatchQueue(label: "com.venbiasa.wailo.test.loopback")
    private var listener: NWListener?
    private var connections: [NWConnection] = []

    @discardableResult
    func start(on port: UInt16? = nil) throws -> UInt16 {
        var lastError: Error?
        for _ in 0..<30 {
            do {
                return try bind(on: port)
            } catch {
                lastError = error
                Thread.sleep(forTimeInterval: 0.1)
            }
        }
        throw XCTSkip("WebSocket listener did not become ready: \(String(describing: lastError))")
    }

    private func bind(on port: UInt16?) throws -> UInt16 {
        let webSocket = NWProtocolWebSocket.Options()
        webSocket.autoReplyPing = true
        let parameters = NWParameters(tls: nil, tcp: NWProtocolTCP.Options())
        parameters.allowLocalEndpointReuse = true
        parameters.defaultProtocolStack.applicationProtocols.insert(webSocket, at: 0)

        let listener: NWListener
        if let port, let nwPort = NWEndpoint.Port(rawValue: port) {
            listener = try NWListener(using: parameters, on: nwPort)
        } else {
            listener = try NWListener(using: parameters)
        }

        let settled = DispatchSemaphore(value: 0)
        var boundPort: UInt16 = 0
        var failed = false
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                boundPort = listener.port?.rawValue ?? 0
                settled.signal()
            case .failed, .cancelled:
                failed = true
                settled.signal()
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            self.connections.append(connection)
            connection.start(queue: self.queue)
            self.receive(on: connection)
        }
        listener.start(queue: queue)

        guard settled.wait(timeout: .now() + 2) == .success, !failed, boundPort != 0 else {
            listener.cancel()
            throw LoopbackBindError.notReady
        }
        self.listener = listener
        return boundPort
    }

    func stop() {
        listener?.cancel()
        connections.forEach { $0.cancel() }
        connections = []
    }

    /// Hangs up on the client but keeps accepting, which is what a device losing the link — iOS tearing
    /// an app's sockets down as it suspends — looks like from this side.
    func dropConnections() {
        connections.forEach { $0.cancel() }
        connections = []
    }

    func push(_ data: Data) {
        guard let connection = connections.last else { return }
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "push", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { _ in })
    }

    private func receive(on connection: NWConnection) {
        connection.receiveMessage { [weak self] content, _, _, error in
            guard let self else { return }
            if let content, let envelope = try? ProtoDecoder().decode(Envelope.self, from: content) {
                self.onEnvelope?(envelope)
            }
            if error == nil {
                self.receive(on: connection)
            }
        }
    }
}
