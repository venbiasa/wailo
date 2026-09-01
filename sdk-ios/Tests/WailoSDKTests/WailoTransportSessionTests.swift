import Foundation
import XCTest
import Wire
import WailoProtocol
@testable import WailoSDK

final class WailoTransportSessionTests: XCTestCase {

    override func tearDown() {
        WailoRuleStore.shared.replace([])
        WailoCaptureFilterStore.shared.reset()
        WailoBreakpointStore.shared.replace([])
        super.tearDown()
    }

    func testRetiredSessionCannotClearNewerSessionSnapshots() throws {
        let retired = TestTransport(name: "retired")
        let active = TestTransport(name: "active")

        try retired.pushSnapshots(id: "retired", epoch: 1)
        try active.pushSnapshots(id: "active", epoch: 2)

        try retired.pushSnapshots(id: "stale", epoch: 3)
        retired.close()

        XCTAssertNotNil(WailoRuleStore.shared.match(url: "https://active.test/x", method: "GET"))
        XCTAssertTrue(WailoCaptureFilterStore.shared.shouldCapture(host: "active.test"))
        XCTAssertFalse(WailoCaptureFilterStore.shared.shouldCapture(host: "other.test"))
        XCTAssertEqual(
            WailoBreakpointStore.shared.match(url: "https://active.test/x", method: "GET")?.ruleId,
            "active"
        )

        active.close()

        XCTAssertNil(WailoRuleStore.shared.match(url: "https://active.test/x", method: "GET"))
        XCTAssertTrue(WailoCaptureFilterStore.shared.shouldCapture(host: "other.test"))
        XCTAssertNil(WailoBreakpointStore.shared.match(url: "https://active.test/x", method: "GET"))
    }
}

private final class TestTransport {
    private let queue: DispatchQueue
    private let link = ImmediateLink()
    private let session: WailoTransportSession

    init(name: String) {
        let queue = DispatchQueue(label: "com.venbiasa.wailo.tests.transport.\(name)")
        self.queue = queue
        session = WailoTransportSession(
            hello: Hello(device_name: name, app_id: "com.test", platform: "ios"),
            queue: queue
        )
        session.bind(to: link)
        queue.sync {}
    }

    func pushSnapshots(id: String, epoch: UInt64) throws {
        let mapRule = MapLocalRule(
            id: id,
            enabled: true,
            url_pattern: "https://\(id).test/*"
        ) { $0.methods = [] }
        try push(Envelope {
            $0.message = .rule_set(RuleSet(epoch: epoch) { $0.rules = [mapRule] })
        })

        let filter = CaptureFilter(
            allowlist_enabled: true,
            blocklist_enabled: false,
            epoch: epoch
        ) { $0.allow_patterns = ["\(id).test"] }
        try push(Envelope { $0.message = .capture_filter(filter) })

        let breakpoint = BreakpointRule(
            id: id,
            enabled: true,
            url_pattern: "https://\(id).test/*",
            on_request: true,
            on_response: false
        ) { $0.methods = [] }
        try push(Envelope {
            $0.message = .breakpoint_rules(BreakpointRules(epoch: epoch) {
                $0.rules = [breakpoint]
            })
        })
    }

    func close() {
        session.unbindAndWait()
    }

    private func push(_ envelope: Envelope) throws {
        session.receive(try ProtoEncoder().encode(envelope))
        queue.sync {}
    }
}

private final class ImmediateLink: WailoTransportLink {
    func send(_ data: Data, completion: @escaping (Error?) -> Void) {
        completion(nil)
    }

    func startKeepalive(interval: TimeInterval, onFailure: @escaping () -> Void) {}

    func stopKeepalive() {}

    func closeLink() {}
}
