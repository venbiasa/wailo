import Foundation
import WailoProtocol

/// Holds the breakpoint rules the desktop has pushed, and answers "should this request pause, and at
/// which phase(s)?". Mirrors `WailoRuleStore` (Map Local): the desktop owns the rules and only ever
/// sends full snapshots (`replace`), so there is no merge logic, and the set is dropped on disconnect so
/// the desktop stays the single source of truth. State is guarded by a lock so the WebSocket receive
/// loop can update it while the interceptor reads it from arbitrary URLSession threads (ADR-0027).
final class WailoBreakpointStore: @unchecked Sendable {

    static let shared = WailoBreakpointStore()

    /// The first matching rule's id and which phases it pauses on.
    struct Match {
        let ruleId: String
        let onRequest: Bool
        let onResponse: Bool
    }

    private let lock = NSLock()
    private var rules: [BreakpointRule] = []

    /// Replace the whole rule set with the latest snapshot from the desktop.
    func replace(_ rules: [BreakpointRule]) {
        lock.lock()
        self.rules = rules
        lock.unlock()
    }

    /// The first enabled rule that can pause at least one phase and whose method filter and URL wildcard
    /// both match, or nil if none do.
    func match(url: String, method: String) -> Match? {
        lock.lock()
        let snapshot = rules
        lock.unlock()
        guard let rule = snapshot.first(where: { rule in
            rule.enabled
                && (rule.on_request || rule.on_response)
                && methodMatches(rule.methods, method)
                && wildcardMatches(rule.url_pattern, url)
        }) else { return nil }
        return Match(ruleId: rule.id, onRequest: rule.on_request, onResponse: rule.on_response)
    }

    private func methodMatches(_ methods: [String], _ method: String) -> Bool {
        methods.isEmpty || methods.contains { $0.caseInsensitiveCompare(method) == .orderedSame }
    }

    /// `*` matches any run of characters; everything else is literal. Escapes the pattern to a regex,
    /// restores `*` as `.*`, and anchors so the whole URL must match (identical to `WailoRuleStore`).
    private func wildcardMatches(_ pattern: String, _ text: String) -> Bool {
        let escaped = NSRegularExpression.escapedPattern(for: pattern)
        let body = escaped.replacingOccurrences(of: "\\*", with: ".*")
        return text.range(of: "^" + body + "$", options: [.regularExpression]) != nil
    }
}
