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
        refresh()
    }

    var canApply: Bool {
        !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    func useDiscovery() {
        host = ""
        port = ""
        Wailo.setHost(nil, port: nil)
        refresh()
    }

    func pin(_ service: WailoService) {
        host = service.host
        port = String(service.port)
        Wailo.setHost(service.host, port: service.port)
        refresh()
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

    private func clearManualErrors() {
        guard hostError != nil || portError != nil else { return }
        hostError = nil
        portError = nil
    }

    private func refresh() {
        isConnected = Wailo.isConnected
        discovered = Wailo.discoveredDesktops
        activeAddress = Wailo.activeAddress ?? "not started"
        isUsingDiscovery = Wailo.configuredHost == nil
        isStarted = Wailo.activeAddress != nil
        isUsb = Wailo.activeAddress?.hasPrefix("usb:") ?? false
    }
}
#endif
