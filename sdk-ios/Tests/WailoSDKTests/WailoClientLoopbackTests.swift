import XCTest
import Network
import Wire
import WailoProtocol
@testable import WailoSDK

/// End-to-end transport check: stands up a real WebSocket server (Network.framework) and asserts
/// `WailoClient` opens with a decodable `Hello` and then streams a buffered exchange — the same
/// bytes the Kotlin `engine` decodes. The Swift analog of the desktop client<->server loopback test.
final class WailoClientLoopbackTests: XCTestCase {

    func testStreamsHelloThenExchange() throws {
        let server = LoopbackWebSocketServer()
        let port = try server.start()

        let helloReceived = expectation(description: "hello")
        let exchangeReceived = expectation(description: "exchange")
        server.onEnvelope = { envelope in
            switch envelope.message {
            case let .hello(hello):
                XCTAssertEqual(hello.platform, "ios")
                XCTAssertEqual(hello.app_id, "com.test")
                helloReceived.fulfill()
            case let .exchange(exchange):
                XCTAssertEqual(exchange.id, "e1")
                exchangeReceived.fulfill()
            case .none:
                break
            }
        }

        let client = WailoClient(
            hello: Hello(device_name: "test", app_id: "com.test", platform: "ios"),
            host: "127.0.0.1",
            port: Int(port)
        )
        client.start()
        // Buffered before the socket is up; must be delivered after the Hello once connected.
        client.onExchange(HttpExchange(id: "e1", started_at_epoch_ms: 1, duration_ms: 2, error: ""))

        wait(for: [helloReceived, exchangeReceived], timeout: 10)
        client.stop()
        server.stop()
    }
}

/// Minimal WebSocket server for tests. Accepts one connection and decodes each binary frame as an
/// `Envelope`, exactly as the real engine does.
private final class LoopbackWebSocketServer {

    var onEnvelope: ((Envelope) -> Void)?

    private let queue = DispatchQueue(label: "com.venbiasa.wailo.test.loopback")
    private var listener: NWListener?
    private var connections: [NWConnection] = []

    func start() throws -> UInt16 {
        let webSocket = NWProtocolWebSocket.Options()
        webSocket.autoReplyPing = true
        let parameters = NWParameters(tls: nil, tcp: NWProtocolTCP.Options())
        parameters.defaultProtocolStack.applicationProtocols.insert(webSocket, at: 0)

        let listener = try NWListener(using: parameters)
        self.listener = listener

        let ready = DispatchSemaphore(value: 0)
        var boundPort: UInt16 = 0
        listener.stateUpdateHandler = { state in
            if case .ready = state {
                boundPort = listener.port?.rawValue ?? 0
                ready.signal()
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            self.connections.append(connection)
            connection.start(queue: self.queue)
            self.receive(on: connection)
        }
        listener.start(queue: queue)

        guard ready.wait(timeout: .now() + 5) == .success, boundPort != 0 else {
            throw XCTSkip("WebSocket listener did not become ready")
        }
        return boundPort
    }

    func stop() {
        listener?.cancel()
        connections.forEach { $0.cancel() }
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
