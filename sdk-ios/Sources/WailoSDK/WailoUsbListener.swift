import Foundation
import Network
import WailoProtocol
import Wire
#if canImport(UIKit)
import UIKit
#endif

/// Loopback port for inbound USB-tunnelled Studio connections. The default is distinct from LAN `8899`
/// so the outbound LAN client never dials its own listener when Bonjour/localhost resolution overlaps.
///
/// Overridable because USB has no discovery: if the default collides with something else in the host app,
/// the only way out is to move both ends, and a rebuild to do it defeats ADR-0035.
enum WailoUsb {
    static let defaultPort: UInt16 = 8900

    /// The port the next bind will use — the persisted override (or `-WailoUsbPort` launch argument),
    /// else the default. Read [WailoUsbListener.port] for the one actually bound.
    static var port: UInt16 {
        WailoHostStore.usbPort.flatMap(UInt16.init(exactly:)) ?? defaultPort
    }
}

/// Accepts a single inbound WebSocket from Studio over a USB port-forward (usbmux). Network.framework
/// cannot bind an NWListener to the loopback interface explicitly, so non-loopback peers are rejected
/// before they become active.
///
/// The listener re-binds itself after a drop. iOS tears down an app's network resources while it is
/// suspended, which kills the listener for good; without re-arming, USB stays dead until the process is
/// restarted even though the cable and Studio are both fine.
final class WailoUsbListener: @unchecked Sendable {

    var onAccepted: ((WailoUsbConnection) -> Void)?
    var onClosed: ((WailoUsbConnection) -> Void)?

    /// Fixed for the listener's lifetime: a re-bind on a live listener would strand Studio on the old
    /// port, so changing the port replaces the listener instead.
    let port: UInt16

    private let hello: Hello
    private let queue = DispatchQueue(label: "com.venbiasa.wailo.usb.listener")
    private let callbackQueue = DispatchQueue(label: "com.venbiasa.wailo.usb.listener.callback")
    private var listener: NWListener?
    private var active: WailoUsbConnection?
    private var foregroundObserver: NSObjectProtocol?
    /// Identifies the bind a state callback belongs to, so a late `.cancelled` from a listener we already
    /// replaced (or stopped) cannot resurrect one.
    private var bindGeneration = 0

    init(hello: Hello, port: UInt16 = WailoUsb.port) {
        self.hello = hello
        self.port = port
    }

    deinit {
        if let foregroundObserver {
            NotificationCenter.default.removeObserver(foregroundObserver)
        }
    }

    func start() {
        queue.async {
            self.observeForeground()
            guard self.listener == nil else { return }
            self.startLocked()
        }
    }

    #if DEBUG
    func startAndWait() {
        guard listener == nil else { return }
        startLocked()
    }

    func stopAndWait() {
        stop()
        queue.sync {}
    }

    func debugActiveConnection() -> WailoUsbConnection? {
        queue.sync { active }
    }

    var debugIsListening: Bool { listener != nil }
    #endif

    func stop() {
        queue.async {
            if let observer = self.foregroundObserver {
                NotificationCenter.default.removeObserver(observer)
                self.foregroundObserver = nil
            }
            self.active?.close()
            self.active = nil
            self.listener?.cancel()
            self.listener = nil
        }
    }

    // MARK: - queue-confined

    /// Returning to the foreground is the other half of the re-arm: a listener that died while the app
    /// was suspended reports its failure only once the process is scheduled again, and on some resumes
    /// not at all.
    private func observeForeground() {
        #if canImport(UIKit)
        guard foregroundObserver == nil else { return }
        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            guard let self else { return }
            self.queue.async {
                guard self.listener == nil else { return }
                self.startLocked()
            }
        }
        #endif
    }

    /// Delayed rather than immediate so a listener that fails the instant it goes ready cannot spin this
    /// into a bind loop, and so a burst of state callbacks collapses into one re-bind.
    private func rebind(generation: Int) {
        queue.asyncAfter(deadline: .now() + 0.5) {
            guard generation == self.bindGeneration, self.listener != nil else { return }
            self.active?.close()
            self.active = nil
            self.listener?.cancel()
            self.listener = nil
            self.startLocked()
        }
    }

    private func startLocked() {
        var lastError: Error?
        for _ in 0..<30 {
            do {
                try bindListener()
                return
            } catch {
                lastError = error
                Thread.sleep(forTimeInterval: 0.1)
            }
        }
        _ = lastError
    }

    private func bindListener() throws {
        let webSocket = NWProtocolWebSocket.Options()
        webSocket.autoReplyPing = true
        let parameters = NWParameters(tls: nil, tcp: NWProtocolTCP.Options())
        parameters.allowLocalEndpointReuse = true
        parameters.defaultProtocolStack.applicationProtocols.insert(webSocket, at: 0)
        // Loopback-only via requiredInterfaceType is rejected on macOS (EINVAL); Studio reaches this
        // port only through usbmux forwarding to device-localhost, not over the LAN.

        let endpointPort = NWEndpoint.Port(rawValue: port)!
        let listener = try NWListener(using: parameters, on: endpointPort)

        bindGeneration += 1
        let generation = bindGeneration
        let settled = DispatchSemaphore(value: 0)
        var failed = false
        var ready = false
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                ready = true
                settled.signal()
            case .failed, .cancelled:
                if ready {
                    self?.rebind(generation: generation)
                } else {
                    failed = true
                    settled.signal()
                }
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.queue.async { self?.accept(connection) }
        }
        listener.start(queue: callbackQueue)
        guard settled.wait(timeout: .now() + 2) == .success, !failed else {
            listener.cancel()
            throw UsbBindError.notReady
        }
        self.listener = listener
    }

    private func accept(_ connection: NWConnection) {
        guard Self.isLoopback(connection.endpoint) else {
            connection.cancel()
            return
        }
        active?.close()
        let transport = WailoUsbConnection(connection: connection, hello: hello, queue: queue)
        transport.onClosed = { [weak self] closed in
            guard let self else { return }
            self.queue.async {
                guard self.active === closed else { return }
                self.active = nil
                self.onClosed?(closed)
            }
        }
        active = transport
        onAccepted?(transport)
        transport.start()
    }

    private static func isLoopback(_ endpoint: NWEndpoint) -> Bool {
        guard case let .hostPort(host, _) = endpoint else { return false }
        let address = host.debugDescription.lowercased().split(separator: "%", maxSplits: 1).first.map(String.init)
        return address == "127.0.0.1" || address == "::1" || address == "localhost"
    }
}

private enum UsbBindError: Error { case notReady }

/// One accepted inbound WebSocket; device sends Hello first, then mirrors LAN session semantics.
final class WailoUsbConnection: CaptureSink, WailoBodyFetcher, WailoBreakpointGate, WailoTransportLink, @unchecked Sendable {

    var onClosed: ((WailoUsbConnection) -> Void)?
    var onConnectionChange: ((Bool) -> Void)?

    private let connection: NWConnection
    private let queue: DispatchQueue
    private let session: WailoTransportSession
    private var closed = false
    private var ready = false
    private var pingGeneration = 0

    init(connection: NWConnection, hello: Hello, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
        self.session = WailoTransportSession(hello: hello, queue: queue)
        session.onConnectionChange = { [weak self] connected in
            guard let self else { return }
            if !connected { self.queue.async { self.closeIfNeeded(notify: true) } }
            self.onConnectionChange?(connected)
        }
    }

    func start() {
        queue.async {
            guard !self.closed else { return }
            self.connection.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    self.ready = true
                    self.session.bind(to: self)
                    self.receive()
                case .failed, .cancelled:
                    self.queue.async { self.closeIfNeeded(notify: true) }
                default:
                    break
                }
            }
            self.connection.start(queue: self.queue)
            self.queue.asyncAfter(deadline: .now() + 5) {
                if !self.ready { self.closeIfNeeded(notify: true) }
            }
        }
    }

    func close() {
        queue.async { self.closeIfNeeded(notify: false) }
    }

    #if DEBUG
    func closeAndWait() {
        close()
        queue.sync {}
        session.unbindAndWait()
    }
    #endif

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

    // MARK: - WailoTransportLink

    func send(_ data: Data, completion: @escaping (Error?) -> Void) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "frame", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { error in
            completion(error)
        })
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
        connection.cancel()
    }

    // MARK: - queue-confined

    private func receive() {
        connection.receiveMessage { [weak self] content, _, _, error in
            guard let self else { return }
            if let content { self.session.receive(content) }
            if error != nil {
                self.closeIfNeeded(notify: true)
            } else if !self.closed {
                self.receive()
            }
        }
    }

    private func schedulePing(interval: TimeInterval, gen: Int, onFailure: @escaping () -> Void) {
        queue.asyncAfter(deadline: .now() + interval) { [weak self] in
            guard let self, gen == self.pingGeneration, !self.closed else { return }
            let metadata = NWProtocolWebSocket.Metadata(opcode: .ping)
            let context = NWConnection.ContentContext(identifier: "ping", metadata: [metadata])
            self.connection.send(content: nil, contentContext: context, isComplete: true, completion: .contentProcessed { error in
                self.queue.async {
                    guard gen == self.pingGeneration else { return }
                    if error != nil {
                        onFailure()
                    } else {
                        self.schedulePing(interval: interval, gen: gen, onFailure: onFailure)
                    }
                }
            })
        }
    }

    private func closeIfNeeded(notify: Bool) {
        guard !closed else { return }
        closed = true
        pingGeneration += 1
        session.unbind()
        connection.cancel()
        if notify { onClosed?(self) }
    }
}
