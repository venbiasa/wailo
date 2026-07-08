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
            error: ""
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
        sink.onExchange(HttpExchange(id: "1", started_at_epoch_ms: 0, duration_ms: 0, error: ""))
        XCTAssertEqual(a.count, 1)
        XCTAssertEqual(b.count, 1)
    }
}
