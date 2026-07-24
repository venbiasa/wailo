import XCTest
import Wire
import WailoProtocol
@testable import WailoSDK

final class WailoSDKTests: XCTestCase {

    /// The wire contract: an Envelope encoded on the device must decode back to the same value the
    /// desktop engine expects. Guards against Swift/Kotlin protobuf drift.
    func testEnvelopeRoundTrips() throws {
        let exchange = HttpExchange(
            id: "abc",
            started_at_epoch_ms: 1_000,
            duration_ms: 42,
            error: "",
            edited: false,
            bodies_omitted: false
        ) {
            $0.request = HttpRequest(
                method: "GET",
                url: "https://example.com/x",
                body: Data(),
                body_size: 0,
                body_truncated: false
            ) {
                $0.headers = [Header(name: "Accept", value: "application/json")]
            }
            $0.response = HttpResponse(
                code: 200,
                message: "OK",
                body: Data("hi".utf8),
                body_size: 2,
                body_truncated: false
            )
        }
        let envelope = Envelope { $0.message = .exchange(exchange) }

        let data = try ProtoEncoder().encode(envelope)
        let decoded = try ProtoDecoder().decode(Envelope.self, from: data)

        guard case let .exchange(decodedExchange) = decoded.message else {
            return XCTFail("expected an exchange envelope")
        }
        XCTAssertEqual(decodedExchange, exchange)
    }

    func testHelloRoundTrips() throws {
        let hello = Hello(device_name: "iPhone", app_id: "com.example.app", platform: "ios")
        let data = try ProtoEncoder().encode(Envelope { $0.message = .hello(hello) })
        let decoded = try ProtoDecoder().decode(Envelope.self, from: data)
        XCTAssertEqual(decoded.message, .hello(hello))
    }

    /// Composite sink fans out to every child (the `stream + ConsoleSink()` pattern).
    func testCompositeSinkFansOut() {
        final class CountingSink: CaptureSink, @unchecked Sendable {
            var count = 0
            func onExchange(_ exchange: HttpExchange) { count += 1 }
        }
        let a = CountingSink()
        let b = CountingSink()
        let sink = a + b
        sink.onExchange(HttpExchange(id: "1", started_at_epoch_ms: 0, duration_ms: 0, error: "", edited: false, bodies_omitted: false))
        XCTAssertEqual(a.count, 1)
        XCTAssertEqual(b.count, 1)
    }

    /// The bodies_omitted flag rides the wire like any other field, so a metadata-only capture decodes
    /// back as metadata-only on the desktop.
    func testBodiesOmittedRoundTrips() throws {
        let exchange = HttpExchange(
            id: "m1",
            started_at_epoch_ms: 5,
            duration_ms: 1,
            error: "",
            edited: false,
            bodies_omitted: true
        )
        let data = try ProtoEncoder().encode(Envelope { $0.message = .exchange(exchange) })
        let decoded = try ProtoDecoder().decode(Envelope.self, from: data)
        guard case let .exchange(decodedExchange) = decoded.message else {
            return XCTFail("expected an exchange envelope")
        }
        XCTAssertTrue(decodedExchange.bodies_omitted)
    }

    // MARK: - Capture allowlist (WailoCaptureConfigStore)

    /// Only hosts on the allowlist have their bodies captured; `*` is a wildcard and matching is
    /// case-insensitive. An empty allowlist (the default) and a nil/empty host never match.
    func testCaptureConfigStoreMatching() {
        let store = WailoCaptureConfigStore()

        // Empty allowlist: nothing is unlocked.
        XCTAssertFalse(store.isBodyAllowed(host: "example.com"))

        store.replace(["example.com", "*.api.test"])
        XCTAssertTrue(store.isBodyAllowed(host: "example.com"))
        // Exact match only — a subdomain of a non-wildcard entry is not covered.
        XCTAssertFalse(store.isBodyAllowed(host: "www.example.com"))
        // Wildcard covers any subdomain run.
        XCTAssertTrue(store.isBodyAllowed(host: "v1.api.test"))
        XCTAssertTrue(store.isBodyAllowed(host: "a.b.api.test"))
        // Host comparison is case-insensitive.
        XCTAssertTrue(store.isBodyAllowed(host: "EXAMPLE.com"))
        // Unrelated host, nil, and empty never match.
        XCTAssertFalse(store.isBodyAllowed(host: "other.test"))
        XCTAssertFalse(store.isBodyAllowed(host: nil))
        XCTAssertFalse(store.isBodyAllowed(host: ""))

        // A fresh snapshot replaces wholesale (no merge): the old entry is gone.
        store.replace(["only.test"])
        XCTAssertFalse(store.isBodyAllowed(host: "example.com"))
        XCTAssertTrue(store.isBodyAllowed(host: "only.test"))
    }

    /// The interceptor's body-gating: a locked host captures no bytes (metadata only); an unlocked host
    /// captures the full body, and with no cap (the default) it is never truncated.
    func testBodyGatingOmitsForLockedCapturesForUnlocked() {
        let body = Data(repeating: 0x41, count: 6 * 1024 * 1024) // 6 MB

        // Locked host: no bytes captured, not flagged truncated (it was omitted, not cut).
        let (lockedBytes, lockedTruncated) = WailoURLProtocol.capturedBody(body, allowed: false, cap: nil)
        XCTAssertEqual(lockedBytes.count, 0)
        XCTAssertFalse(lockedTruncated)

        // Unlocked host, no cap: the whole 6 MB body is captured untruncated.
        let (unlockedBytes, unlockedTruncated) = WailoURLProtocol.capturedBody(body, allowed: true, cap: nil)
        XCTAssertEqual(unlockedBytes.count, body.count)
        XCTAssertFalse(unlockedTruncated)

        // A cap still truncates an unlocked host's oversized body.
        let (cappedBytes, cappedTruncated) = WailoURLProtocol.capturedBody(body, allowed: true, cap: 1024)
        XCTAssertEqual(cappedBytes.count, 1024)
        XCTAssertTrue(cappedTruncated)
    }

    /// Reading the request body: URLSession moves httpBody into httpBodyStream before the interceptor
    /// runs, so readBody must drain the stream — otherwise request bodies are always captured empty.
    func testReadBodyDrainsHttpBodyStream() {
        let payload = Data(repeating: 0x42, count: 3 * 1024 * 1024 + 7) // spans multiple read chunks

        // The httpBody case: returned as-is.
        var withBody = URLRequest(url: URL(string: "https://api.test/upload")!)
        withBody.httpBody = payload
        XCTAssertEqual(WailoURLProtocol.readBody(from: withBody), payload)

        // The URLSession case: body only reachable via httpBodyStream — must be fully drained.
        var withStream = URLRequest(url: URL(string: "https://api.test/upload")!)
        withStream.httpBodyStream = InputStream(data: payload)
        XCTAssertNil(withStream.httpBody, "precondition: a streamed body leaves httpBody nil")
        XCTAssertEqual(WailoURLProtocol.readBody(from: withStream), payload)

        // No body: nil (so capture records an empty body, not zero bytes of a phantom one).
        let noBody = URLRequest(url: URL(string: "https://api.test/get")!)
        XCTAssertNil(WailoURLProtocol.readBody(from: noBody))
    }
}
