import CryptoKit
import XCTest
@testable import WailoSDK

/// The handshake's byte layouts (ADR-0039), pinned as vectors.
///
/// `engine` and `sdk-android` reimplement all of this against `java.security` — the two builds cannot
/// share code — so the only thing keeping them interoperable is that both sides assert the *same*
/// constants. `WailoCryptoTest.kt` on the Kotlin side holds these identical strings; if either drifts,
/// paired devices stop authenticating with nothing but a silent handshake failure to go on.
final class WailoCryptoTests: XCTestCase {

    // Fixed inputs. Arbitrary, but they must never change.
    static let pairingSecret = Data((0..<32).map { UInt8($0) })
    static let studioId = "0123456789abcdef0123456789abcdef"
    static let deviceId = "device-0001"
    static let nonceD = Data((0..<32).map { UInt8(0x40 + $0) })
    static let nonceS = Data((0..<32).map { UInt8(0x60 + $0) })
    /// Fixed agreement scalars, so the derived point and every key below it are reproducible on both
    /// platforms. Real ones are random per connection; these exist only to be asserted.
    static let scalarD = Data((1...32).map { UInt8($0) })
    static let scalarS = Data((33...64).map { UInt8($0) })

    private var deviceKey: SymmetricKey {
        WailoCrypto.deviceKey(
            pairingSecret: Self.pairingSecret, studioId: Self.studioId, deviceId: Self.deviceId
        )
    }

    private var ephemeralD: Data {
        get throws { try P256.KeyAgreement.PrivateKey(rawRepresentation: Self.scalarD).publicKey.x963Representation }
    }

    private var ephemeralS: Data {
        get throws { try P256.KeyAgreement.PrivateKey(rawRepresentation: Self.scalarS).publicKey.x963Representation }
    }

    private var shared: Data {
        get throws {
            let device = try P256.KeyAgreement.PrivateKey(rawRepresentation: Self.scalarD)
            return try XCTUnwrap(WailoCrypto.agree(device, peer: ephemeralS))
        }
    }

    func testDeviceKeyVector() {
        XCTAssertEqual(
            deviceKey.bytes.hexadecimal,
            "0f5f735ac79ff7b5174adb12a2d175eeba55e307d27c4c30d52c879bef311c44"
        )
    }

    /// The agreed secret, and the two public points it was agreed from. Everything else in this file
    /// hangs off these three, so a platform that disagrees here disagrees about all of it.
    func testKeyAgreementVector() throws {
        XCTAssertEqual(try ephemeralD.hexadecimal, "04515c3d6eb9e396b904d3feca7f54fdcd0cc1e997bf375dca515ad0a6c3b4035f4536be3a50f318fbf9a5475902a221502bef0d57e08c53b2cc0a56f17d9f9354")
        XCTAssertEqual(try ephemeralS.hexadecimal, "041f140146bfb1b251f84f4ddbe0d4cdcfd77afd984a9520e35794021f8312bb9eec995a08b1fa7704df3dcc0b50a9665263fb7711f95f9f8a449c5096e47c892b")
        XCTAssertEqual(try shared.hexadecimal, "4fe243908f378aa1c2a69538822e6ed908c3225d8692575507c649901245150a")
    }

    /// Both ends of an agreement reach the same secret, whichever side holds which scalar.
    func testAgreementIsSymmetric() throws {
        let device = try P256.KeyAgreement.PrivateKey(rawRepresentation: Self.scalarD)
        let studio = try P256.KeyAgreement.PrivateKey(rawRepresentation: Self.scalarS)
        XCTAssertEqual(
            WailoCrypto.agree(device, peer: try ephemeralS),
            WailoCrypto.agree(studio, peer: try ephemeralD)
        )
    }

    /// A point that is not on P-256 must not agree to anything — that is the invalid-curve attack,
    /// where the answers leak the private scalar.
    func testOffCurvePointIsRefused() throws {
        let device = try P256.KeyAgreement.PrivateKey(rawRepresentation: Self.scalarD)
        var bogus = try ephemeralS
        bogus[1] ^= 0x01
        XCTAssertNil(WailoCrypto.agree(device, peer: bogus))
        XCTAssertNil(WailoCrypto.agree(device, peer: Data()))
    }

    /// A first contact has no long-term key, so the session rests on the agreement alone.
    func testTofuDeviceKeyVector() throws {
        XCTAssertEqual(
            try WailoCrypto.tofuDeviceKey(
                shared: shared, studioId: Self.studioId, deviceId: Self.deviceId
            ).bytes.hexadecimal,
            "b504b4985c3055c9b4d07e9800b8d3234b802ba3dcf1fafea26fabd877cdd1df"
        )
    }

    func testAuthKeyVector() throws {
        XCTAssertEqual(
            try WailoCrypto.authKey(shared: shared, deviceKey: nil).bytes.hexadecimal,
            "37cfb80bf35ea202e79894afc5babc35aac2d604e6eea05deffe0ea50473a834"
        )
        XCTAssertEqual(
            try WailoCrypto.authKey(shared: shared, deviceKey: deviceKey).bytes.hexadecimal,
            "28c5cb8ca5bcc0ab7327bf87088f4e756578abb3e14fdda32d9e4bb509c5153b"
        )
    }

    func testSessionKeyVector() throws {
        XCTAssertEqual(
            try WailoCrypto.sessionKey(
                shared: shared, deviceKey: nil, nonceD: Self.nonceD, nonceS: Self.nonceS
            ).bytes.hexadecimal,
            "feebb90e8f30121c043444b0eb3c4a907d9622a8c3144e532311cf44e289a403"
        )
        XCTAssertEqual(
            try WailoCrypto.sessionKey(
                shared: shared, deviceKey: deviceKey, nonceD: Self.nonceD, nonceS: Self.nonceS
            ).bytes.hexadecimal,
            "895b7888dbd5f197933cd84537169fa3e6aa2aa140660d489054ba6cd3d8c69b"
        )
    }

    func testDeviceProofVector() throws {
        XCTAssertEqual(
            try WailoCrypto.deviceProof(
                authKey: WailoCrypto.authKey(shared: shared, deviceKey: deviceKey),
                studioId: Self.studioId, nonceD: Self.nonceD, nonceS: Self.nonceS,
                ephemeralD: ephemeralD, ephemeralS: ephemeralS
            ).hexadecimal,
            "e4556159809a931fd61e10aa8252efcabfdd116d465ee347ad5a5b697dbf34a9"
        )
    }

    func testStudioMacVector() throws {
        XCTAssertEqual(
            try WailoCrypto.studioMac(
                authKey: WailoCrypto.authKey(shared: shared, deviceKey: deviceKey),
                studioId: Self.studioId, nonceD: Self.nonceD, nonceS: Self.nonceS,
                ephemeralD: ephemeralD, ephemeralS: ephemeralS
            ).hexadecimal,
            "e369baf64bccd686dda614f3cf30ace730fb45944826f91419d9ede32b4d4199"
        )
    }

    /// Swapping an ephemeral key must change what Studio signed over, which is what makes substituting
    /// one detectable rather than a silent man in the middle.
    func testTranscriptCoversEphemeralKeys() throws {
        let baseline = try WailoCrypto.transcript(
            role: "wailo/studio", studioId: Self.studioId,
            nonceD: Self.nonceD, nonceS: Self.nonceS, ephemeralD: ephemeralD, ephemeralS: ephemeralS
        )
        let swapped = try WailoCrypto.transcript(
            role: "wailo/studio", studioId: Self.studioId,
            nonceD: Self.nonceD, nonceS: Self.nonceS, ephemeralD: ephemeralD, ephemeralS: ephemeralD
        )
        XCTAssertNotEqual(baseline, swapped)
    }

    /// Studio's identity is a hash of the key, which is what lets a device reject an impostor that
    /// merely advertises the right `sid`.
    func testStudioIdIsTheKeyFingerprint() {
        let key = Data((0..<65).map { UInt8($0) })
        XCTAssertEqual(WailoCrypto.studioId(publicKey: key), "4bfd2c8b6f1eec7a2afeb48b934ee4b2")
    }

    func testSealedFrameVector() throws {
        let codec = WailoFrameCodec(
            sessionKey: SymmetricKey(data: Data(repeating: 0x2a, count: 32)),
            sealing: .deviceToStudio,
            opening: .studioToDevice
        )
        let sealed = try codec.seal(Data("wailo".utf8))
        XCTAssertEqual(sealed.seq, 0)
        XCTAssertEqual(sealed.ciphertext.hexadecimal, "421a8d16df530fe3dcd75e18d19e0485ab9184d7e6")
    }

    /// The two directions must never share a nonce under one session key — that is the failure GCM
    /// does not survive.
    func testDirectionsProduceDifferentCiphertext() throws {
        let key = SymmetricKey(data: Data(repeating: 0x2a, count: 32))
        let device = WailoFrameCodec(sessionKey: key, sealing: .deviceToStudio, opening: .studioToDevice)
        let studio = WailoFrameCodec(sessionKey: key, sealing: .studioToDevice, opening: .deviceToStudio)
        let plaintext = Data("wailo".utf8)
        XCTAssertNotEqual(try device.seal(plaintext).ciphertext, try studio.seal(plaintext).ciphertext)
    }

    func testRoundTripAcrossPeers() throws {
        let key = SymmetricKey(data: Data(repeating: 0x11, count: 32))
        let device = WailoFrameCodec(sessionKey: key, sealing: .deviceToStudio, opening: .studioToDevice)
        let studio = WailoFrameCodec(sessionKey: key, sealing: .studioToDevice, opening: .deviceToStudio)

        for index in 0..<4 {
            let payload = Data("frame-\(index)".utf8)
            let sealed = try device.seal(payload)
            XCTAssertEqual(try studio.open(seq: sealed.seq, ciphertext: sealed.ciphertext), payload)
        }
    }

    func testReplayedFrameIsRejected() throws {
        let key = SymmetricKey(data: Data(repeating: 0x11, count: 32))
        let device = WailoFrameCodec(sessionKey: key, sealing: .deviceToStudio, opening: .studioToDevice)
        let studio = WailoFrameCodec(sessionKey: key, sealing: .studioToDevice, opening: .deviceToStudio)

        let sealed = try device.seal(Data("once".utf8))
        _ = try studio.open(seq: sealed.seq, ciphertext: sealed.ciphertext)
        XCTAssertThrowsError(try studio.open(seq: sealed.seq, ciphertext: sealed.ciphertext))
    }

    func testAlteredCiphertextIsRejected() throws {
        let key = SymmetricKey(data: Data(repeating: 0x11, count: 32))
        let device = WailoFrameCodec(sessionKey: key, sealing: .deviceToStudio, opening: .studioToDevice)
        let studio = WailoFrameCodec(sessionKey: key, sealing: .studioToDevice, opening: .deviceToStudio)

        var sealed = try device.seal(Data("tamper me".utf8))
        sealed.ciphertext[0] ^= 0x01
        XCTAssertThrowsError(try studio.open(seq: sealed.seq, ciphertext: sealed.ciphertext))
    }

    /// `seq` travels in the clear, so moving it must break the open rather than silently decrypt.
    func testAlteredSequenceIsRejected() throws {
        let key = SymmetricKey(data: Data(repeating: 0x11, count: 32))
        let device = WailoFrameCodec(sessionKey: key, sealing: .deviceToStudio, opening: .studioToDevice)
        let studio = WailoFrameCodec(sessionKey: key, sealing: .studioToDevice, opening: .deviceToStudio)

        _ = try device.seal(Data("first".utf8))
        let sealed = try device.seal(Data("second".utf8))
        XCTAssertThrowsError(try studio.open(seq: sealed.seq + 5, ciphertext: sealed.ciphertext))
    }

    func testStretchedCodeIsDeterministicAndSaltedByStudio() {
        let first = WailoCrypto.stretch(code: "ABCDEFGHJK", studioId: Self.studioId)
        XCTAssertEqual(first, WailoCrypto.stretch(code: "ABCDEFGHJK", studioId: Self.studioId))
        XCTAssertNotEqual(first, WailoCrypto.stretch(code: "ABCDEFGHJK", studioId: String(repeating: "a", count: 32)))
    }
}
