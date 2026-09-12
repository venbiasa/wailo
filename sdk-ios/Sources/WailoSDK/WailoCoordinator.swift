import Foundation
import WailoProtocol

/// A remembered address now answered by a different identity (ADR-0040). Surfaced rather than acted
/// on: this is either a machine that changed hands or someone standing in the path, and from the
/// device's side the two are indistinguishable.
public struct WailoIdentityChange: Equatable, Sendable {
    public let host: String
    /// The `sid` this device pinned when it first trusted this address.
    public let expected: String
    public let actual: String
}

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
    /// Treats a loopback address as WiFi, so the guarded path can be driven from a test.
    ///
    /// The only peer a unit test can dial is this machine, and loopback is precisely the case the
    /// handshake is skipped for — so without this the whole of ADR-0039/0040's device half is left to
    /// manual smoke, which is how a reconnect after a first contact could break unnoticed.
    static var forcesWifiHandshakeForTesting = false
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
        let expectedStudioId: String?
        let url: URL

        init?(host: String, port: Int, expectedStudioId: String? = nil) {
            guard let url = WailoAddress.webSocketURL(host: host, port: port) else { return nil }
            self.host = host
            self.port = port
            self.expectedStudioId = expectedStudioId
            self.url = url
        }
    }

    /// How a resolved endpoint may be talked to (ADR-0039, revised by ADR-0040).
    private enum Trust {
        /// Loopback: Simulator, `adb reverse`, the usbmux tunnel. The kernel already guarantees the
        /// peer is this machine, so there is nothing a key would add.
        case loopback
        /// WiFi. Whatever the device knows about this peer, which decides what it can demand.
        case wifi(WailoHandshake.Trust)
        /// WiFi that must not be dialled: an address nobody chose, or one whose identity changed.
        /// Sending even a `Hello` would name the app to whatever answered.
        case blocked
    }

    /// The trust one client's *next* handshake will use.
    ///
    /// A box because trust is not fixed for a client's lifetime the way its address is. `WailoClient`
    /// reconnects on its own without going back through `apply`, and a first contact that succeeds
    /// changes what the peer will accept: Studio now holds a key for this device and demands proof of
    /// it from the very next connection. Handing the factory a captured value meant every reconnect
    /// after a trust-on-first-use pairing re-introduced the device as a stranger, computed the auth key
    /// without the key it had just stored, and failed authentication — silently, forever, until Studio
    /// forgot the device and became lenient again.
    ///
    /// Owned by the client it was built for rather than by the coordinator, so a late callback from a
    /// client that has already been replaced cannot upgrade the live one.
    private final class TrustBox: @unchecked Sendable {

        private let lock = NSLock()
        private var trust: WailoHandshake.Trust

        init(_ trust: WailoHandshake.Trust) {
            self.trust = trust
        }

        var current: WailoHandshake.Trust {
            lock.lock()
            defer { lock.unlock() }
            return trust
        }

        func replace(with trust: WailoHandshake.Trust) {
            lock.lock()
            defer { lock.unlock() }
            self.trust = trust
        }
    }

    private let lock = NSLock()
    /// Read through rather than captured: this coordinator is a process-lifetime singleton, so a stored
    /// reference would outlive any replacement of the shared store.
    private var pairingStore: WailoPairingStore { WailoPairingStore.shared }
    private var session: Session?
    private var client: WailoClient?
    private var endpoint: Endpoint?
    private var discovery: WailoDiscovery?
    private var discovered: [WailoService] = []
    /// Set by `pair`, cleared once the handshake it authorises succeeds. Outranks everything else so a
    /// freshly scanned QR connects immediately rather than waiting for discovery to catch up.
    private var pendingInvite: WailoPairingInvite?
    private var refusal: String?
    private var identityChange: WailoIdentityChange?
    /// Hosts the user has said "yes, that new identity is fine" about, cleared once one is taken. Kept
    /// in memory only: accepting a changed identity is a decision about right now, and a stale one
    /// surviving a relaunch would silently widen it.
    private var acceptedIdentityChanges: Set<String> = []
    /// Forget is a stop action; only a later explicit Connect/pair action may resume dialling.
    private var connectionSuppressedAfterForget = false
    private var usbListener: WailoUsbListener?
    private var usbConnection: WailoUsbConnection?
    private var usbActive = false
    private var connected = false
    private var phase: WailoConnectionPhase = .stopped
    private var connectedStudioId: String?
    private var forgetting: Set<String> = []
    private var forgettingAll = false

    // MARK: - lifecycle

    func start(_ session: Session) {
        lock.lock()
        self.session = session
        startUsbListener(session: session)
        let connectionChanged = apply()
        lock.unlock()
        if connectionChanged { post(Wailo.connectionDidChangeNotification) }
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
        connectedStudioId = nil
        phase = .stopped
        lock.unlock()

        WailoURLProtocol.sink = nil
        WailoURLProtocol.bodyFetcher = nil
        WailoURLProtocol.breakpointGate = nil
        WailoURLProtocol.scriptTransformer = nil
        if wasConnected { post(Wailo.connectionDidChangeNotification) }
    }

    /// Persist a manual override (or `nil` to fall back to discovery) and re-dial immediately.
    ///
    /// Returns whether the address was taken. Text that names nothing diallable changes nothing — a
    /// working address must survive a typo, and above all a value that cannot be dialled must never
    /// reach `UserDefaults`, where every later launch would read it back.
    @discardableResult
    func setHost(_ host: String?, port: Int?, expectedStudioId: String? = nil) -> Bool {
        var connectionChanged = false
        lock.lock()
        defer {
            lock.unlock()
            if connectionChanged { post(Wailo.connectionDidChangeNotification) }
        }

        if let port, !WailoAddress.portRange.contains(port) {
            print("Wailo: port \(port) is outside 1-65535; keeping the current address")
            return false
        }
        // Clearing the host hands the address back to discovery; a port given alongside still stands,
        // since overriding a discovered desktop's port is the one reason to pass both.
        guard let host else {
            connectionSuppressedAfterForget = false
            WailoHostStore.host = nil
            WailoHostStore.port = port
            WailoHostStore.expectedStudioId = nil
            identityChange = nil
            connectionChanged = apply()
            return true
        }
        guard let address = WailoAddress(host) else {
            print("Wailo: \"\(host)\" is not an address that can be dialled; keeping the current one")
            return false
        }
        connectionSuppressedAfterForget = false
        // A warning about a different address has nothing to say about this one, and leaving it up
        // would attach it to whatever the user typed next.
        if identityChange?.host != address.host { identityChange = nil }
        // A port typed into the address itself is the more specific answer, and splitting it out here
        // keeps the store holding a bare host — the shape everything downstream expects.
        WailoHostStore.host = address.host
        WailoHostStore.port = address.port ?? port
        WailoHostStore.expectedStudioId = expectedStudioId
        connectionChanged = apply()
        return true
    }

    // MARK: - pairing

    /// Take a scanned QR or a typed code and dial the Studio it names. The key is derived here and
    /// only persisted once the handshake proves the peer really is that Studio, so a mistyped code
    /// leaves nothing behind.
    func pair(_ invite: WailoPairingInvite) {
        lock.lock()
        connectionSuppressedAfterForget = false
        pendingInvite = invite
        refusal = nil
        // A pinned manual address would otherwise outrank the invite we were just handed.
        WailoHostStore.host = nil
        WailoHostStore.expectedStudioId = nil
        apply()
        lock.unlock()
        post(Wailo.connectionDidChangeNotification)
    }

    func forget(studioId: String) {
        lock.lock()
        guard !forgettingAll, !forgetting.contains(studioId) else { lock.unlock(); return }
        connectionSuppressedAfterForget = true
        let live = connected && connectedStudioId == studioId ? client : nil
        forgetting.insert(studioId)
        lock.unlock()

        if let live {
            live.revoke { [weak self] _ in self?.finishForget(studioId: studioId) }
        } else {
            finishForget(studioId: studioId)
        }
    }

    private func finishForget(studioId: String) {
        lock.lock()
        guard forgetting.remove(studioId) != nil else { lock.unlock(); return }
        connectionSuppressedAfterForget = true
        unpinAddress(of: pairingStore.pairing(studioId: studioId).map { [$0] } ?? [])
        pairingStore.forget(studioId: studioId)
        if pendingInvite?.studioId == studioId { pendingInvite = nil }
        refusal = nil
        if identityChange?.expected == studioId {
            if let host = identityChange?.host { acceptedIdentityChanges.remove(host) }
            identityChange = nil
        }
        apply()
        lock.unlock()
        post(Wailo.connectionDidChangeNotification)
    }

    func forgetAllPairings() {
        lock.lock()
        guard !forgettingAll else { lock.unlock(); return }
        forgettingAll = true
        connectionSuppressedAfterForget = true
        let live = connected && connectedStudioId != nil ? client : nil
        lock.unlock()

        if let live {
            live.revoke { [weak self] _ in self?.finishForgetAll() }
        } else {
            finishForgetAll()
        }
    }

    private func finishForgetAll() {
        lock.lock()
        guard forgettingAll else { lock.unlock(); return }
        forgettingAll = false
        connectionSuppressedAfterForget = true
        forgetting.removeAll()
        unpinAddress(of: pairingStore.all)
        pairingStore.forgetAll()
        pendingInvite = nil
        refusal = nil
        acceptedIdentityChanges.removeAll()
        identityChange = nil
        apply()
        lock.unlock()
        post(Wailo.connectionDidChangeNotification)
    }

    /// Forgetting a desktop has to let go of its address too.
    ///
    /// A pinned address outranks everything else in `apply`, and a first contact at an address the user
    /// named is taken at its word (ADR-0040) — so dropping only the key would re-trust the same desktop
    /// on the next dial two seconds later, and Forget would read as a button that does nothing. The
    /// coordinator also stays stopped after deletion; discovery resumes only when the user explicitly
    /// chooses Connect again (ADR-0060).
    private func unpinAddress(of pairings: [WailoPairing]) {
        guard let pinned = WailoHostStore.host else { return }
        if pairings.contains(where: { $0.lastHost == pinned }) {
            WailoHostStore.host = nil
            WailoHostStore.expectedStudioId = nil
        }
    }

    /// Clears the "this Studio does not know you" latch so the device tries once more. Deliberately a
    /// human action because repeated signed refusals still need a different action, usually Forget.
    func retryAfterRefusal() {
        lock.lock()
        refusal = nil
        for var pairing in pairingStore.all where pairing.refused {
            pairing.refused = false
            pairingStore.save(pairing)
        }
        apply()
        lock.unlock()
        post(Wailo.connectionDidChangeNotification)
    }

    var pairings: [WailoPairing] {
        lock.lock(); defer { lock.unlock() }
        return pairingStore.all
    }

    var refusalMessage: String? {
        lock.lock(); defer { lock.unlock() }
        return refusal
    }

    var pendingIdentityChange: WailoIdentityChange? {
        lock.lock(); defer { lock.unlock() }
        return identityChange
    }

    /// Take the new identity at that address: drop the old pin and let the next connection establish
    /// a fresh one. Only ever called from an explicit user action, which is what separates "my Mac
    /// changed hands" from "someone is in the path" — nothing on the wire can tell them apart.
    func acceptIdentityChange() {
        lock.lock()
        guard let change = identityChange else { lock.unlock(); return }
        acceptedIdentityChanges.insert(change.host)
        identityChange = nil
        apply()
        lock.unlock()
        post(Wailo.connectionDidChangeNotification)
    }

    /// Leave the pin alone and stop dialling that address.
    func rejectIdentityChange() {
        lock.lock()
        guard let change = identityChange else { lock.unlock(); return }
        acceptedIdentityChanges.remove(change.host)
        identityChange = nil
        // Hand the address back to discovery, which only reconnects to identities already known.
        if WailoHostStore.host == change.host {
            WailoHostStore.host = nil
            WailoHostStore.expectedStudioId = nil
        }
        apply()
        lock.unlock()
        post(Wailo.connectionDidChangeNotification)
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

    var connectionPhase: WailoConnectionPhase {
        lock.lock(); defer { lock.unlock() }
        return phase
    }

    var discoveredDesktops: [WailoService] {
        lock.lock(); defer { lock.unlock() }
        return discovered
    }

    func refreshDiscovery() {
        lock.lock()
        let discovery = self.discovery
        lock.unlock()
        discovery?.refresh()
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

    @discardableResult
    private func apply() -> Bool {
        guard let session else { return false }

        if connectionSuppressedAfterForget {
            let wasConnected = connected
            // Forget suppresses dialling, not enumeration. Typed-code recovery needs a discovered
            // Studio to aim at, and merely listing an unauthenticated advertisement grants no trust.
            startDiscovery()
            client?.stop()
            client = nil
            endpoint = nil
            connected = false
            connectedStudioId = nil
            phase = identityChange == nil ? .stopped : .identityMismatch
            return wasConnected
        }

        // Parsed here rather than trusted, because a host reaching this point may never have passed
        // `setHost` — `start(host:)` and the `-WailoHost` launch argument both land straight here. An
        // unusable one falls through to discovery instead of taking the app down with it.
        let pinned = (session.explicitHost ?? WailoHostStore.host).flatMap(WailoAddress.init)
        // Browsing is also the target picker for typed-code enrollment. A pinned socket and Bonjour
        // enumeration can coexist because discovery only auto-connects identities already trusted.
        startDiscovery()

        let forcedPort = session.explicitPort ?? WailoHostStore.port
        let host: String
        let port: Int
        let expectedStudioId: String?
        // Whether a human named this endpoint. It is the whole basis for a lenient first contact: an
        // address someone typed carries an intent that an mDNS advertisement never does.
        let chosen: Bool
        if let invite = pendingInvite {
            host = invite.host
            port = invite.port
            expectedStudioId = invite.studioId
            chosen = true
        } else if let pinned {
            host = pinned.host
            port = pinned.port ?? forcedPort ?? Wailo.defaultPort
            expectedStudioId = session.explicitHost == nil ? WailoHostStore.expectedStudioId : nil
            chosen = true
        } else if let found = firstKnownDesktop() {
            host = found.host
            port = forcedPort ?? found.port
            expectedStudioId = found.studioId
            chosen = false
        } else {
            host = Wailo.defaultHost
            port = forcedPort ?? Wailo.defaultPort
            expectedStudioId = nil
            chosen = false
        }

        guard let target = Endpoint(host: host, port: port, expectedStudioId: expectedStudioId) else {
            print("Wailo: cannot dial \(host):\(port); leaving the connection as it is")
            return false
        }

        let trust = self.trust(for: target, chosen: chosen)
        guard case .blocked = trust else {
            guard target != endpoint || client == nil else { return false }
            endpoint = target
            return rebuildClient(session: session, target: target, trust: trust)
        }
        // Nothing worth saying to whoever is on the other end, so do not open the socket at all.
        let connectionChanged = !usbActive && connected
        if connectionChanged { connected = false }
        connectedStudioId = nil
        phase = identityChange == nil ? .stopped : .identityMismatch
        endpoint = nil
        client?.stop()
        client = nil
        return connectionChanged
    }

    /// Discovery may only reconnect to a desktop this device already holds a key for.
    ///
    /// That restriction is the original fix: a colleague's Studio on the same WiFi advertises a `sid`
    /// we know nothing about, and used to win simply because its hostname sorted first. Reaching a new
    /// desktop is now always something the user does on purpose — by typing its address or scanning
    /// its QR — and discovery's job is only to find one already trusted, wherever DHCP has moved it.
    private func firstKnownDesktop() -> WailoService? {
        discovered.first { service in
            guard !service.studioId.isEmpty else { return false }
            guard let pairing = pairingStore.pairing(studioId: service.studioId) else { return false }
            return !pairing.refused
        }
    }

    private func trust(for target: Endpoint, chosen: Bool) -> Trust {
        if Self.isLoopback(target.host) { return .loopback }

        if let invite = pendingInvite, invite.host == target.host {
            return .wifi(.invited(
                studioId: invite.studioId,
                pairingSecret: invite.pairingSecret,
                publicKey: invite.publicKey,
                byCode: invite.pairedByCode
            ))
        }

        // An identity change already seen at this address, not yet accepted. Nothing is dialled until
        // the user resolves it; retrying would just reproduce the same warning every two seconds.
        if let identityChange, identityChange.host == target.host,
           !acceptedIdentityChanges.contains(target.host) {
            return .blocked
        }

        let acceptedReplacement = acceptedIdentityChanges.contains(target.host)
        if !acceptedReplacement,
           let expected = target.expectedStudioId,
           let pairing = pairingStore.pairing(studioId: expected) {
            guard !pairing.refused else { return .blocked }
            return .wifi(.paired(pairing))
        }

        // The address does not identify one saved Studio. A chosen endpoint may still answer as any
        // known identity; let its signed StudioHello select the key before treating it as a stranger.
        guard chosen else { return .blocked }
        let candidates = pairingStore.all.filter { !$0.refused }
        return candidates.isEmpty
            ? .wifi(.firstContact(expectedStudioId: acceptedReplacement ? nil : target.expectedStudioId))
            : .wifi(.knownOrFirstContact(
                candidates,
                expectedStudioId: acceptedReplacement ? nil : target.expectedStudioId
            ))
    }

    /// A typed IP names a machine, not an identity, so work back to one: the advertised `sid` at that
    /// address, else the Studio last reached there. An unfamiliar address is resolved from the
    /// identity in StudioHello, because DHCP can move any one of several saved Studios there.
    private func pairing(forHost host: String) -> WailoPairing? {
        if let advertised = discovered.first(where: { $0.host == host && !$0.studioId.isEmpty }),
           let pairing = pairingStore.pairing(studioId: advertised.studioId) {
            return pairing
        }
        return pairingStore.all.first(where: { $0.lastHost == host })
    }

    private static func isLoopback(_ host: String) -> Bool {
        #if DEBUG
        if forcesWifiHandshakeForTesting { return false }
        #endif
        return ["localhost", "127.0.0.1", "::1", "[::1]"].contains(host.lowercased())
    }

    private func rebuildClient(session: Session, target: Endpoint, trust: Trust) -> Bool {
        // A replacement starts disconnected. The old client's eventual close callback is ignored
        // once `client` points at the replacement, so leaving this to that callback makes a failed
        // handshake inherit the old client's `true` status forever.
        let connectionChanged = !usbActive && connected
        if connectionChanged { connected = false }
        connectedStudioId = nil
        client?.stop()

        let hello = Hello(device_name: session.deviceName, app_id: session.appId, platform: Wailo.platform)
        let trustBox = Self.trustBox(for: trust)
        let webSocket = WailoClient(hello: hello, url: target.url, security: security(for: trustBox, host: target.host))
        webSocket.onDialling = { [weak self, weak webSocket] in
            guard let self, let webSocket else { return }
            self.lanPhaseChanged(.dialling, from: webSocket)
        }
        webSocket.onAuthenticationStarted = { [weak self, weak webSocket] in
            guard let self, let webSocket else { return }
            self.lanPhaseChanged(.authenticating, from: webSocket)
        }
        webSocket.onConnectionChange = { [weak self, weak webSocket] isConnected in
            guard let self, let webSocket else { return }
            self.lanConnectionChanged(isConnected, from: webSocket)
        }
        webSocket.onHandshakeEstablished = { [weak self] pairing in
            self?.handshakeEstablished(pairing, upgrading: trustBox) ?? false
        }
        webSocket.onHandshakeRefused = { [weak self] reason in
            self?.handshakeRefused(reason)
        }
        webSocket.onIdentityChanged = { [weak self, weak webSocket] expected, actual in
            guard let self, let webSocket else { return }
            self.identityChanged(expected: expected, actual: actual, from: webSocket)
        }
        webSocket.start()
        client = webSocket
        if !usbActive { phase = .dialling }

        if !usbActive {
            wireTransport(webSocket, session: session)
        } else {
            webSocket.suspend()
        }
        return connectionChanged
    }

    /// Nil for the transports that prove nothing, which is also what makes `security` fall to `.open`.
    private static func trustBox(for trust: Trust) -> TrustBox? {
        guard case let .wifi(handshakeTrust) = trust else { return nil }
        return TrustBox(handshakeTrust)
    }

    private func security(for box: TrustBox?, host: String) -> WailoSessionSecurity {
        guard let box else { return .open }
        let store = pairingStore
        // Built per connection: each handshake needs its own nonce and ephemeral key, the trust may
        // have been upgraded by the connection before it, and the session counter has to be read fresh
        // because that connection advanced it.
        return .guarded {
            WailoHandshake(trust: Self.refreshed(box.current, in: store), host: host)
        }
    }

    /// The counter advances on every handshake, and the trust in the box was written one handshake
    /// ago at best. Everything else in it is fixed for the life of the pairing.
    private static func refreshed(
        _ trust: WailoHandshake.Trust,
        in store: WailoPairingStore
    ) -> WailoHandshake.Trust {
        switch trust {
        case let .paired(pairing):
            return store.pairing(studioId: pairing.studioId).map(WailoHandshake.Trust.paired)
                ?? .firstContact(expectedStudioId: pairing.studioId)
        case let .knownOrFirstContact(pairings, expectedStudioId):
            let current = pairings.compactMap { store.pairing(studioId: $0.studioId) }.filter { !$0.refused }
            return current.isEmpty
                ? .firstContact(expectedStudioId: expectedStudioId)
                : .knownOrFirstContact(current, expectedStudioId: expectedStudioId)
        case .firstContact, .invited:
            return trust
        }
    }

    private func handshakeEstablished(_ pairing: WailoPairing, upgrading trust: TrustBox?) -> Bool {
        lock.lock()
        // Forget may race the final result frame. Keep this check under the same lock as deletion so
        // a completed handshake cannot resurrect the relationship or release Hello.
        guard !connectionSuppressedAfterForget else {
            lock.unlock()
            return false
        }
        // A previous entry for this address is stale the moment a different identity is accepted
        // there; leaving it would keep resolving the host back to a Studio that has moved on.
        if acceptedIdentityChanges.remove(pairing.lastHost) != nil {
            for stale in pairingStore.all
            where stale.lastHost == pairing.lastHost && stale.studioId != pairing.studioId {
                pairingStore.forget(studioId: stale.studioId)
            }
        }
        pairingStore.save(pairing)
        if WailoHostStore.host == pairing.lastHost {
            WailoHostStore.expectedStudioId = pairing.studioId
        }
        // Persisting the identity changes the endpoint's durable meaning but not the live socket.
        // Keep the in-memory endpoint in step so a later discovery update does not rebuild an already
        // authenticated connection merely because its expected identity moved from nil to this pin.
        if let current = endpoint, current.host == pairing.lastHost {
            endpoint = Endpoint(
                host: current.host,
                port: current.port,
                expectedStudioId: pairing.studioId
            )
        }
        connectedStudioId = pairing.studioId
        // Whatever this connection was — a first contact, an invite — the device now holds a key and
        // Studio will ask it to prove it next time. Reconnects do not re-enter `apply`, so this is the
        // only place that can move the client on from the trust it was built with.
        trust?.replace(with: .paired(pairing))
        if pendingInvite?.studioId == pairing.studioId { pendingInvite = nil }
        refusal = nil
        if identityChange?.host == pairing.lastHost { identityChange = nil }
        lock.unlock()
        post(Wailo.discoveryDidChangeNotification)
        return true
    }

    /// A pinned address answered by someone else. Stop, and leave it to a human — the whole point of
    /// having pinned the key is that this decision is not made automatically (ADR-0040).
    private func identityChanged(expected: String, actual: String, from source: WailoClient) {
        lock.lock()
        guard client === source, let host = endpoint?.host else { lock.unlock(); return }
        identityChange = WailoIdentityChange(host: host, expected: expected, actual: actual)
        connected = false
        connectedStudioId = nil
        phase = .identityMismatch
        client?.stop()
        client = nil
        endpoint = nil
        lock.unlock()
        post(Wailo.connectionDidChangeNotification)
    }

    private func handshakeRefused(_ reason: String) {
        lock.lock()
        refusal = reason
        // Latch it on the pairing so `apply` stops choosing this desktop. V3 authenticates the
        // refusal, but deletion still belongs to the human: retry and Forget have different meanings.
        if var pairing = endpoint.flatMap({ pairing(forHost: $0.host) }) {
            pairing.refused = true
            pairingStore.save(pairing)
        }
        pendingInvite = nil
        connected = false
        connectedStudioId = nil
        phase = .refused
        client?.stop()
        client = nil
        endpoint = nil
        lock.unlock()
        post(Wailo.connectionDidChangeNotification)
    }

    private func wireTransport(
        _ transport: CaptureSink & WailoBodyFetcher & WailoBreakpointGate & WailoScriptTransformer,
        session: Session
    ) {
        let sink: CaptureSink = session.alsoLogToConsole ? transport + ConsoleSink() : transport
        WailoURLProtocol.sink = sink
        WailoURLProtocol.bodyFetcher = transport
        WailoURLProtocol.breakpointGate = transport
        WailoURLProtocol.scriptTransformer = transport
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
        let connectionChanged = apply()
        lock.unlock()
        if connectionChanged { post(Wailo.connectionDidChangeNotification) }
        post(Wailo.discoveryDidChangeNotification)
    }

    private func usbAccepted(_ connection: WailoUsbConnection) {
        lock.lock()
        guard let session else { lock.unlock(); return }
        let replacedActiveUsb = usbActive
        if replacedActiveUsb {
            usbActive = false
            connected = false
            connectedStudioId = nil
            phase = client == nil ? .stopped : .dialling
            if let client {
                wireTransport(client, session: session)
                client.resume()
            }
        }
        usbConnection = connection
        connection.onConnectionChange = { [weak self, weak connection] isConnected in
            guard let self, let connection else { return }
            self.usbConnectionChanged(isConnected, from: connection)
        }
        lock.unlock()
        if replacedActiveUsb { post(Wailo.connectionDidChangeNotification) }
    }

    private func usbClosed(_ connection: WailoUsbConnection) {
        lock.lock()
        guard usbConnection === connection else { lock.unlock(); return }
        usbConnection = nil
        let wasActive = usbActive
        usbActive = false
        if wasActive, let session, let client {
            wireTransport(client, session: session)
            client.resume()
        }
        if wasActive {
            connected = false
            connectedStudioId = nil
            phase = client == nil ? .stopped : .dialling
        }
        lock.unlock()
        if wasActive { post(Wailo.connectionDidChangeNotification) }
    }

    private func lanConnectionChanged(_ isConnected: Bool, from source: WailoClient) {
        lock.lock()
        guard client === source, !usbActive, connected != isConnected else { lock.unlock(); return }
        connected = isConnected
        phase = isConnected ? .connected : .dialling
        if !isConnected { connectedStudioId = nil }
        lock.unlock()
        post(Wailo.connectionDidChangeNotification)
    }

    private func lanPhaseChanged(_ next: WailoConnectionPhase, from source: WailoClient) {
        lock.lock()
        guard client === source, !usbActive else { lock.unlock(); return }
        let changed = phase != next
        phase = next
        lock.unlock()
        if next == .dialling { refreshDiscovery() }
        if changed { post(Wailo.connectionDidChangeNotification) }
    }

    private func usbConnectionChanged(_ isConnected: Bool, from source: WailoUsbConnection) {
        lock.lock()
        guard usbConnection === source else { lock.unlock(); return }
        if isConnected, !usbActive {
            guard let session else { lock.unlock(); return }
            // Make before break: an attached cable is not yet a usable transport. Keep LAN live until
            // USB has sent Hello successfully, then move the control channel and suspend LAN.
            usbActive = true
            connectedStudioId = nil
            client?.suspend()
            wireTransport(source, session: session)
        } else if !isConnected, !usbActive {
            lock.unlock()
            return
        }
        let changed = connected != isConnected
        connected = isConnected
        phase = isConnected ? .connected : .dialling
        lock.unlock()
        // USB takeover changes activeAddress/transport even when LAN was already connected, so publish
        // on the first `true` regardless of whether the shared connection boolean changed.
        if changed || isConnected { post(Wailo.connectionDidChangeNotification) }
    }

    private func post(_ name: Notification.Name) {
        NotificationCenter.default.post(name: name, object: nil)
    }
}
