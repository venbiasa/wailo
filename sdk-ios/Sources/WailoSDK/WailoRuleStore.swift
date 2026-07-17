import Foundation
import WailoProtocol

/// Holds the Map Local *match-metadata* the desktop has pushed, and answers "does this request match a
/// rule?". No response bodies live here (ADR-0019) — a match only yields the rule id, and the body is
/// fetched from the desktop on demand. The desktop owns the rules; the device only ever receives full
/// snapshots (`replace`), so there is no merge logic. State is guarded by a lock so the WebSocket
/// receive loop can update it while the interceptor reads it from arbitrary URLSession threads.
final class WailoRuleStore: @unchecked Sendable {

    static let shared = WailoRuleStore()

    private let lock = NSLock()
    private var rules: [MapLocalRule] = []

    /// Replace the whole rule set with the latest snapshot from the desktop.
    func replace(_ rules: [MapLocalRule]) {
        lock.lock()
        self.rules = rules
        lock.unlock()
    }

    /// The first enabled rule whose method filter and URL wildcard both match, or nil if none do.
    func match(url: String, method: String) -> MapLocalRule? {
        lock.lock()
        let snapshot = rules
        lock.unlock()
        return snapshot.first { rule in
            rule.enabled
                && methodMatches(rule.methods, method)
                && wildcardMatches(rule.url_pattern, url)
        }
    }

    private func methodMatches(_ methods: [String], _ method: String) -> Bool {
        methods.isEmpty || methods.contains { $0.caseInsensitiveCompare(method) == .orderedSame }
    }

    /// `*` matches any run of characters; everything else is literal. Implemented by escaping the
    /// pattern to a regex, restoring `*` as `.*`, and anchoring so the whole URL must match.
    private func wildcardMatches(_ pattern: String, _ text: String) -> Bool {
        let escaped = NSRegularExpression.escapedPattern(for: pattern)
        let body = escaped.replacingOccurrences(of: "\\*", with: ".*")
        return text.range(of: "^" + body + "$", options: [.regularExpression]) != nil
    }
}
