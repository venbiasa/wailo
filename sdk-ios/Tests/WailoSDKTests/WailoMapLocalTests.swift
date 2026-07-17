import XCTest
import Foundation
import WailoProtocol
@testable import WailoSDK

/// Verifies the device-side Map Local flow under the lazy-body model (ADR-0019): a match yields only
/// the rule id, the interceptor fetches the body from the desktop via a `WailoBodyFetcher`, serves it
/// (flagging the exchange `edited`), and falls open to the real network when the fetch returns nil.
final class WailoMapLocalTests: XCTestCase {

    override func tearDown() {
        WailoRuleStore.shared.replace([])
        WailoURLProtocol.sink = nil
        WailoURLProtocol.bodyFetcher = nil
        super.tearDown()
    }

    func testMatchingRuleServesFetchedBodyWithoutNetwork() {
        let bodyText = "hello from disk"
        let rule = MapLocalRule(id: "r1", enabled: true, url_pattern: "https://maplocal.test/*") {
            $0.methods = []
        }
        WailoRuleStore.shared.replace([rule])

        // The fetcher stands in for the desktop: it returns the body a real BodyResponse would carry.
        let fetcher = StubFetcher(response: WailoMappedResponse(
            code: 200,
            headers: [Header(name: "Content-Type", value: "text/plain")],
            body: Data(bodyText.utf8)
        ))
        WailoURLProtocol.bodyFetcher = fetcher

        let sink = CapturingSink()
        WailoURLProtocol.sink = sink

        let configuration = URLSessionConfiguration.ephemeral
        // Force our protocol on this session; the target host doesn't exist, so any real network
        // attempt would fail — a successful body proves the match short-circuited it.
        configuration.protocolClasses = [WailoURLProtocol.self]
        let session = URLSession(configuration: configuration)

        let done = expectation(description: "request completed")
        var receivedBody: String?
        var statusCode: Int?
        var requestError: Error?
        let task = session.dataTask(with: URL(string: "https://maplocal.test/hello")!) { data, response, error in
            receivedBody = data.map { String(decoding: $0, as: UTF8.self) }
            statusCode = (response as? HTTPURLResponse)?.statusCode
            requestError = error
            done.fulfill()
        }
        task.resume()
        wait(for: [done], timeout: 5)
        session.invalidateAndCancel()

        XCTAssertNil(requestError)
        XCTAssertEqual(statusCode, 200)
        XCTAssertEqual(receivedBody, bodyText)

        // The interceptor must have asked the desktop for the matched rule's body.
        XCTAssertEqual(fetcher.requests.first?.ruleId, "r1")
        XCTAssertEqual(fetcher.requests.first?.method, "GET")

        let mapped = sink.exchanges.first { $0.request?.url == "https://maplocal.test/hello" }
        XCTAssertNotNil(mapped, "the substituted exchange should be mirrored to the sink")
        XCTAssertTrue(mapped?.edited ?? false, "a mapped exchange must be flagged edited")
        XCTAssertEqual(mapped?.response?.code, 200)
    }

    func testFailsOpenToNetworkWhenFetchReturnsNil() {
        let rule = MapLocalRule(id: "r1", enabled: true, url_pattern: "https://maplocal.test/*") {
            $0.methods = []
        }
        WailoRuleStore.shared.replace([rule])

        // Fetch returns nil (desktop couldn't serve): the interceptor must fall open to the real
        // network rather than serve or hang. The host is unresolvable, so the network attempt errors —
        // that error (not a served body) is the proof the request left the Map Local path.
        let fetcher = StubFetcher(response: nil)
        WailoURLProtocol.bodyFetcher = fetcher

        let sink = CapturingSink()
        WailoURLProtocol.sink = sink

        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [WailoURLProtocol.self]
        let session = URLSession(configuration: configuration)

        let done = expectation(description: "request completed")
        var receivedData: Data?
        var requestError: Error?
        let task = session.dataTask(with: URL(string: "https://maplocal.test/hello")!) { data, _, error in
            receivedData = data
            requestError = error
            done.fulfill()
        }
        task.resume()
        wait(for: [done], timeout: 10)
        session.invalidateAndCancel()

        XCTAssertEqual(fetcher.requests.first?.ruleId, "r1", "a match must still ask the desktop first")
        XCTAssertNotNil(requestError, "an unresolvable host must surface a network error, proving fail-open")
        XCTAssertTrue(receivedData?.isEmpty ?? true, "nothing should have been served from Map Local")
        let mapped = sink.exchanges.first { $0.request?.url == "https://maplocal.test/hello" }
        XCTAssertFalse(mapped?.edited ?? false, "a fell-open request must not be flagged edited")
    }

    func testNonMatchingMethodIsNotMatched() {
        let rule = MapLocalRule(id: "r1", enabled: true, url_pattern: "https://maplocal.test/*") {
            $0.methods = ["POST"]
        }
        WailoRuleStore.shared.replace([rule])
        // A GET must not match a POST-only rule.
        XCTAssertNil(WailoRuleStore.shared.match(url: "https://maplocal.test/hello", method: "GET"))
        XCTAssertNotNil(WailoRuleStore.shared.match(url: "https://maplocal.test/hello", method: "POST"))
    }
}

/// Stand-in for the desktop body fetch. Records what it was asked for and answers with a fixed
/// response (or nil to exercise fail-open). Thread-safe: the interceptor may call it off-thread.
private final class StubFetcher: WailoBodyFetcher, @unchecked Sendable {
    private let lock = NSLock()
    private let response: WailoMappedResponse?
    private var recorded: [(ruleId: String, url: String, method: String)] = []

    var requests: [(ruleId: String, url: String, method: String)] {
        lock.lock(); defer { lock.unlock() }
        return recorded
    }

    init(response: WailoMappedResponse?) {
        self.response = response
    }

    func fetchBody(ruleId: String, url: String, method: String, completion: @escaping (WailoMappedResponse?) -> Void) {
        lock.lock()
        recorded.append((ruleId, url, method))
        let answer = response
        lock.unlock()
        completion(answer)
    }
}

/// Thread-safe sink: `onExchange` fires on URLSession's callback thread while the test reads the list.
private final class CapturingSink: CaptureSink, @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [HttpExchange] = []

    var exchanges: [HttpExchange] {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }

    func onExchange(_ exchange: HttpExchange) {
        lock.lock()
        stored.append(exchange)
        lock.unlock()
    }
}
