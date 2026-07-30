import XCTest
@testable import WailoSDK

/// The persistence contract behind runtime host switching (ADR-0035). If a stored address doesn't
/// survive a read — or a cleared one keeps coming back — the device silently keeps dialling the wrong
/// Mac, which looks identical to "the desktop isn't running".
final class WailoHostStoreTests: XCTestCase {

    override func setUp() {
        super.setUp()
        WailoHostStore.clear()
    }

    override func tearDown() {
        WailoHostStore.clear()
        super.tearDown()
    }

    func testUnsetHostMeansDiscovery() {
        XCTAssertNil(WailoHostStore.host)
        XCTAssertNil(WailoHostStore.port)
    }

    func testHostRoundTrips() {
        WailoHostStore.host = "192.168.1.42"
        XCTAssertEqual(WailoHostStore.host, "192.168.1.42")
    }

    /// The address arrives from a text field, so surrounding whitespace is a matter of when the user
    /// stopped typing — never a different host.
    func testHostIsTrimmed() {
        WailoHostStore.host = "  192.168.1.42\n"
        XCTAssertEqual(WailoHostStore.host, "192.168.1.42")
    }

    /// An emptied field means "stop pinning", not "dial the empty string" — which would build the
    /// nonsense URL `ws://:8899/`.
    func testBlankHostReadsAsUnset() {
        WailoHostStore.host = "192.168.1.42"
        WailoHostStore.host = "   "
        XCTAssertNil(WailoHostStore.host)
    }

    func testClearingHostRestoresDiscovery() {
        WailoHostStore.host = "192.168.1.42"
        WailoHostStore.host = nil
        XCTAssertNil(WailoHostStore.host)
    }

    func testPortRoundTrips() {
        WailoHostStore.port = 9001
        XCTAssertEqual(WailoHostStore.port, 9001)
    }

    /// `UserDefaults.integer(forKey:)` returns 0 for a missing key, so an out-of-range value has to read
    /// as unset rather than as port 0.
    func testOutOfRangePortReadsAsUnset() {
        WailoHostStore.port = 70_000
        XCTAssertNil(WailoHostStore.port)

        WailoHostStore.port = 0
        XCTAssertNil(WailoHostStore.port)
    }
}
