import Foundation
import ObjectiveC.runtime

/// `URLProtocol.registerClass` only reaches `URLSession.shared`; libraries that build their own
/// `URLSession` from `.default`/`.ephemeral` would be invisible. Swizzling those two factory getters
/// injects the interceptor into every configuration the process creates afterwards — capturing
/// third-party traffic with no cooperation, the iOS counterpart of the Android ASM plugin.
extension URLSessionConfiguration {

    private static let installOnce: Void = {
        exchangeClassGetter(
            #selector(getter: URLSessionConfiguration.default),
            with: #selector(getter: URLSessionConfiguration.wailo_default)
        )
        exchangeClassGetter(
            #selector(getter: URLSessionConfiguration.ephemeral),
            with: #selector(getter: URLSessionConfiguration.wailo_ephemeral)
        )
    }()

    static func wailo_installProtocolInjection() {
        _ = installOnce
    }

    // After the exchange these selectors resolve to the *original* implementations, so `Self.` here
    // returns the real config (the explicit qualifier also silences the self-recursion heuristic).
    @objc private class var wailo_default: URLSessionConfiguration {
        let configuration = Self.wailo_default
        configuration.wailo_injectProtocol()
        return configuration
    }

    @objc private class var wailo_ephemeral: URLSessionConfiguration {
        let configuration = Self.wailo_ephemeral
        configuration.wailo_injectProtocol()
        return configuration
    }

    func wailo_injectProtocol() {
        var classes = protocolClasses ?? []
        guard !classes.contains(where: { $0 == WailoURLProtocol.self }) else { return }
        classes.insert(WailoURLProtocol.self, at: 0)
        protocolClasses = classes
    }

    private static func exchangeClassGetter(_ original: Selector, with replacement: Selector) {
        guard
            let originalMethod = class_getClassMethod(self, original),
            let replacementMethod = class_getClassMethod(self, replacement)
        else { return }
        method_exchangeImplementations(originalMethod, replacementMethod)
    }
}
