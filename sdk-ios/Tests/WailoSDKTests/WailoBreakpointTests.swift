import XCTest
import Foundation
import WailoProtocol
@testable import WailoSDK

/// Device-side breakpoints (ADR-0027): the `WailoBreakpointStore` match rules, and the interceptor's
/// request/response-phase behavior driven by a stubbed gate — abort fails the call, a proceed(nil) fails
/// open to the real network, and a breakpoint *owns* an exchange that also matches Map Local, with the Map
/// Local value becoming the response it shows (precedence v3, ADR-0033). The connected round-trip
/// (hit -> decision) and disconnect fail-open live in the loopback test.
final class WailoBreakpointTests: XCTestCase {

    override func tearDown() {
        WailoBreakpointStore.shared.replace([])
        WailoRuleStore.shared.replace([])
        WailoURLProtocol.sink = nil
        WailoURLProtocol.bodyFetcher = nil
        WailoURLProtocol.breakpointGate = nil
        super.tearDown()
    }

    // MARK: - store matching

    func testMatchRequiresEnabledAndAtLeastOnePhase() {
        // Disabled: never matches.
        WailoBreakpointStore.shared.replace([
            BreakpointRule(id: "r", enabled: false, url_pattern: "https://bp.test/*", on_request: true, on_response: false),
        ])
        XCTAssertNil(WailoBreakpointStore.shared.match(url: "https://bp.test/x", method: "GET"))

        // Enabled but neither phase set: never fires.
        WailoBreakpointStore.shared.replace([
            BreakpointRule(id: "r", enabled: true, url_pattern: "https://bp.test/*", on_request: false, on_response: false),
        ])
        XCTAssertNil(WailoBreakpointStore.shared.match(url: "https://bp.test/x", method: "GET"))

        // Enabled with phases: matches and carries the phase flags.
        WailoBreakpointStore.shared.replace([
            BreakpointRule(id: "r", enabled: true, url_pattern: "https://bp.test/*", on_request: true, on_response: true),
        ])
        let match = WailoBreakpointStore.shared.match(url: "https://bp.test/x", method: "GET")
        XCTAssertEqual(match?.ruleId, "r")
        XCTAssertEqual(match?.onRequest, true)
        XCTAssertEqual(match?.onResponse, true)
    }

    func testMethodFilterAndWildcard() {
        WailoBreakpointStore.shared.replace([
            BreakpointRule(id: "r", enabled: true, url_pattern: "https://bp.test/*", on_request: true, on_response: false) {
                $0.methods = ["POST"]
            },
        ])
        // A POST-only rule ignores a GET, and the wildcard must span the path.
        XCTAssertNil(WailoBreakpointStore.shared.match(url: "https://bp.test/submit", method: "GET"))
        XCTAssertNotNil(WailoBreakpointStore.shared.match(url: "https://bp.test/submit", method: "POST"))
        XCTAssertNil(WailoBreakpointStore.shared.match(url: "https://other.test/submit", method: "POST"))
    }

    // MARK: - interceptor request phase

    func testRequestPhaseAbortFailsTheCall() {
        WailoBreakpointStore.shared.replace([
            BreakpointRule(id: "r", enabled: true, url_pattern: "https://bp.test/*", on_request: true, on_response: false),
        ])
        let gate = StubBreakpointGate(requestDecision: .abort)
        WailoURLProtocol.breakpointGate = gate
        let sink = CapturingSink()
        WailoURLProtocol.sink = sink

        let (data, response, error) = runIntercepted(url: "https://bp.test/go")

        XCTAssertNil(response)
        XCTAssertTrue(data?.isEmpty ?? true)
        XCTAssertEqual((error as? URLError)?.code, .cancelled, "abort must fail the call with .cancelled")
        XCTAssertEqual(gate.requestPauses.count, 1, "the request must have been paused once")
        // `emit` runs on the loading thread just after `didFailWithError` wakes this thread, so poll for it.
        let emitted = firstExchange(in: sink, matching: "https://bp.test/go")
        XCTAssertTrue(emitted?.edited ?? false, "an aborted exchange must be flagged edited")
    }

    func testRequestPhaseProceedNilFailsOpenToNetwork() {
        WailoBreakpointStore.shared.replace([
            BreakpointRule(id: "r", enabled: true, url_pattern: "https://bp.test/*", on_request: true, on_response: false),
        ])
        // proceed(nil) = resume unchanged: the request must reach the network. The `.test` TLD is
        // reserved and never resolves (RFC 6761), so the surfaced network error is the proof it left
        // the breakpoint path onto the real network rather than being served or held.
        let gate = StubBreakpointGate(requestDecision: .proceed(nil))
        WailoURLProtocol.breakpointGate = gate
        let sink = CapturingSink()
        WailoURLProtocol.sink = sink

        let (_, _, error) = runIntercepted(url: "https://bp.test/go", timeout: 10)

        XCTAssertEqual(gate.requestPauses.count, 1, "a matching request must still pause first")
        XCTAssertNotNil(error, "an unresolvable host must surface a network error, proving fail-open")
    }

    func testBreakpointOwnsExchangeAndMapLocalSuppliesResponse() {
        // Precedence v3 (ADR-0033): a URL matching both a Map Local rule and a breakpoint is *owned* by the
        // breakpoint — both phases fire — and the Map Local value becomes the response the response phase
        // pauses on (no network call). Resuming unchanged delivers that mock.
        WailoRuleStore.shared.replace([
            MapLocalRule(id: "ml", enabled: true, url_pattern: "https://both.test/*") { $0.methods = [] },
        ])
        WailoBreakpointStore.shared.replace([
            BreakpointRule(id: "bp", enabled: true, url_pattern: "https://both.test/*", on_request: true, on_response: true),
        ])
        WailoURLProtocol.bodyFetcher = StubFetcher(response: WailoMappedResponse(
            code: 200,
            headers: [Header(name: "Content-Type", value: "text/plain")],
            body: Data("mapped".utf8)
        ))
        let gate = StubBreakpointGate(requestDecision: .proceed(nil), responseDecision: .proceed(nil))
        WailoURLProtocol.breakpointGate = gate
        WailoURLProtocol.sink = CapturingSink()

        let (data, response, error) = runIntercepted(url: "https://both.test/go")

        XCTAssertNil(error)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertEqual(data.map { String(decoding: $0, as: UTF8.self) }, "mapped")
        XCTAssertEqual(gate.requestPauses.count, 1, "the breakpoint owns the exchange: the request phase fires")
        XCTAssertEqual(gate.responsePauses.count, 1, "the response phase fires on the Map Local value")
        XCTAssertEqual(
            gate.responsePauses.first?.response.body,
            Data("mapped".utf8),
            "the Map Local value must be the response the breakpoint shows"
        )
    }

    func testBreakpointResponsePhaseShowsMapLocalValue() {
        // Precedence v3 (ADR-0033), the user's case: a response-phase breakpoint on a URL that also matches a
        // Map Local rule pauses on the Map Local value (no network), and resuming unchanged delivers it.
        WailoRuleStore.shared.replace([
            MapLocalRule(id: "ml", enabled: true, url_pattern: "https://both.test/*") { $0.methods = [] },
        ])
        WailoBreakpointStore.shared.replace([
            BreakpointRule(id: "bp", enabled: true, url_pattern: "https://both.test/*", on_request: false, on_response: true),
        ])
        WailoURLProtocol.bodyFetcher = StubFetcher(response: WailoMappedResponse(
            code: 200,
            headers: [Header(name: "Content-Type", value: "text/plain")],
            body: Data("mapped".utf8)
        ))
        let gate = StubBreakpointGate(responseDecision: .proceed(nil))
        WailoURLProtocol.breakpointGate = gate
        WailoURLProtocol.sink = CapturingSink()

        let (data, _, error) = runIntercepted(url: "https://both.test/go")

        XCTAssertNil(error)
        XCTAssertTrue(gate.requestPauses.isEmpty, "this rule has no request phase")
        XCTAssertEqual(gate.responsePauses.count, 1)
        XCTAssertEqual(gate.responsePauses.first?.response.body, Data("mapped".utf8))
        XCTAssertEqual(data.map { String(decoding: $0, as: UTF8.self) }, "mapped")
    }

    func testRequestEditThatMatchesMapLocalIsSourcedFromMapLocal() {
        // Precedence v3 (ADR-0033, subsuming ADR-0032): the original URL matches no Map Local rule, so it
        // reaches the request breakpoint, which rewrites the URL to one a Map Local rule *does* match. The
        // response is then sourced from Map Local for the edited request instead of hitting the network.
        WailoRuleStore.shared.replace([
            MapLocalRule(id: "ml", enabled: true, url_pattern: "https://mapped.test/*") { $0.methods = [] },
        ])
        WailoBreakpointStore.shared.replace([
            BreakpointRule(id: "bp", enabled: true, url_pattern: "https://origin.test/*", on_request: true, on_response: false),
        ])
        WailoURLProtocol.bodyFetcher = StubFetcher(response: WailoMappedResponse(
            code: 200,
            headers: [Header(name: "Content-Type", value: "text/plain")],
            body: Data("mapped".utf8)
        ))
        let gate = StubBreakpointGate(requestDecision: .proceed(
            HttpRequest(method: "GET", url: "https://mapped.test/x", body: Data(), body_size: 0, body_truncated: false)
        ))
        WailoURLProtocol.breakpointGate = gate
        WailoURLProtocol.sink = CapturingSink()

        let (data, response, error) = runIntercepted(url: "https://origin.test/go")

        XCTAssertNil(error, "an edited request sourced from Map Local must not surface a network error")
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertEqual(data.map { String(decoding: $0, as: UTF8.self) }, "mapped")
        XCTAssertEqual(gate.requestPauses.count, 1, "the request must have paused and been edited before the re-match")
    }

    // Runs one request through a session that forces `WailoURLProtocol`, returning its result.
    private func runIntercepted(url: String, timeout: TimeInterval = 5) -> (Data?, URLResponse?, Error?) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [WailoURLProtocol.self]
        let session = URLSession(configuration: configuration)
        let done = expectation(description: "request completed")
        var out: (Data?, URLResponse?, Error?) = (nil, nil, nil)
        let task = session.dataTask(with: URL(string: url)!) { data, response, error in
            out = (data, response, error)
            done.fulfill()
        }
        task.resume()
        wait(for: [done], timeout: timeout)
        session.invalidateAndCancel()
        return out
    }

    // The sink is written on the loading thread after the client callback that woke the test thread, so
    // a matching exchange can lag the request's completion by a hair; poll briefly rather than race it.
    private func firstExchange(in sink: CapturingSink, matching url: String, timeout: TimeInterval = 2) -> HttpExchange? {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if let found = sink.exchanges.first(where: { $0.request?.url == url }) { return found }
            Thread.sleep(forTimeInterval: 0.02)
        } while Date() < deadline
        return sink.exchanges.first(where: { $0.request?.url == url })
    }
}

/// Stand-in for the desktop gate. Records the pauses it was asked for and answers with a fixed decision.
/// Thread-safe: the interceptor may call it off-thread.
private final class StubBreakpointGate: WailoBreakpointGate, @unchecked Sendable {
    private let lock = NSLock()
    private var requests: [(ruleId: String, request: HttpRequest)] = []
    private var responses: [(ruleId: String, response: HttpResponse)] = []
    private let requestDecision: WailoRequestDecision
    private let responseDecision: WailoResponseDecision

    init(requestDecision: WailoRequestDecision = .proceed(nil), responseDecision: WailoResponseDecision = .proceed(nil)) {
        self.requestDecision = requestDecision
        self.responseDecision = responseDecision
    }

    var requestPauses: [(ruleId: String, request: HttpRequest)] {
        lock.lock(); defer { lock.unlock() }
        return requests
    }

    var responsePauses: [(ruleId: String, response: HttpResponse)] {
        lock.lock(); defer { lock.unlock() }
        return responses
    }

    func pauseRequest(ruleId: String, request: HttpRequest, completion: @escaping (WailoRequestDecision) -> Void) {
        lock.lock()
        requests.append((ruleId, request))
        let decision = requestDecision
        lock.unlock()
        completion(decision)
    }

    func pauseResponse(
        ruleId: String,
        request: HttpRequest,
        response: HttpResponse,
        completion: @escaping (WailoResponseDecision) -> Void
    ) {
        lock.lock()
        responses.append((ruleId, response))
        let decision = responseDecision
        lock.unlock()
        completion(decision)
    }
}

/// Stand-in for the desktop body fetch (Map Local), answering with a fixed response.
private final class StubFetcher: WailoBodyFetcher, @unchecked Sendable {
    private let response: WailoMappedResponse?
    init(response: WailoMappedResponse?) { self.response = response }
    func fetchBody(ruleId: String, url: String, method: String, completion: @escaping (WailoMappedResponse?) -> Void) {
        completion(response)
    }
}

/// Thread-safe sink for asserting on emitted exchanges.
private final class CapturingSink: CaptureSink, @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [HttpExchange] = []
    var exchanges: [HttpExchange] {
        lock.lock(); defer { lock.unlock() }
        return stored
    }
    func onExchange(_ exchange: HttpExchange) {
        lock.lock()
        stored.append(exchange)
        lock.unlock()
    }
}
