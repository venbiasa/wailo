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
        WailoScriptStore.shared.replace([])
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
        XCTAssertTrue(
            WailoScriptStore.shared.matches(
                url: "https://active.test/x",
                method: "GET",
                phase: .SCRIPT_PHASE_REQUEST
            )
        )

        active.close()

        XCTAssertNil(WailoRuleStore.shared.match(url: "https://active.test/x", method: "GET"))
        XCTAssertTrue(WailoCaptureFilterStore.shared.shouldCapture(host: "other.test"))
        XCTAssertNil(WailoBreakpointStore.shared.match(url: "https://active.test/x", method: "GET"))
        XCTAssertFalse(
            WailoScriptStore.shared.matches(
                url: "https://active.test/x",
                method: "GET",
                phase: .SCRIPT_PHASE_REQUEST
            )
        )
    }

    func testScriptTransformRoundTrips() throws {
        let transport = TestTransport(name: "transform")
        defer { transport.close() }

        let result = try transport.transformRequest()

        XCTAssertEqual(result?.request?.method, "PATCH")
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

        let script = ScriptRule(
            id: id,
            enabled: true,
            url_pattern: "https://\(id).test/*",
            on_request: true,
            on_response: false
        ) { $0.methods = [] }
        try push(Envelope {
            $0.message = .script_rule_set(ScriptRuleSet(epoch: epoch) {
                $0.rules = [script]
            })
        })
    }

    func close() {
        session.unbindAndWait()
    }

    func transformRequest() throws -> ScriptTransformResult? {
        let finished = DispatchSemaphore(value: 0)
        var result: ScriptTransformResult?
        session.transform(
            phase: .SCRIPT_PHASE_REQUEST,
            request: HttpRequest(
                method: "GET",
                url: "https://example.com",
                body: Data(),
                body_size: 0,
                body_truncated: false
            ),
            response: nil,
            requestBodyReplayable: true
        ) {
            result = $0
            finished.signal()
        }
        queue.sync {}
        let envelope = try link.lastEnvelope()
        guard case let .script_transform_request(transform)? = envelope.message else {
            XCTFail("Expected Script transform request")
            return nil
        }
        try push(Envelope {
            $0.message = .script_transform_result(ScriptTransformResult(
                correlation_id: transform.correlation_id,
                request_body_replaced: false,
                response_body_replaced: false,
                delay_ms: 0
            ) {
                $0.request = transform.request.map {
                    var edited = $0
                    edited.method = "PATCH"
                    return edited
                }
            })
        })
        XCTAssertEqual(finished.wait(timeout: .now() + 1), .success)
        return result
    }

    private func push(_ envelope: Envelope) throws {
        session.receive(try ProtoEncoder().encode(envelope))
        queue.sync {}
    }
}

private final class ImmediateLink: WailoTransportLink {
    private let lock = NSLock()
    private var sent: [Data] = []

    func send(_ data: Data, completion: @escaping (Error?) -> Void) {
        lock.lock()
        sent.append(data)
        lock.unlock()
        completion(nil)
    }

    func lastEnvelope() throws -> Envelope {
        lock.lock()
        let data = sent.last
        lock.unlock()
        return try ProtoDecoder().decode(Envelope.self, from: XCTUnwrap(data))
    }

    func startKeepalive(interval: TimeInterval, onFailure: @escaping () -> Void) {}

    func stopKeepalive() {}

    func closeLink() {}
}
