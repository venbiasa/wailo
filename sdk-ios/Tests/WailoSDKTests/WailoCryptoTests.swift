import CryptoKit
import XCTest
@testable import WailoSDK

/// The identity-first handshake's byte layouts (ADR-0060), pinned as vectors.
///
/// `engine` and `sdk-android` reimplement all of this against `java.security` — the two builds cannot
/// share code — so the only thing keeping them interoperable is that both sides assert the *same*
/// constants. `WailoCryptoTest.kt` on the Kotlin side holds these identical strings; if either drifts,
/// paired devices stop authenticating with nothing but a silent handshake failure to go on.
final class WailoCryptoTests: XCTestCase {

    // Fixed inputs. Arbitrary, but they must never change.
    static let pairingSecret = Data((0..<32).map { UInt8($0) })
    static let studioId = "0123456789abcdef0123456789abcdef"
    static let deviceAlias = "00112233445566778899aabbccddeeff"
    static let nonceD = Data((0..<32).map { UInt8(0x40 + $0) })
    static let nonceS = Data((0..<32).map { UInt8(0x60 + $0) })
    /// Fixed agreement scalars, so the derived point and every key below it are reproducible on both
    /// platforms. Real ones are random per connection; these exist only to be asserted.
    static let scalarD = Data((1...32).map { UInt8($0) })
    static let scalarS = Data((33...64).map { UInt8($0) })

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

    func testV3KeyScheduleVectors() throws {
        let v3DeviceKey = WailoCrypto.deviceKeyV3(
            pairingSecret: Self.pairingSecret,
            studioId: Self.studioId,
            deviceAlias: Self.deviceAlias
        )
        XCTAssertEqual(
            v3DeviceKey.bytes.hexadecimal,
            "df6ad188cc1263df05a07b927dc4cf11c4ab8a6131fef1109f6b1cc7e7b66c17"
        )
        XCTAssertEqual(
            try WailoCrypto.tofuDeviceKeyV3(
                shared: shared,
                studioId: Self.studioId,
                deviceAlias: Self.deviceAlias
            ).bytes.hexadecimal,
            "792e0d6856885a4cd6205927f8f43858fcac1c8fa61d9f2bf90a215e61bf0bf3"
        )
        XCTAssertEqual(
            try WailoCrypto.authKeyV3(shared: shared, deviceKey: v3DeviceKey).bytes.hexadecimal,
            "8300755de092d366f9cb3562147176965f77df7dff6547b8c88a09979ed12e3b"
        )
        XCTAssertEqual(
            try WailoCrypto.sessionKeyV3(
                shared: shared,
                deviceKey: v3DeviceKey,
                nonceD: Self.nonceD,
                nonceS: Self.nonceS
            ).bytes.hexadecimal,
            "5d6219803478b15a9a9642e4e0adaa94937a7bae7fc12047a839e32790045b37"
        )
    }

    func testV3ProofVectors() throws {
        let authKey = try WailoCrypto.authKeyV3(
            shared: shared,
            deviceKey: WailoCrypto.deviceKeyV3(
                pairingSecret: Self.pairingSecret,
                studioId: Self.studioId,
                deviceAlias: Self.deviceAlias
            )
        )
        XCTAssertEqual(
            try WailoCrypto.deviceProofV3(
                authKey: authKey,
                studioId: Self.studioId,
                nonceD: Self.nonceD,
                nonceS: Self.nonceS,
                ephemeralD: ephemeralD,
                ephemeralS: ephemeralS,
                pairingRequired: true,
                deviceAlias: Self.deviceAlias,
                mode: 1,
                sessionCounter: 42
            ).hexadecimal,
            "26c6d7a65f27e0613796bcf81c311c8739443bed3be1f8b4a879d51aa0be4e43"
        )
        XCTAssertEqual(
            try WailoCrypto.studioProofV3(
                authKey: authKey,
                studioId: Self.studioId,
                nonceD: Self.nonceD,
                nonceS: Self.nonceS,
                ephemeralD: ephemeralD,
                ephemeralS: ephemeralS,
                pairingRequired: true,
                deviceAlias: Self.deviceAlias,
                mode: 1,
                sessionCounter: 42,
                resultCode: 1
            ).hexadecimal,
            "523866c51d880396974b66dc160d3b543f29a6718f3b5682342194744c8ac037"
        )
        XCTAssertEqual(
            try WailoCrypto.deviceProofV3(
                authKey: authKey,
                studioId: Self.studioId,
                nonceD: Self.nonceD,
                nonceS: Self.nonceS,
                ephemeralD: ephemeralD,
                ephemeralS: ephemeralS,
                pairingRequired: false,
                deviceAlias: Self.deviceAlias,
                mode: 1,
                sessionCounter: 42
            ).hexadecimal,
            "42ecb4b2b8178ce16267b7a58a1c76f7e700366c56c0fdab35cdef45156caf9b"
        )
        XCTAssertEqual(
            try WailoCrypto.studioProofV3(
                authKey: authKey,
                studioId: Self.studioId,
                nonceD: Self.nonceD,
                nonceS: Self.nonceS,
                ephemeralD: ephemeralD,
                ephemeralS: ephemeralS,
                pairingRequired: false,
                deviceAlias: Self.deviceAlias,
                mode: 1,
                sessionCounter: 42,
                resultCode: 1
            ).hexadecimal,
            "c533d92ab93a2df6fcd2cecd86b982def9dbc516ed5ca312bb1723cfc62ae821"
        )
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

    func testSkippedFrameIsRejected() throws {
        let key = SymmetricKey(data: Data(repeating: 0x11, count: 32))
        let device = WailoFrameCodec(sessionKey: key, sealing: .deviceToStudio, opening: .studioToDevice)
        let studio = WailoFrameCodec(sessionKey: key, sealing: .studioToDevice, opening: .deviceToStudio)

        _ = try device.seal(Data("skipped".utf8))
        let second = try device.seal(Data("second".utf8))
        XCTAssertThrowsError(try studio.open(seq: second.seq, ciphertext: second.ciphertext))
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
