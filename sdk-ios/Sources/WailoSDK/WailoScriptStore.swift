import Foundation
import WailoProtocol

/// Match metadata for daemon-owned Scripts. Source and execution never enter the host app.
final class WailoScriptStore: @unchecked Sendable {

    static let shared = WailoScriptStore()

    private let lock = NSLock()
    private var rules: [ScriptRule] = []
    private var snapshotOwner: WailoSnapshotOwner?
    private var snapshotGeneration: UInt64 = 0

    func replace(_ rules: [ScriptRule], owner: WailoSnapshotOwner? = nil) {
        lock.lock()
        defer { lock.unlock() }
        if let owner {
            guard owner.generation >= snapshotGeneration else { return }
            snapshotGeneration = owner.generation
        } else {
            snapshotGeneration = 0
        }
        snapshotOwner = owner
        self.rules = rules
    }

    func reset(owner: WailoSnapshotOwner? = nil) {
        lock.lock()
        defer { lock.unlock() }
        if let owner {
            guard snapshotOwner === owner else { return }
        } else {
            snapshotGeneration = 0
        }
        snapshotOwner = nil
        rules = []
    }

    func matches(url: String, method: String, phase: ScriptPhase) -> Bool {
        lock.lock()
        let snapshot = rules
        lock.unlock()
        return snapshot.contains { rule in
            rule.enabled
                && ((phase == .SCRIPT_PHASE_REQUEST && rule.on_request)
                    || (phase == .SCRIPT_PHASE_RESPONSE && rule.on_response))
                && (rule.methods.isEmpty
                    || rule.methods.contains { $0.caseInsensitiveCompare(method) == .orderedSame })
                && wildcardMatches(rule.url_pattern, url)
        }
    }

    private func wildcardMatches(_ pattern: String, _ text: String) -> Bool {
        let escaped = NSRegularExpression.escapedPattern(for: pattern)
        let body = escaped.replacingOccurrences(of: "\\*", with: ".*")
        return text.range(of: "^" + body + "$", options: [.regularExpression]) != nil
    }
}
