import Foundation
import WailoProtocol

/// Owns the live transport and decides *which* desktop it dials.
///
/// Resolution order, highest first: the host passed to `Wailo.start`, then the persisted (or
/// `-WailoHost` launch-argument) value in `WailoHostStore`, then whatever Bonjour found, then
/// `localhost`. Discovery runs only when nothing above it is set, so an explicit address is never
/// silently overridden by some other Mac that happens to be advertising — and clearing the override
/// hands control back to discovery without a relaunch.
///
/// An inbound USB listener on loopback (`:8900` unless overridden) runs whenever `start()` is active.
/// When Studio connects
/// over USB it takes precedence: the outbound LAN client is suspended (not torn down — manual host
/// config is preserved) and capture/control routes through the USB session until it drops, then LAN
/// resumes automatically. Traffic never streams over both links at once.
///
/// State is guarded by a lock because discovery and USB callbacks arrive on their own queues;
/// notifications are posted after unlocking so an observer can call back in without deadlocking.
final class WailoCoordinator: @unchecked Sendable {

    static let shared = WailoCoordinator()
    #if DEBUG
    static var usbEnabledForTesting = false
    #endif

    struct Session {
        let appId: String
        let deviceName: String
        let explicitHost: String?
        let explicitPort: Int?
        let maxBodyBytes: Int?
        let alsoLogToConsole: Bool
    }

    /// A resolved target: what is being dialled, plus the URL it forms. Failable because forming that
    /// URL is the step a bad address fails at, and failing it must refuse the address rather than trap
    /// (see `WailoAddress`).
    private struct Endpoint: Equatable {
        let host: String
        let port: Int
        let url: URL

        init?(host: String, port: Int) {
            guard let url = WailoAddress.webSocketURL(host: host, port: port) else { return nil }
            self.host = host
            self.port = port
            self.url = url
        }
    }

    private let lock = NSLock()
    private var session: Session?
    private var client: WailoClient?
    private var endpoint: Endpoint?
    private var discovery: WailoDiscovery?
    private var discovered: [WailoService] = []
    private var usbListener: WailoUsbListener?
    private var usbConnection: WailoUsbConnection?
    private var usbActive = false
    private var connected = false

    // MARK: - lifecycle

    func start(_ session: Session) {
        lock.lock()
        self.session = session
        startUsbListener(session: session)
        apply()
        lock.unlock()
    }

    func stop() {
        lock.lock()
        session = nil
        endpoint = nil
        usbActive = false
        usbConnection?.close()
        usbConnection = nil
        usbListener?.stop()
        usbListener = nil
        client?.stop()
        client = nil
        stopDiscovery()
        let wasConnected = connected
        connected = false
        lock.unlock()

        WailoURLProtocol.sink = nil
        WailoURLProtocol.bodyFetcher = nil
        WailoURLProtocol.breakpointGate = nil
        if wasConnected { post(Wailo.connectionDidChangeNotification) }
    }

    /// Persist a manual override (or `nil` to fall back to discovery) and re-dial immediately.
    ///
    /// Returns whether the address was taken. Text that names nothing diallable changes nothing — a
    /// working address must survive a typo, and above all a value that cannot be dialled must never
    /// reach `UserDefaults`, where every later launch would read it back.
    @discardableResult
    func setHost(_ host: String?, port: Int?) -> Bool {
        lock.lock()
        defer { lock.unlock() }

        if let port, !WailoAddress.portRange.contains(port) {
            print("Wailo: port \(port) is outside 1-65535; keeping the current address")
            return false
        }
        // Clearing the host hands the address back to discovery; a port given alongside still stands,
        // since overriding a discovered desktop's port is the one reason to pass both.
        guard let host else {
            WailoHostStore.host = nil
            WailoHostStore.port = port
            apply()
            return true
        }
        guard let address = WailoAddress(host) else {
            print("Wailo: \"\(host)\" is not an address that can be dialled; keeping the current one")
            return false
        }
        // A port typed into the address itself is the more specific answer, and splitting it out here
        // keeps the store holding a bare host — the shape everything downstream expects.
        WailoHostStore.host = address.host
        WailoHostStore.port = address.port ?? port
        apply()
        return true
    }

    /// Persist the USB listener port and re-bind on it immediately. Studio has to be pointed at the same
    /// number by hand: usbmux forwards to a port, it does not advertise one.
    func setUsbPort(_ port: Int?) {
        lock.lock()
        WailoHostStore.usbPort = port
        if let session { startUsbListener(session: session) }
        lock.unlock()
    }

    // MARK: - status

    var configuredUsbPort: Int? {
        lock.lock(); defer { lock.unlock() }
        return WailoHostStore.usbPort
    }

    /// The port the listener is actually bound to, which lags [configuredUsbPort] until `start()` runs.
    var activeUsbPort: Int {
        lock.lock(); defer { lock.unlock() }
        return Int(usbListener?.port ?? WailoUsb.port)
    }

    var configuredHost: String? {
        lock.lock(); defer { lock.unlock() }
        return session?.explicitHost ?? WailoHostStore.host
    }

    var configuredPort: Int? {
        lock.lock(); defer { lock.unlock() }
        return session?.explicitPort ?? WailoHostStore.port
    }

    var activeAddress: String? {
        lock.lock(); defer { lock.unlock() }
        if usbActive { return "usb:\(usbListener?.port ?? WailoUsb.port)" }
        return endpoint.map { "\($0.host):\($0.port)" }
    }

    var isConnected: Bool {
        lock.lock(); defer { lock.unlock() }
        return connected
    }

    var discoveredDesktops: [WailoService] {
        lock.lock(); defer { lock.unlock() }
        return discovered
    }

    // MARK: - lock-confined

    private func startUsbListener(session: Session) {
        guard Self.usbEnabled else { return }
        usbListener?.stop()
        let hello = Hello(device_name: session.deviceName, app_id: session.appId, platform: Wailo.platform)
        let listener = WailoUsbListener(hello: hello)
        listener.onAccepted = { [weak self] connection in
            self?.usbAccepted(connection)
        }
        listener.onClosed = { [weak self] connection in
            self?.usbClosed(connection)
        }
        usbListener = listener
        listener.start()
    }

    private static var usbEnabled: Bool {
        #if os(iOS)
        true
        #elseif DEBUG
        usbEnabledForTesting
        #else
        false
        #endif
    }

    private func apply() {
        guard let session else { return }

        // Parsed here rather than trusted, because a host reaching this point may never have passed
        // `setHost` — `start(host:)` and the `-WailoHost` launch argument both land straight here. An
        // unusable one falls through to discovery instead of taking the app down with it.
        let pinned = (session.explicitHost ?? WailoHostStore.host).flatMap(WailoAddress.init)
        if pinned == nil { startDiscovery() } else { stopDiscovery() }

        let forcedPort = session.explicitPort ?? WailoHostStore.port
        let host: String
        let port: Int
        if let pinned {
            host = pinned.host
            port = pinned.port ?? forcedPort ?? Wailo.defaultPort
        } else if let found = discovered.first {
            host = found.host
            port = forcedPort ?? found.port
        } else {
            host = Wailo.defaultHost
            port = forcedPort ?? Wailo.defaultPort
        }

        guard let target = Endpoint(host: host, port: port) else {
            print("Wailo: cannot dial \(host):\(port); leaving the connection as it is")
            return
        }
        guard target != endpoint || client == nil else { return }
        endpoint = target
        rebuildClient(session: session, target: target)
    }

    private func rebuildClient(session: Session, target: Endpoint) {
        client?.stop()

        let hello = Hello(device_name: session.deviceName, app_id: session.appId, platform: Wailo.platform)
        let webSocket = WailoClient(hello: hello, url: target.url)
        webSocket.onConnectionChange = { [weak self, weak webSocket] isConnected in
            guard let self, let webSocket else { return }
            self.lanConnectionChanged(isConnected, from: webSocket)
        }
        webSocket.start()
        client = webSocket

        if !usbActive {
            wireTransport(webSocket, session: session)
        } else {
            webSocket.suspend()
        }
    }

    private func wireTransport(
        _ transport: CaptureSink & WailoBodyFetcher & WailoBreakpointGate,
        session: Session
    ) {
        let sink: CaptureSink = session.alsoLogToConsole ? transport + ConsoleSink() : transport
        WailoURLProtocol.sink = sink
        WailoURLProtocol.bodyFetcher = transport
        WailoURLProtocol.breakpointGate = transport
        WailoURLProtocol.maxBodyBytes = session.maxBodyBytes
    }

    private func startDiscovery() {
        guard discovery == nil else { return }
        let browser = WailoDiscovery()
        browser.onChange = { [weak self] services in self?.discoveryChanged(services) }
        discovery = browser
        browser.start()
    }

    private func stopDiscovery() {
        discovery?.stop()
        discovery = nil
        discovered = []
    }

    // MARK: - callbacks

    private func discoveryChanged(_ services: [WailoService]) {
        lock.lock()
        discovered = services
        apply()
        lock.unlock()
        post(Wailo.discoveryDidChangeNotification)
    }

    private func usbAccepted(_ connection: WailoUsbConnection) {
        lock.lock()
        guard let session else { lock.unlock(); return }
        usbConnection = connection
        usbActive = true
        client?.suspend()
        connection.onConnectionChange = { [weak self, weak connection] isConnected in
            guard let self, let connection else { return }
            self.usbConnectionChanged(isConnected, from: connection)
        }
        wireTransport(connection, session: session)
        lock.unlock()
    }

    private func usbClosed(_ connection: WailoUsbConnection) {
        lock.lock()
        guard usbConnection === connection else { lock.unlock(); return }
        usbConnection = nil
        usbActive = false
        if let session, let client {
            wireTransport(client, session: session)
            client.resume()
        }
        let wasConnected = connected
        connected = false
        lock.unlock()
        if wasConnected { post(Wailo.connectionDidChangeNotification) }
    }

    private func lanConnectionChanged(_ isConnected: Bool, from source: WailoClient) {
        lock.lock()
        guard client === source, !usbActive, connected != isConnected else { lock.unlock(); return }
        connected = isConnected
        lock.unlock()
        post(Wailo.connectionDidChangeNotification)
    }

    private func usbConnectionChanged(_ isConnected: Bool, from source: WailoUsbConnection) {
        lock.lock()
        guard usbConnection === source, usbActive, connected != isConnected else { lock.unlock(); return }
        connected = isConnected
        lock.unlock()
        post(Wailo.connectionDidChangeNotification)
    }

    private func post(_ name: Notification.Name) {
        NotificationCenter.default.post(name: name, object: nil)
    }
}
