import CryptoKit
import XCTest
@testable import WailoSDK

/// What the device accepts as a pairing invite.
///
/// This is the only parser that runs before anything is authenticated — a QR is whatever was pointed
/// at the camera — so every rejection here is load-bearing.
final class WailoPairingInviteTests: XCTestCase {

    private static let secret = Data((0..<32).map { UInt8($0) })
    private static let publicKey = P256.Signing.PrivateKey().publicKey.x963Representation

    private static func qr(
        sid: String? = nil,
        host: String = "192.168.1.20",
        port: Int = 8899,
        key: Data? = publicKey,
        secret: Data = secret
    ) -> String {
        let studioId = sid ?? WailoCrypto.studioId(publicKey: key ?? publicKey)
        var text = "wailo://pair?sid=\(studioId)&h=\(host)&p=\(port)&s=\(secret.base64URL)"
        if let key { text += "&k=\(key.base64URL)" }
        return text
    }

    func testParsesAFullInvite() throws {
        let invite = try XCTUnwrap(WailoPairingInvite(qr: Self.qr()))
        XCTAssertEqual(invite.host, "192.168.1.20")
        XCTAssertEqual(invite.port, 8899)
        XCTAssertEqual(invite.pairingSecret, Self.secret)
        XCTAssertEqual(invite.publicKey, Self.publicKey)
        XCTAssertFalse(invite.pairedByCode)
    }

    /// `sid` is defined as the fingerprint of the key. An invite claiming otherwise is malformed, and
    /// accepting it would mean pinning a key the device could never match against an advertisement.
    func testRejectsAKeyThatDoesNotHashToTheClaimedIdentity() {
        XCTAssertNil(WailoPairingInvite(qr: Self.qr(sid: String(repeating: "a", count: 32))))
    }

    func testRejectsForeignSchemesAndHosts() {
        XCTAssertNil(WailoPairingInvite(qr: "https://example.com/pair?sid=x"))
        XCTAssertNil(WailoPairingInvite(qr: "wailo://connect?sid=x"))
        XCTAssertNil(WailoPairingInvite(qr: "not a url at all"))
    }

    func testRejectsAnUndersizedSecret() {
        XCTAssertNil(WailoPairingInvite(qr: Self.qr(secret: Data(repeating: 7, count: 16))))
    }

    func testRejectsAnOutOfRangePort() {
        XCTAssertNil(WailoPairingInvite(qr: Self.qr(port: 0)))
        XCTAssertNil(WailoPairingInvite(qr: Self.qr(port: 70000)))
    }

    /// A Studio too old to put its key in the QR still pairs; the key is then pinned from the
    /// challenge, which is only safe because Studio has to prove the secret first.
    func testAcceptsAnInviteWithNoKey() throws {
        let invite = try XCTUnwrap(WailoPairingInvite(qr: Self.qr(key: nil)))
        XCTAssertNil(invite.publicKey)
    }

    func testTypedCodeDerivesADifferentSecretThanTheQrPath() throws {
        let studioId = WailoCrypto.studioId(publicKey: Self.publicKey)
        let invite = try XCTUnwrap(
            WailoPairingInvite(code: "abcde-fghjk", studioId: studioId, host: "10.0.0.2", port: 8899)
        )
        XCTAssertTrue(invite.pairedByCode)
        XCTAssertNil(invite.publicKey)
        XCTAssertEqual(invite.pairingSecret, WailoCrypto.stretch(code: "ABCDEFGHJK", studioId: studioId))
    }

    func testTypedCodeRejectsTheAmbiguousLetters() {
        let studioId = WailoCrypto.studioId(publicKey: Self.publicKey)
        for ambiguous in ["I", "L", "O", "U"] {
            XCTAssertNil(
                WailoPairingInvite(code: "ABCDEFGH" + ambiguous + "K", studioId: studioId, host: "h", port: 1),
                "\(ambiguous) is not in the alphabet and must not be accepted"
            )
        }
    }
}

/// The entry field filters keystrokes with `sanitize` but the handshake derives from `normalize`. If
/// those two ever disagree the field accepts input that fails to pair with no visible reason.
final class WailoPairingCodeTests: XCTestCase {

    func testSanitizeOutputAtFullLengthIsAlwaysAcceptedByNormalize() {
        let messy = "ab-cd ef!gh@jk"
        let cleaned = WailoPairingCode.sanitize(messy)
        XCTAssertEqual(cleaned.count, WailoPairingCode.length)
        XCTAssertEqual(WailoPairingCode.normalize(cleaned), cleaned)
    }

    func testSanitizeCapsAtTheCodeLength() {
        XCTAssertEqual(WailoPairingCode.sanitize(String(repeating: "A", count: 40)).count, 10)
    }

    func testSanitizeDropsTheAmbiguousLetters() {
        XCTAssertEqual(WailoPairingCode.sanitize("ILOU"), "")
    }

    /// Grouping is presentation; both sides derive from the stripped form.
    func testNormalizeAcceptsTheGroupingShownOnScreen() {
        XCTAssertEqual(WailoPairingCode.normalize("abcde-fghjk"), "ABCDEFGHJK")
        XCTAssertEqual(WailoPairingCode.normalize("ABCDE FGHJK"), "ABCDEFGHJK")
    }

    func testNormalizeRejectsTheWrongLength() {
        XCTAssertNil(WailoPairingCode.normalize("ABCDEFGHJ"))
        XCTAssertNil(WailoPairingCode.normalize("ABCDEFGHJKM"))
    }
}
