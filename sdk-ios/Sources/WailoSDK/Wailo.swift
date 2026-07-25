import Foundation
import WailoProtocol
#if canImport(UIKit)
import UIKit
#endif

/// Entry point for the iOS SDK. Call `Wailo.start()` once at launch (e.g. in
/// `application(_:didFinishLaunchingWithOptions:)`). This registers the interceptor and begins
/// streaming captured exchanges to the desktop.
///
/// Simulator note: the Simulator shares the Mac's network stack, so the default `localhost:8899`
/// reaches the desktop engine with no port forwarding (iOS has no `adb reverse`). For a physical
/// device, pass the Mac's LAN IP as `host`.
public enum Wailo {

    public static let defaultHost = "localhost"
    public static let defaultPort = 8899
    private static let platform = "ios"

    nonisolated(unsafe) private static var client: WailoClient?

    /// Registers the interceptor and starts streaming to the desktop.
    ///
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
        host: String = defaultHost,
        port: Int = defaultPort,
        maxBodyBytes: Int? = nil,
        alsoLogToConsole: Bool = true,
        instrumentSharedConfigurations: Bool = true
    ) {
        // Idempotent: cleanly replace any prior client (e.g. the `+load` auto-start default) instead of
        // leaking a second live WebSocket — so "auto default + explicit customize" is one session, not two.
        client?.stop()

        let hello = Hello(device_name: deviceName, app_id: appId, platform: platform)
        let webSocket = WailoClient(hello: hello, host: host, port: port)
        let sink: CaptureSink = alsoLogToConsole ? webSocket + ConsoleSink() : webSocket

        WailoURLProtocol.sink = sink
        WailoURLProtocol.bodyFetcher = webSocket
        WailoURLProtocol.breakpointGate = webSocket
        WailoURLProtocol.maxBodyBytes = maxBodyBytes
        URLProtocol.registerClass(WailoURLProtocol.self)
        if instrumentSharedConfigurations {
            URLSessionConfiguration.wailo_installProtocolInjection()
        }
        webSocket.start()
        client = webSocket
    }

    /// Adds the interceptor to a caller-owned `URLSessionConfiguration`. Use this for sessions built
    /// before `start()` or when `instrumentSharedConfigurations` is disabled.
    public static func instrument(_ configuration: URLSessionConfiguration) {
        configuration.wailo_injectProtocol()
    }

    public static func stop() {
        client?.stop()
        client = nil
        WailoURLProtocol.sink = nil
        WailoURLProtocol.bodyFetcher = nil
        WailoURLProtocol.breakpointGate = nil
    }

    public static func defaultDeviceName() -> String {
        #if canImport(UIKit)
        return UIDevice.current.name
        #else
        return ProcessInfo.processInfo.hostName
        #endif
    }
}
