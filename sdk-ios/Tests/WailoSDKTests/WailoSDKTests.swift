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
            edited: false
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
        sink.onExchange(HttpExchange(id: "1", started_at_epoch_ms: 0, duration_ms: 0, error: "", edited: false))
        XCTAssertEqual(a.count, 1)
        XCTAssertEqual(b.count, 1)
    }

    // MARK: - Capture filter (WailoCaptureFilterStore)

    /// The default (both lists off) captures everything, including a nil/empty host. `*` is a wildcard
    /// and host matching is case-insensitive.
    func testCaptureFilterDefaultCapturesEverything() {
        let store = WailoCaptureFilterStore()
        XCTAssertTrue(store.shouldCapture(host: "example.com"))
        XCTAssertTrue(store.shouldCapture(host: "anything.test"))
        XCTAssertTrue(store.shouldCapture(host: nil))
        XCTAssertTrue(store.shouldCapture(host: ""))
    }

    /// An enabled allowlist captures only matching hosts; `*` covers a subdomain run and matching is
    /// case-insensitive. An enabled-but-empty allowlist matches nothing (captures nothing). A nil/empty
    /// host can't match a real pattern.
    func testCaptureFilterAllowlist() {
        let store = WailoCaptureFilterStore()
        store.replace(CaptureFilter(allowlist_enabled: true, blocklist_enabled: false, epoch: 1) {
            $0.allow_patterns = ["example.com", "*.api.test"]
        })
        XCTAssertTrue(store.shouldCapture(host: "example.com"))
        XCTAssertTrue(store.shouldCapture(host: "EXAMPLE.com"))
        XCTAssertTrue(store.shouldCapture(host: "v1.api.test"))
        // Exact match only — a subdomain of a non-wildcard entry is not covered.
        XCTAssertFalse(store.shouldCapture(host: "www.example.com"))
        XCTAssertFalse(store.shouldCapture(host: "other.test"))
        XCTAssertFalse(store.shouldCapture(host: nil))

        // An enabled allowlist with no patterns matches nothing.
        store.replace(CaptureFilter(allowlist_enabled: true, blocklist_enabled: false, epoch: 2))
        XCTAssertFalse(store.shouldCapture(host: "example.com"))
    }

    /// An enabled blocklist drops matching hosts and captures everything else. An enabled-but-empty
    /// blocklist blocks nothing.
    func testCaptureFilterBlocklist() {
        let store = WailoCaptureFilterStore()
        store.replace(CaptureFilter(allowlist_enabled: false, blocklist_enabled: true, epoch: 1) {
            $0.block_patterns = ["*.tracker.test"]
        })
        XCTAssertFalse(store.shouldCapture(host: "a.tracker.test"))
        XCTAssertTrue(store.shouldCapture(host: "example.com"))

        // An enabled blocklist with no patterns blocks nothing.
        store.replace(CaptureFilter(allowlist_enabled: false, blocklist_enabled: true, epoch: 2))
        XCTAssertTrue(store.shouldCapture(host: "a.tracker.test"))
    }

    /// With both lists on a host must be allowed AND not blocked, so the blocklist can carve an exception
    /// out of the allowlist.
    func testCaptureFilterAllowAndBlockCombined() {
        let store = WailoCaptureFilterStore()
        store.replace(CaptureFilter(allowlist_enabled: true, blocklist_enabled: true, epoch: 1) {
            $0.allow_patterns = ["*.example.com"]
            $0.block_patterns = ["secret.example.com"]
        })
        XCTAssertTrue(store.shouldCapture(host: "api.example.com"))
        // Allowed by the allowlist but carved out by the blocklist.
        XCTAssertFalse(store.shouldCapture(host: "secret.example.com"))
        // Not on the allowlist at all.
        XCTAssertFalse(store.shouldCapture(host: "other.test"))
    }

    /// `reset` (called on disconnect) drops the pushed filter back to capture-everything, so no
    /// user-authored list outlives the connection.
    func testCaptureFilterResetFallsBackToCaptureEverything() {
        let store = WailoCaptureFilterStore()
        store.replace(CaptureFilter(allowlist_enabled: true, blocklist_enabled: false, epoch: 1) {
            $0.allow_patterns = ["example.com"]
        })
        XCTAssertFalse(store.shouldCapture(host: "other.test"))
        store.reset()
        XCTAssertTrue(store.shouldCapture(host: "other.test"))
    }

    /// Once a host passes the filter, `capturedBody` returns the full body with no cap (the default) and
    /// truncates only when a cap is set.
    func testCapturedBodyRespectsCap() {
        let body = Data(repeating: 0x41, count: 6 * 1024 * 1024) // 6 MB

        // No cap: the whole 6 MB body is captured untruncated.
        let (fullBytes, fullTruncated) = WailoURLProtocol.capturedBody(body, cap: nil)
        XCTAssertEqual(fullBytes.count, body.count)
        XCTAssertFalse(fullTruncated)

        // A cap truncates an oversized body.
        let (cappedBytes, cappedTruncated) = WailoURLProtocol.capturedBody(body, cap: 1024)
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
