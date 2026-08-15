import Foundation
import WailoProtocol
#if canImport(UIKit)
import UIKit
#endif

public enum WailoConnectionPhase: Sendable {
    case stopped
    case dialling
    case authenticating
    case connected
    case refused
    case identityMismatch
}

/// Entry point for the iOS SDK. Call `Wailo.start()` once at launch (e.g. in
/// `application(_:didFinishLaunchingWithOptions:)`). This registers the interceptor and begins
/// streaming captured exchanges to the desktop.
///
/// Reaching the desktop needs no address in the common cases. The Simulator shares the Mac's network
/// stack, so `localhost:8899` works with no port forwarding (iOS has no `adb reverse`). A physical
/// device finds the desktop over Bonjour. Pass `host` — or set it at runtime via `setHost` — only to
/// pin a specific machine or when mDNS is blocked; see `WailoCoordinator` for the precedence order.
public enum Wailo {

    public static let defaultHost = "localhost"
    public static let defaultPort = 8899
    /// Loopback port the device listens on for USB-tunnelled Studio connections (distinct from LAN).
    public static let defaultUsbPort = Int(WailoUsb.defaultPort)
    static let platform = "ios"

    /// Posted when the live connection opens or closes; read `isConnected` for the current value.
    public static let connectionDidChangeNotification = Notification.Name("com.venbiasa.wailo.connectionDidChange")

    /// Posted when the set of desktops visible on the LAN changes; read `discoveredDesktops`.
    public static let discoveryDidChangeNotification = Notification.Name("com.venbiasa.wailo.discoveryDidChange")

    /// Registers the interceptor and starts streaming to the desktop.
    ///
    /// - Parameter host: pins a specific desktop. `nil` (the default) resolves the address at runtime —
    ///   persisted override, then Bonjour, then `localhost` — so the address can change without a rebuild.
    /// - Parameter instrumentSharedConfigurations: when true, also swizzles
    ///   `URLSessionConfiguration.default`/`.ephemeral` so sessions built by third-party libraries
    ///   are captured too. When false, only `URLSession.shared` and sessions passed to
    ///   `Wailo.instrument(_:)` are captured.
    /// - Parameter maxBodyBytes: optional cap on captured body bytes. nil (the default) captures the
    ///   full body. Whether a host is captured at all is decided by the desktop's CaptureFilter
    ///   (ADR-0029), so memory is bounded by which hosts pass the filter rather than by a per-body ceiling.
    public static func start(
        appId: String = Bundle.main.bundleIdentifier ?? "unknown",
        deviceName: String = defaultDeviceName(),
        host: String? = nil,
        port: Int? = nil,
        maxBodyBytes: Int? = nil,
        alsoLogToConsole: Bool = true,
        instrumentSharedConfigurations: Bool = true
    ) {
        WailoCoordinator.shared.start(WailoCoordinator.Session(
            appId: appId,
            deviceName: deviceName,
            explicitHost: host,
            explicitPort: port,
            maxBodyBytes: maxBodyBytes,
            alsoLogToConsole: alsoLogToConsole
        ))
        URLProtocol.registerClass(WailoURLProtocol.self)
        if instrumentSharedConfigurations {
            URLSessionConfiguration.wailo_installProtocolInjection()
        }
    }

    /// Adds the interceptor to a caller-owned `URLSessionConfiguration`. Use this for sessions built
    /// before `start()` or when `instrumentSharedConfigurations` is disabled.
    public static func instrument(_ configuration: URLSessionConfiguration) {
        configuration.wailo_injectProtocol()
    }

    public static func stop() {
        WailoCoordinator.shared.stop()
    }

    public static func defaultDeviceName() -> String {
        #if canImport(UIKit)
        return UIDevice.current.name
        #else
        return ProcessInfo.processInfo.hostName
        #endif
    }

    // MARK: - Runtime configuration

    /// Re-point at a different desktop and reconnect immediately, persisting the choice across launches.
    /// Pass `nil` to clear the override and hand control back to Bonjour discovery.
    ///
    /// `host` is free text: `10.0.0.2`, `10.0.0.2:8899`, a bare IPv6 literal, and a pasted `ws://…` all
    /// resolve, and a port named inside `host` wins over the `port` argument. Returns false — changing
    /// nothing, so a working address survives a typo — when the text names no address that can be
    /// dialled.
    ///
    /// Takes effect without a rebuild — that is the point (ADR-0035). A `host` passed to `start` still
    /// outranks this for the current process.
    @discardableResult
    public static func setHost(
        _ host: String?,
        port: Int? = nil,
        expectedStudioId: String? = nil
    ) -> Bool {
        WailoCoordinator.shared.setHost(host, port: port, expectedStudioId: expectedStudioId)
    }

    /// Move the USB listener to another port and persist the choice; `nil` restores [defaultUsbPort].
    ///
    /// Studio must be set to the same port. USB has no equivalent of Bonjour — usbmux forwards to a
    /// number — so a mismatch simply looks like the app isn't running.
    public static func setUsbPort(_ port: Int?) {
        WailoCoordinator.shared.setUsbPort(port)
    }

    /// The manual override currently in force, or `nil` when the address comes from discovery.
    public static var configuredHost: String? { WailoCoordinator.shared.configuredHost }

    public static var configuredPort: Int? { WailoCoordinator.shared.configuredPort }

    public static var configuredUsbPort: Int? { WailoCoordinator.shared.configuredUsbPort }

    /// The USB port currently bound. Differs from [configuredUsbPort] before `start()`.
    public static var usbPort: Int { WailoCoordinator.shared.activeUsbPort }

    /// The `host:port` actually being dialled, however it was resolved. `nil` before `start()`.
    public static var activeAddress: String? { WailoCoordinator.shared.activeAddress }

    /// Whether the WebSocket is up right now. A `true` here is the only proof traffic is reaching the
    /// desktop; the client retries forever, so "started" says nothing.
    public static var isConnected: Bool { WailoCoordinator.shared.isConnected }

    public static var connectionPhase: WailoConnectionPhase {
        WailoCoordinator.shared.connectionPhase
    }

    /// Desktops currently advertising `_wailo._tcp` on the LAN while Wailo is running.
    public static var discoveredDesktops: [WailoService] { WailoCoordinator.shared.discoveredDesktops }

    /// Re-resolves currently advertised desktops. Results arrive through
    /// [discoveryDidChangeNotification] if a route changed.
    public static func refreshDiscovery() {
        WailoCoordinator.shared.refreshDiscovery()
    }

    // MARK: - Pairing

    /// Pair with the Studio a QR code names, and connect to it (ADR-0039).
    ///
    /// WiFi is the one transport where the peer is whoever answered an mDNS advertisement, so it is
    /// the one that has to be paired. Loopback needs none of this: the Simulator, `adb reverse` and
    /// the USB tunnel all reach a machine the kernel guarantees is this one.
    ///
    /// Returns false when the text is not a Wailo invite, in which case nothing changes.
    @discardableResult
    public static func pair(qr text: String) -> Bool {
        guard let invite = WailoPairingInvite(qr: text) else { return false }
        WailoCoordinator.shared.pair(invite)
        return true
    }

    /// Pair using the code Studio displays, against a desktop already visible on the network.
    ///
    /// The weaker of the two paths: a code short enough to type carries far less than a scanned key,
    /// so it leans on a slow KDF and a code that expires. Prefer the QR where a camera is available.
    @discardableResult
    public static func pair(code: String, with desktop: WailoService) -> Bool {
        guard !desktop.studioId.isEmpty,
              let invite = WailoPairingInvite(
                  code: code, studioId: desktop.studioId, host: desktop.host, port: desktop.port
              ) else { return false }
        WailoCoordinator.shared.pair(invite)
        return true
    }

    /// Every Studio this device is paired with.
    public static var pairings: [WailoPairing] { WailoCoordinator.shared.pairings }

    /// Stop trusting one Studio. It has to be paired again before this device will talk to it.
    public static func forgetPairing(studioId: String) {
        WailoCoordinator.shared.forget(studioId: studioId)
    }

    public static func forgetAllPairings() {
        WailoCoordinator.shared.forgetAllPairings()
    }

    /// Set after Studio signs a refusal — usually because it forgot this device. The device stops
    /// retrying until [retryPairing] is called; repeating the same authenticated failure cannot repair
    /// the relationship.
    public static var pairingRefusal: String? { WailoCoordinator.shared.refusalMessage }

    public static func retryPairing() {
        WailoCoordinator.shared.retryAfterRefusal()
    }

    /// Set when an address this device has a pinned key for is answered by a different Studio — a Mac
    /// that changed hands, or someone standing in the path. Nothing is dialled there until
    /// [acceptIdentityChange] or [rejectIdentityChange] settles it, because on the wire the two look
    /// exactly the same and only a person knows which happened (ADR-0040).
    public static var identityChange: WailoIdentityChange? {
        WailoCoordinator.shared.pendingIdentityChange
    }

    /// Trust the new identity at that address and forget the old one.
    public static func acceptIdentityChange() {
        WailoCoordinator.shared.acceptIdentityChange()
    }

    /// Keep the pinned identity and stop dialling that address.
    public static func rejectIdentityChange() {
        WailoCoordinator.shared.rejectIdentityChange()
    }
}
