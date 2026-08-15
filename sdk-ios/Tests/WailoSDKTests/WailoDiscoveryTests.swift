import Network
import XCTest
@testable import WailoSDK

final class WailoDiscoveryTests: XCTestCase {

    func testBrowserRequestsStudioIdentityTxtRecords() {
        guard case let .bonjourWithTXTRecord(type, domain) = WailoDiscovery.browserDescriptor else {
            return XCTFail("plain Bonjour browsing never populates result metadata")
        }
        XCTAssertEqual(type, WailoDiscovery.serviceType)
        XCTAssertNil(domain)
    }

    func testLateTxtIdentityUpdatesAnAlreadyResolvedService() throws {
        let resolvedBeforeMetadata = WailoService(
            name: "Studio",
            host: "192.168.1.20",
            port: 8899,
            studioId: ""
        )

        let updated = try XCTUnwrap(WailoDiscovery.replacingStudioId(
            in: resolvedBeforeMetadata,
            with: "00112233445566778899aabbccddeeff"
        ))

        XCTAssertEqual(updated.name, resolvedBeforeMetadata.name)
        XCTAssertEqual(updated.host, resolvedBeforeMetadata.host)
        XCTAssertEqual(updated.port, resolvedBeforeMetadata.port)
        XCTAssertEqual(updated.studioId, "00112233445566778899aabbccddeeff")
        XCTAssertNil(WailoDiscovery.replacingStudioId(in: updated, with: updated.studioId))
    }

    func testReresolutionReplacesAStaleStudioAddress() throws {
        let stale = WailoService(
            name: "Studio",
            host: "192.168.1.20",
            port: 8899,
            studioId: "00112233445566778899aabbccddeeff"
        )

        let moved = try XCTUnwrap(WailoDiscovery.replacingRoute(
            in: stale,
            host: "192.168.1.41",
            port: stale.port,
            studioId: stale.studioId
        ))

        XCTAssertEqual(moved.host, "192.168.1.41")
        XCTAssertEqual(moved.studioId, stale.studioId)
        XCTAssertNil(WailoDiscovery.replacingRoute(
            in: moved,
            host: moved.host,
            port: moved.port,
            studioId: moved.studioId
        ))
    }
}
