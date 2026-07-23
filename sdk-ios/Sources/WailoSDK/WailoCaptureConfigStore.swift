import Foundation
import WailoProtocol

/// Holds the capture allowlist the desktop has pushed: the host patterns whose request/response *bodies*
/// the device captures. Metadata (method, url, status, headers, sizes) is always captured; this store
/// only gates body bytes, so memory stays bounded to the few hosts the user has "unlocked". The desktop
/// owns the list and the device only ever receives full snapshots (`replace`), so there is no merge
/// logic. State is guarded by a lock so the WebSocket receive loop can update it while the interceptor
/// reads it from arbitrary URLSession threads. Empty = capture no bodies (the default until a host is
/// unlocked). Mirrors `WailoRuleStore`.
final class WailoCaptureConfigStore: @unchecked Sendable {

    static let shared = WailoCaptureConfigStore()

    private let lock = NSLock()
    private var hostPatterns: [String] = []

    /// Replace the whole allowlist with the latest snapshot from the desktop.
    func replace(_ hostPatterns: [String]) {
        lock.lock()
        self.hostPatterns = hostPatterns
        lock.unlock()
    }

    /// Whether [host]'s bodies should be captured: true iff some pattern matches it. A nil/empty host,
    /// or an empty allowlist, never matches (so bodies are omitted by default).
    func isBodyAllowed(host: String?) -> Bool {
        guard let host, !host.isEmpty else { return false }
        lock.lock()
        let snapshot = hostPatterns
        lock.unlock()
        return snapshot.contains { wildcardMatches($0, host) }
    }

    /// `*` matches any run of characters; everything else is literal, matched case-insensitively against
    /// the whole host. Same wildcard scheme as `WailoRuleStore`, so the desktop and device agree.
    private func wildcardMatches(_ pattern: String, _ host: String) -> Bool {
        guard !pattern.isEmpty else { return false }
        let escaped = NSRegularExpression.escapedPattern(for: pattern)
        let body = escaped.replacingOccurrences(of: "\\*", with: ".*")
        return host.range(of: "^" + body + "$", options: [.regularExpression, .caseInsensitive]) != nil
    }
}
