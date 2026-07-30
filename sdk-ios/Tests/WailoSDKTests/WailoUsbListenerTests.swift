import XCTest
import Network
import Wire
import WailoProtocol
@testable import WailoSDK

/// USB listener: device sends Hello first, streams exchanges, and handles desktop control pushes.
final class WailoUsbListenerTests: XCTestCase {

    func testPort8900IsBindable() throws {
        let server = LoopbackWebSocketServer()
        let port = try server.start(on: WailoUsb.port)
        XCTAssertEqual(port, WailoUsb.port)
        server.stop()
    }

    func testUsbTunnelClientConnectsToLoopbackServerOn8900() throws {
        let server = LoopbackWebSocketServer()
        try server.start(on: WailoUsb.port)
        defer { server.stop() }

        let client = UsbTunnelClient()
        try client.connect(to: WailoUsb.port)
        client.close()
    }

    func testListenerBindsTheConfiguredPortInsteadOfTheDefault() throws {
        WailoHostStore.usbPort = 8917
        defer { WailoHostStore.usbPort = nil }
        XCTAssertEqual(WailoUsb.port, 8917)

        let listener = startUsbListener()
        defer { listener.stopAndWait() }
        XCTAssertEqual(listener.port, 8917)

        let client = UsbTunnelClient()
        try client.connect(to: 8917)
        client.close()
    }

    /// The port has to move without a rebuild, same as the LAN address (ADR-0035).
    func testSetUsbPortRebindsTheListener() throws {
        WailoCoordinator.usbEnabledForTesting = true
        WailoHostStore.host = "127.0.0.1"
        defer {
            WailoCoordinator.usbEnabledForTesting = false
            WailoHostStore.clear()
        }

        Wailo.start(appId: "com.test.usbport", deviceName: "usb-port", alsoLogToConsole: false)
        defer { Wailo.stop() }

        Wailo.setUsbPort(8918)
        XCTAssertEqual(Wailo.usbPort, 8918)
        XCTAssertEqual(Wailo.configuredUsbPort, 8918)

        let client = try connectUsbStudio(port: 8918)
        defer { client.close() }
        let hello = expectation(description: "hello on the reconfigured port")
        client.onEnvelope = { envelope in
            if case .hello = envelope.message { hello.fulfill() }
        }
        wait(for: [hello], timeout: 10)
    }

    func testListenerSendsHelloThenExchange() throws {
        let listener = startUsbListener()
        defer { listener.stopAndWait() }

        let client = UsbTunnelClient()
        try client.connect(to: WailoUsb.port)

        let helloReceived = expectation(description: "hello")
        let exchangeReceived = expectation(description: "exchange")
        client.onEnvelope = { envelope in
            switch envelope.message {
            case let .hello(hello):
                XCTAssertEqual(hello.platform, "ios")
                XCTAssertEqual(hello.app_id, "com.test.usb")
                helloReceived.fulfill()
            case let .exchange(exchange):
                XCTAssertEqual(exchange.id, "usb-e1")
                exchangeReceived.fulfill()
            default:
                break
            }
        }

        wait(for: [helloReceived], timeout: 10)
        guard let transport = listener.debugActiveConnection() else {
            return XCTFail("expected active USB connection")
        }
        transport.onExchange(HttpExchange(
            id: "usb-e1", started_at_epoch_ms: 1, duration_ms: 2, error: "", edited: false
        ))
        wait(for: [exchangeReceived], timeout: 10)
        client.close()
    }

    func testListenerAppliesRuleSetPushedByStudioAndAcksEpoch() throws {
        WailoRuleStore.shared.replace([])
        defer { WailoRuleStore.shared.replace([]) }

        let listener = startUsbListener()
        defer { listener.stopAndWait() }

        let client = UsbTunnelClient()
        try client.connect(to: WailoUsb.port)
        defer { client.close() }

        let helloReceived = expectation(description: "hello")
        let ackReceived = expectation(description: "rule ack")
        client.onEnvelope = { [weak client] envelope in
            switch envelope.message {
            case .hello:
                helloReceived.fulfill()
                let rule = MapLocalRule(id: "usb-rule", enabled: true, url_pattern: "https://usb.test/*") {
                    $0.methods = []
                }
                let push = Envelope { $0.message = .rule_set(RuleSet(epoch: 42) { $0.rules = [rule] }) }
                if let data = try? ProtoEncoder().encode(push) { client?.push(data) }
            case let .rule_ack(ack) where ack.epoch == 42:
                ackReceived.fulfill()
            default:
                break
            }
        }

        wait(for: [helloReceived, ackReceived], timeout: 10)
        XCTAssertNotNil(WailoRuleStore.shared.match(url: "https://usb.test/x", method: "GET"))
    }

    func testUsbTakeoverSuspendsLanAndReportsUsbAddress() throws {
        WailoCoordinator.usbEnabledForTesting = true
        defer { WailoCoordinator.usbEnabledForTesting = false }
        WailoHostStore.host = "127.0.0.1"
        WailoHostStore.port = 8899
        defer { WailoHostStore.host = nil; WailoHostStore.port = nil }

        let lanServer = LoopbackWebSocketServer()
        let lanPort = try lanServer.start()
        defer { lanServer.stop() }

        var lanHellos = 0
        lanServer.onEnvelope = { envelope in
            if case .hello = envelope.message { lanHellos += 1 }
        }

        Wailo.start(appId: "com.test.takeover", deviceName: "takeover", host: "127.0.0.1", port: Int(lanPort), alsoLogToConsole: false)
        defer { Wailo.stop() }

        waitUntil { Wailo.isConnected }
        XCTAssertEqual(Wailo.activeAddress, "127.0.0.1:\(lanPort)")
        XCTAssertEqual(lanHellos, 1)

        let usbClient = try connectUsbStudio()
        defer { usbClient.close() }

        let usbHello = expectation(description: "usb hello")
        usbClient.onEnvelope = { envelope in
            if case .hello = envelope.message { usbHello.fulfill() }
        }
        wait(for: [usbHello], timeout: 10)

        waitUntil(timeout: 5) { Wailo.activeAddress == "usb:\(Wailo.usbPort)" }
        XCTAssertEqual(Wailo.configuredHost, "127.0.0.1")
        XCTAssertEqual(WailoHostStore.port, 8899, "manual host store must survive USB takeover")

        Thread.sleep(forTimeInterval: 0.5)
        XCTAssertEqual(lanHellos, 1, "LAN must not reconnect while USB is active")
    }

    func testUsbDisconnectResumesLanFallback() throws {
        WailoCoordinator.usbEnabledForTesting = true
        defer { WailoCoordinator.usbEnabledForTesting = false }
        WailoHostStore.host = nil
        WailoHostStore.port = nil

        let lanServer = LoopbackWebSocketServer()
        let lanPort = try lanServer.start()
        defer { lanServer.stop() }

        Wailo.start(appId: "com.test.fallback", deviceName: "fallback", host: "127.0.0.1", port: Int(lanPort), alsoLogToConsole: false)
        defer { Wailo.stop() }

        let usbClient = try connectUsbStudio()
        let usbHello = expectation(description: "usb hello")
        usbClient.onEnvelope = { envelope in
            if case .hello = envelope.message { usbHello.fulfill() }
        }
        wait(for: [usbHello], timeout: 10)
        waitUntil(timeout: 5) { Wailo.activeAddress == "usb:\(Wailo.usbPort)" }

        let postFallbackHello = expectation(description: "lan hello after usb drop")
        lanServer.onEnvelope = { envelope in
            if case .hello = envelope.message { postFallbackHello.fulfill() }
        }

        usbClient.close()
        waitUntil(timeout: 10) { Wailo.activeAddress == "127.0.0.1:\(lanPort)" && Wailo.isConnected }
        wait(for: [postFallbackHello], timeout: 10)
    }

    func testUsbDisconnectFailsOpenPendingBodyFetch() throws {
        let listener = startUsbListener()
        defer { listener.stopAndWait() }

        let client = UsbTunnelClient()
        try client.connect(to: WailoUsb.port)
        defer { client.close() }

        let helloReceived = expectation(description: "hello")
        client.onEnvelope = { envelope in
            if case .hello = envelope.message { helloReceived.fulfill() }
        }
        wait(for: [helloReceived], timeout: 10)

        guard let transport = listener.debugActiveConnection() else {
            return XCTFail("expected active USB connection")
        }

        let resolved = expectation(description: "body fetch fail-open")
        transport.fetchBody(ruleId: "r1", url: "https://example.test/", method: "GET") { body in
            XCTAssertNil(body)
            resolved.fulfill()
        }

        client.close()
        wait(for: [resolved], timeout: 10)
    }
}

// MARK: - helpers

private func connectUsbStudio(port: UInt16 = WailoUsb.port) throws -> UsbTunnelClient {
    var lastError: Error?
    for _ in 0..<50 {
        let client = UsbTunnelClient()
        do {
            try client.connect(to: port)
            return client
        } catch {
            lastError = error
            Thread.sleep(forTimeInterval: 0.1)
        }
    }
    throw lastError ?? NSError(domain: "UsbTunnelClient", code: -1)
}

private func startUsbListener() -> WailoUsbListener {
    let hello = Hello(device_name: "usb-test", app_id: "com.test.usb", platform: "ios")
    let listener = WailoUsbListener(hello: hello)
    listener.startAndWait()
    XCTAssertTrue(listener.debugIsListening, "USB listener must bind port \(WailoUsb.port)")
    return listener
}

private func waitUntil(timeout: TimeInterval = 10, _ predicate: @escaping () -> Bool) {
    let deadline = Date().addingTimeInterval(timeout)
    while !predicate(), Date() < deadline {
        Thread.sleep(forTimeInterval: 0.05)
    }
    XCTAssertTrue(predicate())
}

/// Simulates Studio dialling the device's USB listener (receive-only — device sends Hello first).
private final class UsbTunnelClient: NSObject, URLSessionWebSocketDelegate {

    var onEnvelope: ((Envelope) -> Void)?

    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private let ready = DispatchSemaphore(value: 0)
    private var failed = false

    @discardableResult
    func connect(to port: UInt16) throws -> UInt16 {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = (configuration.protocolClasses ?? []).filter { $0 != WailoURLProtocol.self }
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        self.session = session
        let task = session.webSocketTask(with: URL(string: "ws://127.0.0.1:\(port)/")!)
        self.task = task
        task.resume()
        listen()
        guard ready.wait(timeout: .now() + 5) == .success, !failed else {
            throw NSError(domain: "UsbTunnelClient", code: -1)
        }
        return port
    }

    func push(_ data: Data) {
        task?.send(.data(data)) { _ in }
    }

    func close() {
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        session?.invalidateAndCancel()
        session = nil
    }

    private func listen() {
        task?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case let .success(message):
                if case let .data(content) = message,
                   let envelope = try? ProtoDecoder().decode(Envelope.self, from: content) {
                    self.onEnvelope?(envelope)
                }
                self.listen()
            case .failure:
                self.failed = true
                self.ready.signal()
            }
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if error != nil { failed = true; ready.signal() }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        ready.signal()
    }
}
