import XCTest
@testable import WailoSDK

/// The gate in front of every address the SDK is handed. Typing an IP together with its port into the
/// panel's address field crashed the host app: `ws://192.168.1.20:8080:8899/` is not a URL Foundation
/// will parse, and the transport force-unwrapped it — then, because the text had already been persisted,
/// the crash returned on every launch. These cases are that bug and its neighbours.
final class WailoAddressTests: XCTestCase {

    func testPlainHostNamesNoPort() {
        let address = WailoAddress("192.168.1.42")
        XCTAssertEqual(address?.host, "192.168.1.42")
        XCTAssertNil(address?.port)
    }

    /// The reported crash: the address field holding `host:port`.
    func testHostCarryingItsPortIsSplit() {
        let address = WailoAddress("192.168.1.42:8080")
        XCTAssertEqual(address?.host, "192.168.1.42")
        XCTAssertEqual(address?.port, 8080)
    }

    /// The invariant the crash violated: anything that parses can be turned into a URL, whatever port
    /// the caller ends up dialling on.
    func testEveryAcceptedAddressFormsAUrl() {
        for text in ["192.168.1.42", "192.168.1.42:8080", "my-mac.local", "localhost", "::1", "[::1]:9000"] {
            guard let address = WailoAddress(text) else {
                XCTFail("\(text) should parse")
                continue
            }
            for port in [1, address.port ?? Wailo.defaultPort, 65_535] {
                XCTAssertNotNil(
                    WailoAddress.webSocketURL(host: address.host, port: port),
                    "\(text) parsed but yields no URL on port \(port)"
                )
            }
        }
    }

    func testSurroundingWhitespaceIsIgnored() {
        XCTAssertEqual(WailoAddress("  192.168.1.42:8080\n")?.description, "192.168.1.42:8080")
    }

    /// Studio prints its address as a URL, so that is what gets pasted back in.
    func testPastedUrlIsAccepted() {
        XCTAssertEqual(WailoAddress("ws://192.168.1.42:8080/")?.description, "192.168.1.42:8080")
        XCTAssertEqual(WailoAddress("http://192.168.1.42")?.description, "192.168.1.42")
    }

    /// A bare IPv6 literal is illegal in a URL authority until it is bracketed, which is a detail nobody
    /// typing an address should have to know.
    func testBareIPv6IsBracketed() {
        XCTAssertEqual(WailoAddress("fe80::1")?.host, "[fe80::1]")
        XCTAssertNil(WailoAddress("fe80::1")?.port)

        let bracketed = WailoAddress("[fe80::1]:9000")
        XCTAssertEqual(bracketed?.host, "[fe80::1]")
        XCTAssertEqual(bracketed?.port, 9000)
    }

    func testUndiallableTextIsRefused() {
        let refused = [
            "",
            "   ",
            "192.168.1.42:80:90",   // doubled port, not an IPv6 literal
            "192.168.1.42:",
            ":8899",
            "192.168.1.42:abc",
            "192.168.1.42:0",
            "192.168.1.42:70000",
            "192.168. 1.42",
            "192.168.1.42/api",
            "user@192.168.1.42",
            "[fe80::1",
        ]
        for text in refused {
            XCTAssertNil(WailoAddress(text), "\(text.debugDescription) must not parse")
        }
    }

    /// The store keeps addresses as text, so the canonical form has to survive a round trip.
    func testDescriptionReparses() {
        for text in ["192.168.1.42", "192.168.1.42:8080", "fe80::1", "[fe80::1]:9000", "ws://my-mac.local:1/"] {
            guard let address = WailoAddress(text) else {
                XCTFail("\(text) should parse")
                continue
            }
            XCTAssertEqual(WailoAddress(address.description), address)
        }
    }
}
