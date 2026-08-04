import CommonCrypto
import CryptoKit
import Foundation

/// The primitives behind the WiFi handshake (ADR-0039), and the one place the wire's byte layouts are
/// decided. `sdk-android` and `engine` reimplement all of this against `java.security`, so every
/// constant, label and concatenation order here is a contract: change one and paired devices stop
/// authenticating with no useful error. The cross-platform vectors in the test suites exist to catch
/// exactly that drift.
///
/// The two sides prove themselves differently because their exposure differs. Studio signs with an
/// ECDSA P-256 key, so nothing a device stores — nor anything that leaks out of Studio's own key
/// store — can forge that identity. A device answers with an HMAC over a paired secret, which is
/// enough when it only has to convince the one Studio it paired with.
///
/// P-256 rather than Ed25519 purely for reach: `java.security` only exposes Ed25519 at Android API 33
/// and the SDK's floor is 24, whereas `SHA256withECDSA` has been in the platform since API 11 and
/// `P256.Signing` since iOS 13. ECDSA's real hazard is a reused or biased signing nonce leaking the
/// private key, and it cannot reach the device: only Studio signs, and verification uses no nonce.
///
/// Keys cross the wire as X9.63 uncompressed points and signatures as raw `r || s`, because those are
/// the representations CryptoKit exposes at iOS 13 — DER needs 14. Studio converts to and from the
/// JVM's native SPKI/DER on its own side.
enum WailoCrypto {

    /// Both handshake nonces, and every derived key.
    static let nonceLength = 32
    static let keyLength = 32

    // MARK: - identity

    /// Studio's identity: the first 16 bytes of its public key's SHA-256, in lowercase hex.
    ///
    /// Deriving it from the key rather than picking a random string is what makes the Bonjour TXT
    /// record self-authenticating. An impostor can advertise someone else's `sid`, but it would need a
    /// key that hashes to it in order to sign the challenge — so the device dials it, fails to verify,
    /// and hangs up, instead of racing the real Studio for the device's attention.
    ///
    /// The key is hashed in its X9.63 form (the 65-byte uncompressed point), not X.509 SPKI DER, so
    /// both platforms hash the same bytes. Studio converts on its side; CryptoKit only offers DER
    /// above iOS 14 and this package targets 13.
    static func studioId(publicKey: Data) -> String {
        Data(SHA256.hash(data: publicKey).prefix(16)).hexadecimal
    }

    // MARK: - key agreement

    /// A fresh P-256 agreement pair, one per connection and discarded with it.
    static func generateEphemeral() -> P256.KeyAgreement.PrivateKey {
        P256.KeyAgreement.PrivateKey()
    }

    /// The agreed secret: the X coordinate of the shared point, which is what CryptoKit and the JVM's
    /// `ECDH` both return. Nil for a peer key that is malformed or off the curve — CryptoKit checks
    /// that on the way in, so an invalid-curve probe fails here rather than agreeing to something.
    static func agree(_ privateKey: P256.KeyAgreement.PrivateKey, peer: Data) -> Data? {
        guard let peerKey = try? P256.KeyAgreement.PublicKey(x963Representation: peer),
              let secret = try? privateKey.sharedSecretFromKeyAgreement(with: peerKey) else { return nil }
        return secret.withUnsafeBytes { Data($0) }
    }

    // MARK: - key schedule

    /// The long-term per-device secret for a QR or typed-code pairing. Never transmitted: the invite
    /// carries `pairingSecret` out of band and both ends derive the same `K` from it, which is why
    /// pairing needs no extra round trip — the first successful handshake *is* the pairing.
    static func deviceKey(pairingSecret: Data, studioId: String, deviceId: String) -> SymmetricKey {
        hkdf(
            keyMaterial: pairingSecret,
            salt: Data(studioId.utf8),
            info: label("wailo/device-key/v1", Data(deviceId.utf8))
        )
    }

    /// The same long-term secret, for a lenient first contact that had no invite (ADR-0040). Both ends
    /// derive it from the agreed secret of that one connection and keep it, so every later connection
    /// takes the ordinary paired path. Nothing is transmitted here either.
    static func tofuDeviceKey(shared: Data, studioId: String, deviceId: String) -> SymmetricKey {
        hkdf(
            keyMaterial: shared,
            salt: Data(studioId.utf8),
            info: label("wailo/tofu-device-key/v1", Data(deviceId.utf8))
        )
    }

    /// What every per-connection key descends from: the agreed secret, then the long-term key when
    /// there is one.
    ///
    /// Both, rather than either. The agreed secret alone would let anyone who is merely *present* at a
    /// handshake derive the session — there would be nothing they had to know. `K` alone would mean a
    /// key that leaks in a year opens every session recorded before it. Concatenating them needs both.
    private static func material(shared: Data, deviceKey: SymmetricKey?) -> Data {
        shared + (deviceKey?.bytes ?? Data())
    }

    /// Separated from the sealing key so no key is ever used for two purposes.
    static func authKey(shared: Data, deviceKey: SymmetricKey?) -> SymmetricKey {
        hkdf(
            keyMaterial: material(shared: shared, deviceKey: deviceKey),
            salt: Data(),
            info: Data("wailo/auth/v2".utf8)
        )
    }

    /// Fresh for every connection, because the ephemeral keys and both nonces are. That is what lets
    /// `SealedFrame.seq` restart at zero each time instead of being persisted across the SDK's
    /// 2-second reconnect loop — a counter that survives reconnects is how GCM nonce reuse happens in
    /// practice.
    static func sessionKey(shared: Data, deviceKey: SymmetricKey?, nonceD: Data, nonceS: Data) -> SymmetricKey {
        hkdf(
            keyMaterial: material(shared: shared, deviceKey: deviceKey),
            salt: nonceD + nonceS,
            info: Data("wailo/session/v2".utf8)
        )
    }

    // MARK: - proofs

    /// What both sides sign or MAC over. The role label is what stops a reflection attack — without it
    /// an impostor could bounce the device's own challenge back as its answer — and including `sid`
    /// binds the proof to one Studio identity, so it cannot be replayed at a different one.
    ///
    /// The ephemeral keys are in here because otherwise they are the one part of the handshake nobody
    /// vouches for: anyone in the path could substitute their own into a replayed challenge and agree
    /// a separate key with each side, which is a man in the middle wearing the real Studio's signature.
    static func transcript(
        role: String,
        studioId: String,
        nonceD: Data,
        nonceS: Data,
        ephemeralD: Data,
        ephemeralS: Data
    ) -> Data {
        label(role, Data(studioId.utf8)) + nonceD + nonceS + ephemeralD + ephemeralS
    }

    static func deviceProof(
        authKey: SymmetricKey,
        studioId: String,
        nonceD: Data,
        nonceS: Data,
        ephemeralD: Data,
        ephemeralS: Data
    ) -> Data {
        let transcript = transcript(
            role: "wailo/device", studioId: studioId,
            nonceD: nonceD, nonceS: nonceS, ephemeralD: ephemeralD, ephemeralS: ephemeralS
        )
        return Data(HMAC<SHA256>.authenticationCode(for: transcript, using: authKey))
    }

    /// Studio's second proof, and the only one that means anything during a first pairing over a typed
    /// code: at that moment the device has no trusted copy of the public key, so a signature would just
    /// be an impostor signing with its own. This is keyed by the pairing secret, which only the Studio
    /// that displayed the code knows.
    static func studioMac(
        authKey: SymmetricKey,
        studioId: String,
        nonceD: Data,
        nonceS: Data,
        ephemeralD: Data,
        ephemeralS: Data
    ) -> Data {
        let transcript = transcript(
            role: "wailo/studio-mac", studioId: studioId,
            nonceD: nonceD, nonceS: nonceS, ephemeralD: ephemeralD, ephemeralS: ephemeralS
        )
        return Data(HMAC<SHA256>.authenticationCode(for: transcript, using: authKey))
    }

    /// Stretches a typed pairing code into the same 32 bytes a QR would have carried directly.
    ///
    /// A code short enough to type holds ~50 bits, and everything downstream descends from it, so
    /// anyone who records one pairing handshake could otherwise brute-force it offline and decrypt
    /// every session that device ever has. The iteration count is the mitigation: it makes each guess
    /// cost real work. A PAKE would let the code shrink further and is the proper fix if the typing
    /// ever grates.
    static func stretch(code: String, studioId: String) -> Data {
        pbkdf2(password: Data(code.utf8), salt: Data(studioId.utf8), rounds: 200_000)
    }

    /// Constant-time, because these compare secrets and `==` on `Data` returns early.
    static func constantTimeEquals(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var difference: UInt8 = 0
        for (left, right) in zip(lhs, rhs) { difference |= left ^ right }
        return difference == 0
    }

    /// Studio's half. Returns false for a malformed key or signature as readily as for a wrong one:
    /// every failure here means "this is not the Studio I paired with", and the device treats them
    /// identically by hanging up.
    static func isValidStudioSignature(
        _ signature: Data,
        publicKey: Data,
        studioId: String,
        nonceD: Data,
        nonceS: Data,
        ephemeralD: Data,
        ephemeralS: Data
    ) -> Bool {
        guard let key = try? P256.Signing.PublicKey(x963Representation: publicKey),
              let signature = try? P256.Signing.ECDSASignature(rawRepresentation: signature) else {
            return false
        }
        let transcript = transcript(
            role: "wailo/studio", studioId: studioId,
            nonceD: nonceD, nonceS: nonceS, ephemeralD: ephemeralD, ephemeralS: ephemeralS
        )
        return key.isValidSignature(signature, for: transcript)
    }

    static func randomNonce() -> Data {
        var bytes = Data(count: nonceLength)
        bytes.withUnsafeMutableBytes { buffer in
            guard let base = buffer.baseAddress else { return }
            _ = SecRandomCopyBytes(kSecRandomDefault, nonceLength, base)
        }
        return bytes
    }

    // MARK: - internals

    /// A `0x00`-separated label, so a payload can never be read two ways. Every field either side of a
    /// separator is fixed-length or terminated by one, which is what keeps the transcript unambiguous
    /// across two independent implementations.
    private static func label(_ text: String, _ suffix: Data) -> Data {
        Data(text.utf8) + Data([0x00]) + suffix + Data([0x00])
    }

    /// RFC 5869 HKDF-SHA256, hand-composed because CryptoKit's own `HKDF` is iOS 14 and this package
    /// targets iOS 13. Only the composition is ours — extract and expand are both HMAC, which is the
    /// platform's. Output is one SHA-256 block, so expand needs a single iteration.
    private static func hkdf(keyMaterial: Data, salt: Data, info: Data) -> SymmetricKey {
        let prk = HMAC<SHA256>.authenticationCode(for: keyMaterial, using: SymmetricKey(data: salt))
        let block = HMAC<SHA256>.authenticationCode(
            for: info + Data([0x01]),
            using: SymmetricKey(data: Data(prk))
        )
        return SymmetricKey(data: Data(block))
    }

    /// CommonCrypto rather than CryptoKit, which has no PBKDF2 at any deployment target.
    private static func pbkdf2(password: Data, salt: Data, rounds: UInt32) -> Data {
        var derived = Data(count: keyLength)
        let status: Int32 = derived.withUnsafeMutableBytes { output in
            password.withUnsafeBytes { password in
                salt.withUnsafeBytes { salt in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        password.baseAddress?.assumingMemoryBound(to: CChar.self), password.count,
                        salt.baseAddress?.assumingMemoryBound(to: UInt8.self), salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), rounds,
                        output.baseAddress?.assumingMemoryBound(to: UInt8.self), keyLength
                    )
                }
            }
        }
        // Only a nonsensical parameter can fail here, and every one of them is a constant above.
        precondition(status == kCCSuccess, "PBKDF2 derivation failed")
        return derived
    }
}

extension SymmetricKey {
    var bytes: Data { withUnsafeBytes { Data($0) } }
}

extension Data {
    var hexadecimal: String { map { String(format: "%02x", $0) }.joined() }

    init?(hexadecimal text: String) {
        guard text.count.isMultiple(of: 2) else { return nil }
        var bytes = Data(capacity: text.count / 2)
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            guard let byte = UInt8(text[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self = bytes
    }
}
