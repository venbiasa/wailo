import Foundation
import WailoProtocol

/// Holds the capture filter the desktop has pushed (ADR-0029): two independent host-pattern lists — an
/// allowlist and a blocklist, each with its own on/off switch — that decide which exchanges the device
/// captures and streams. The gate is whole-exchange: a host that passes is captured in full, one that
/// doesn't is dropped at the source (there is no metadata-only capture). The desktop owns the filter and
/// the device only ever receives full snapshots (`replace`), so there is no merge logic.
///
/// The default (and the state after every disconnect, via `reset`) is "both lists off" = capture
/// everything, matching the desktop's default and keeping the pre-push / no-desktop path permissive so
/// local aids like `ConsoleSink` still see traffic. The engine re-pushes the current filter on every
/// connect, so a real allowlist/blocklist reasserts itself within one frame of reconnecting.
///
/// State is guarded by a lock so the WebSocket receive loop can update it while the interceptor reads it
/// from arbitrary URLSession threads. Mirrors `WailoRuleStore`.
final class WailoCaptureFilterStore: @unchecked Sendable {

    static let shared = WailoCaptureFilterStore()

    private let lock = NSLock()
    private var filter = CaptureFilter(allowlist_enabled: false, blocklist_enabled: false, epoch: 0)

    /// Replace the filter with the latest snapshot from the desktop.
    func replace(_ filter: CaptureFilter) {
        lock.lock()
        self.filter = filter
        lock.unlock()
    }

    /// Forget the pushed filter on disconnect: fall back to capture-everything until the desktop
    /// re-pushes. The desktop is the source of truth, so no user-authored list outlives the connection.
    func reset() {
        replace(CaptureFilter(allowlist_enabled: false, blocklist_enabled: false, epoch: 0))
    }

    /// Whether [host]'s exchange should be captured and streamed. An enabled allowlist requires a match;
    /// an enabled blocklist rejects a match; with both on a host must be allowed and not blocked; with
    /// both off (the default) everything is captured. An enabled list with no patterns matches nothing,
    /// so an enabled-but-empty allowlist captures nothing and an enabled-but-empty blocklist blocks
    /// nothing. A nil/empty host only fails an enabled allowlist (it can't match a real pattern).
    func shouldCapture(host: String?) -> Bool {
        lock.lock()
        let filter = self.filter
        lock.unlock()
        let host = host ?? ""
        if filter.allowlist_enabled, !filter.allow_patterns.contains(where: { wildcardMatches($0, host) }) {
            return false
        }
        if filter.blocklist_enabled, filter.block_patterns.contains(where: { wildcardMatches($0, host) }) {
            return false
        }
        return true
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
