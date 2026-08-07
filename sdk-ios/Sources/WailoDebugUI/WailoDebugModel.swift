#if canImport(UIKit)
import Combine
import Foundation
import WailoSDK

/// Mirrors the SDK's status into something SwiftUI can bind to.
///
/// `WailoSDK` publishes plain `Notification`s rather than an `ObservableObject` so the core stays
/// Foundation-only (invariant #3); the Combine dependency belongs here, in the product that already
/// draws UI. Phrasing lives here too, so the view renders strings instead of deriving them.
final class WailoDebugModel: ObservableObject {

    @Published var host: String { didSet { clearManualErrors() } }
    @Published var port: String { didSet { clearManualErrors() } }
    @Published var usbPort: String
    /// Why the last Connect didn't take, per field. Reported on submit rather than while typing —
    /// half-typed text is not a mistake — and cleared by the next edit.
    @Published private(set) var hostError: String?
    @Published private(set) var portError: String?
    @Published private(set) var isConnected: Bool
    @Published private(set) var discovered: [WailoService]
    @Published private(set) var activeAddress: String
    @Published private(set) var isUsingDiscovery: Bool
    @Published private(set) var isStarted: Bool
    @Published private(set) var isUsb: Bool

    // MARK: - pairing (ADR-0039)

    /// Filtered to the code's alphabet on every keystroke, so the field cannot hold something the
    /// handshake would reject — a typed `O` that silently becomes a wrong key is unexplainable.
    @Published var pairingCode: String = "" {
        didSet {
            let cleaned = WailoPairingCode.sanitize(pairingCode)
            if cleaned != pairingCode { pairingCode = cleaned } else if pairingError != nil { pairingError = nil }
        }
    }
    /// Which discovered desktop a typed code is meant for. A code says nothing about who is offering
    /// it, so the target has to be picked; preselected when there is only one candidate.
    @Published var codeTarget: WailoService?
    @Published private(set) var pairings: [WailoPairing]
    @Published private(set) var pairingError: String?
    @Published private(set) var refusal: String?
    @Published private(set) var identityChange: WailoIdentityChange?
    @Published private(set) var attempt: ConnectAttempt = .idle

    /// What the last Connect did. Without this the button is indistinguishable before and after a tap,
    /// which is the complaint it exists to answer.
    enum ConnectAttempt: Equatable {
        case idle
        case dialling(String)
        case connected(String)
        case stopped(String)
    }
    @Published var isScanning = false

    private var observers: [NSObjectProtocol] = []

    init() {
        host = Wailo.configuredHost ?? ""
        port = Wailo.configuredPort.map(String.init) ?? ""
        usbPort = String(Wailo.usbPort)
        isConnected = Wailo.isConnected
        discovered = Wailo.discoveredDesktops
        activeAddress = Wailo.activeAddress ?? "not started"
        isUsingDiscovery = Wailo.configuredHost == nil
        isStarted = Wailo.activeAddress != nil
        isUsb = Wailo.activeAddress?.hasPrefix("usb:") ?? false
        pairings = Wailo.pairings
        refusal = Wailo.pairingRefusal
        codeTarget = Wailo.discoveredDesktops.first { !$0.studioId.isEmpty }

        let center = NotificationCenter.default
        let refresh: (Notification) -> Void = { [weak self] _ in self?.refresh() }
        observers = [
            center.addObserver(forName: Wailo.connectionDidChangeNotification, object: nil, queue: .main, using: refresh),
            center.addObserver(forName: Wailo.discoveryDidChangeNotification, object: nil, queue: .main, using: refresh),
        ]
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    // MARK: - status wording

    var statusTitle: String {
        if !isStarted { return "Not started" }
        return isConnected ? "Connected" : "Not connected"
    }

    var transportLabel: String? {
        guard isStarted else { return nil }
        return isUsb ? "USB" : "LAN"
    }

    var statusDetail: String {
        if !isStarted { return "Wailo.start() has not run yet." }
        if isUsb { return "Studio is attached over the cable; the Wi-Fi connection is paused until it unplugs." }
        if isConnected { return isUsingDiscovery ? "Found over Bonjour." : "Pinned to a manual address." }
        return isUsingDiscovery
            ? "Looking for a desktop over Bonjour. Needs the same Wi-Fi and Local Network permission."
            : "Retrying the pinned address."
    }

    // MARK: - actions

    /// Pin the typed address. An empty field means "stop pinning", which is the same as `useDiscovery`.
    ///
    /// The address field is allowed to carry its port, because `10.0.0.2:8899` is what people type when
    /// asked for an address — that input used to crash the app. A port found there is moved into the
    /// port field, so the panel never shows an address different from the one it is dialling.
    func apply() {
        let typedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !typedHost.isEmpty else { return useDiscovery() }
        guard let address = WailoAddress(typedHost) else {
            hostError = "Not an address Wailo can dial. Use an IP or hostname, with an optional :port."
            return
        }

        let typedPort = port.trimmingCharacters(in: .whitespacesAndNewlines)
        var resolvedPort = address.port
        if resolvedPort == nil, !typedPort.isEmpty {
            guard let value = Int(typedPort), WailoAddress.portRange.contains(value) else {
                portError = "The port has to be a number from 1 to 65535."
                return
            }
            resolvedPort = value
        }

        host = address.host
        port = resolvedPort.map(String.init) ?? ""
        Wailo.setHost(address.host, port: resolvedPort)
        // Dialling is asynchronous, so Connect cannot report success or failure by the time it returns.
        // Saying "dialling" and letting the status line settle is honest; leaving the button looking
        // exactly as it did before the tap is what makes people press it again.
        attempt = .dialling(address.host)
        refresh()
    }

    var canApply: Bool {
        !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isTargetActive
    }

    /// Whether the field already names what is being dialled. Connect has nothing left to do then, and
    /// an enabled button that changes nothing reads as a button that did not work.
    private var isTargetActive: Bool {
        let typed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let address = WailoAddress(typed) else { return false }
        guard Wailo.configuredHost == address.host else { return false }
        let typedPort = address.port ?? Int(port.trimmingCharacters(in: .whitespacesAndNewlines))
        return typedPort == nil || typedPort == Wailo.configuredPort
    }

    func useDiscovery() {
        host = ""
        port = ""
        Wailo.setHost(nil, port: nil)
        attempt = .idle
        refresh()
    }

    /// A desktop worth offering under the address field: one on this network now, one this device has
    /// reached before, or — the ordinary case — both at once.
    ///
    /// Merged rather than listed twice. Discovery and the pairing store answer different questions
    /// ("who is advertising" and "who do we hold a key for"), but for the person choosing an address
    /// they describe the same machine, and showing it in two places made "which of these is mine" a
    /// question the panel itself invented.
    struct Desktop: Identifiable {

        let id: String
        /// The Bonjour instance name while it is advertising, else the address it was last reached at.
        let name: String
        /// What Fill puts in the address field. A remembered desktop that is not advertising has no
        /// port to offer, which leaves the field empty and the default in force.
        let host: String
        let port: Int?
        let pairing: WailoPairing?
        let online: Bool

        var address: String { port.map { "\(host):\($0)" } ?? host }

        /// Dropped when it would only repeat the title, which is every remembered desktop that is
        /// currently offline — there its name *is* its last address.
        var subtitle: String? { name == address ? nil : address }

        /// Held only for a desktop this device has a key for: it is the one thing that can be compared
        /// against what Studio prints in its own Settings when two machines look alike (ADR-0040).
        var fingerprint: String? { pairing?.studioId }

        var trustLabel: String? {
            guard let pairing else { return nil }
            let how = pairing.trustedOnFirstUse ? "Trusted on first contact" : "Paired"
            return online ? how : "\(how) — not on this network"
        }

        var warning: String? {
            pairing?.refused == true ? "This desktop no longer recognises this device." : nil
        }

        /// Forgetting is only meaningful for a desktop there is something stored about.
        var isRemembered: Bool { pairing != nil }

        var canFill: Bool { !host.isEmpty }
    }

    var desktops: [Desktop] {
        let remembered = Dictionary(pairings.map { ($0.studioId, $0) }, uniquingKeysWith: { first, _ in first })
        var matched: Set<String> = []
        let advertising = discovered.map { service -> Desktop in
            let pairing = service.studioId.isEmpty ? nil : remembered[service.studioId]
            if let pairing { matched.insert(pairing.studioId) }
            return Desktop(
                id: pairing?.studioId ?? service.id,
                name: service.name,
                host: service.host,
                port: service.port,
                pairing: pairing,
                online: true
            )
        }
        // Kept in the list even though nothing can be dialled right now: this is the only place a
        // desktop that has moved networks — or one the user is done with — can be forgotten, and
        // hiding it would mean Bonjour silently reconnecting to something with no way to say no.
        let offline = pairings
            .filter { !matched.contains($0.studioId) }
            .map { pairing in
                Desktop(
                    id: pairing.studioId,
                    name: pairing.lastHost.isEmpty ? "Paired desktop" : pairing.lastHost,
                    host: pairing.lastHost,
                    port: nil,
                    pairing: pairing,
                    online: false
                )
            }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        return advertising + offline
    }

    /// Fills the address field rather than dialling. A row in this list is a suggestion — the user
    /// still has to say "that one", because a first contact over Wi-Fi pins whatever answers and that
    /// should never happen from a stray tap (ADR-0040).
    func fill(from desktop: Desktop) {
        guard desktop.canFill else { return }
        host = desktop.host
        port = desktop.port.map(String.init) ?? ""
        attempt = .idle
        clearManualErrors()
    }

    /// Only offered for a port that is both valid and different — re-applying the current one would drop
    /// a working USB session to rebuild an identical listener.
    var canApplyUsbPort: Bool {
        guard let value = Int(usbPort.trimmingCharacters(in: .whitespacesAndNewlines)) else { return false }
        return (1...65535).contains(value) && value != Wailo.usbPort
    }

    func applyUsbPort() {
        guard canApplyUsbPort else { return }
        Wailo.setUsbPort(Int(usbPort.trimmingCharacters(in: .whitespacesAndNewlines)))
        usbPort = String(Wailo.usbPort)
        refresh()
    }

    // MARK: - pairing actions

    /// Whether a paired Studio is what the SDK is actually talking to. USB and the Simulator connect
    /// without pairing, so "connected" alone must not be read as "pairing worked".
    var isPairedConnection: Bool {
        guard isConnected, !isUsb else { return false }
        return pairings.contains { !$0.refused }
    }

    var cameraAvailability: WailoCameraAvailability { WailoCamera.availability }

    /// Ask for the camera only when the user taps Scan. Requesting on open would prompt every
    /// developer who opens the panel to look at something else.
    func startScanning() {
        pairingError = nil
        switch cameraAvailability {
        case .ready:
            WailoCamera.requestAccess { [weak self] granted in
                guard let self else { return }
                if granted {
                    self.isScanning = true
                } else {
                    self.pairingError = WailoCameraAvailability.denied.message
                }
            }
        case .missingUsageDescription, .denied, .unavailable:
            pairingError = cameraAvailability.message
        }
    }

    func scanned(_ payload: String) {
        isScanning = false
        guard Wailo.pair(qr: payload) else {
            pairingError = "That QR is not a Wailo pairing code."
            return
        }
        pairingError = nil
        refresh()
    }

    /// Desktops a typed code can be aimed at. A Studio too old to advertise its fingerprint is
    /// excluded: without one there is nothing to derive the key against, so offering it would only
    /// produce a handshake that fails for reasons the user cannot see.
    var pairableDesktops: [WailoService] {
        discovered.filter { !$0.studioId.isEmpty }
    }

    var canPairWithCode: Bool {
        WailoPairingCode.normalize(pairingCode) != nil && codeTarget != nil
    }

    func pairWithCode() {
        guard let target = codeTarget else {
            pairingError = "Pick the desktop this code is showing on."
            return
        }
        guard Wailo.pair(code: pairingCode, with: target) else {
            pairingError = target.studioId.isEmpty
                ? "That desktop is running a Studio too old to pair. Update it."
                : "That code isn't right. Check what Studio is showing — it expires after two minutes."
            return
        }
        pairingCode = ""
        pairingError = nil
        refresh()
    }

    func forget(_ desktop: Desktop) {
        guard let pairing = desktop.pairing else { return }
        Wailo.forgetPairing(studioId: pairing.studioId)
        refresh()
    }

    func retryAfterRefusal() {
        Wailo.retryPairing()
        refresh()
    }

    // MARK: - identity change

    func acceptIdentityChange() {
        guard let change = identityChange else { return }
        Wailo.acceptIdentityChange()
        attempt = .dialling(change.host)
        refresh()
    }

    func rejectIdentityChange() {
        Wailo.rejectIdentityChange()
        attempt = .idle
        refresh()
    }

    private func clearManualErrors() {
        guard hostError != nil || portError != nil else { return }
        hostError = nil
        portError = nil
    }

    /// Where the in-flight attempt has got to. Resolved from the SDK rather than tracked by the button,
    /// so a connection that drops later stops claiming to be connected.
    private var resolvedAttempt: ConnectAttempt {
        guard case let .dialling(target) = attempt else {
            guard case let .connected(target) = attempt, !Wailo.isConnected else { return attempt }
            return .stopped(target)
        }
        if Wailo.isConnected { return .connected(target) }
        // The coordinator refuses to open a socket at all for an address it will not talk to, so an
        // attempt with nothing active behind it has already been decided against.
        if Wailo.activeAddress == nil || Wailo.identityChange?.host == target { return .stopped(target) }
        return attempt
    }

    private func refresh() {
        isConnected = Wailo.isConnected
        discovered = Wailo.discoveredDesktops
        activeAddress = Wailo.activeAddress ?? "not started"
        isUsingDiscovery = Wailo.configuredHost == nil
        isStarted = Wailo.activeAddress != nil
        isUsb = Wailo.activeAddress?.hasPrefix("usb:") ?? false
        pairings = Wailo.pairings
        refusal = Wailo.pairingRefusal
        identityChange = Wailo.identityChange
        attempt = resolvedAttempt
        // Discovery churns as desktops come and go; only re-pick when the chosen one is gone, so the
        // selection does not move out from under someone mid-way through typing a code.
        if codeTarget == nil || !discovered.contains(where: { $0.studioId == codeTarget?.studioId }) {
            codeTarget = discovered.first { !$0.studioId.isEmpty }
        }
    }
}
#endif
