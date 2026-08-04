import CryptoKit
import Foundation
import WailoProtocol

/// Drives the device's half of the WiFi handshake (ADR-0039, revised by ADR-0040): one exchange, no
/// I/O of its own, so the whole trust decision is testable without a socket.
///
/// Nothing leaves the device until this finishes — `Hello` carries the device name and bundle id, and
/// handing those to anything that merely advertises `_wailo._tcp` is the leak the handshake exists to
/// prevent.
final class WailoHandshake {

    /// What the device already knows about the peer it is dialling, which is what it can demand.
    enum Trust {
        /// Nothing. The user named this address, so whatever identity answers is pinned and kept
        /// (ADR-0040). An attacker has to already be in the path at this exact moment; from the next
        /// connection on, the pin makes that too late.
        case firstContact
        /// A QR or typed code supplied the secret out of band. `publicKey` is present for a QR, which
        /// carries it; a typed code has to pin whatever the challenge offers, and leans on the mac to
        /// prove that key belongs to the Studio showing the code.
        case invited(studioId: String, deviceKey: SymmetricKey, publicKey: Data?, byCode: Bool)
        /// Established previously: both the key and the identity are pinned, and neither may move.
        case paired(studioId: String, deviceKey: SymmetricKey, publicKey: Data, sessionCounter: UInt64)
    }

    struct Established {
        let sessionKey: SymmetricKey
        /// Persist this: on a first contact it is the whole record, and afterwards it carries the
        /// advanced session counter.
        let pairing: WailoPairing
    }

    enum Step {
        case send(Envelope)
        case established(Established)
        /// Studio does not know us, or will not take an unpaired device. Deliberately distinct from
        /// `failed`: the device stops retrying but keeps its key, because `AuthResult` is
        /// unauthenticated and deleting on it would let anyone force a re-pair on demand.
        case refused(String)
        /// Something else is answering at an address this device has been to before. Never resolved
        /// automatically — that decision is the entire value of having pinned the key (ADR-0040).
        case identityChanged(expected: String, actual: String)
        /// Something claimed this identity and could not prove it. Hang up without a word.
        case failed
        case ignore
    }

    private let trust: Trust
    private let deviceId: String
    private let host: String

    private let nonceD = WailoCrypto.randomNonce()
    private let ephemeral = WailoCrypto.generateEphemeral()
    private let ephemeralD: Data

    private var nonceS: Data?
    private var ephemeralS: Data?
    private var shared: Data?
    private var resolvedStudioId: String?
    private var acceptedPublicKey: Data?
    /// Studio proved its identity but declined to prove it holds our key. Only then is the
    /// `AuthResult` that follows worth acting on.
    private var declining = false

    init(trust: Trust, deviceId: String, host: String) {
        self.trust = trust
        self.deviceId = deviceId
        self.host = host
        self.ephemeralD = ephemeral.publicKey.x963Representation
    }

    func begin() -> Envelope {
        Envelope {
            $0.message = .auth_request(AuthRequest(
                // Empty on a first contact: the device has not been told who is listening here, and
                // guessing would only produce a mismatch Studio has to reject.
                studio_id: expectedStudioId ?? "",
                device_id: deviceId,
                nonce: nonceD,
                paired_by_code: pairedByCode,
                ephemeral_key: ephemeralD
            ))
        }
    }

    func handle(_ envelope: Envelope) -> Step {
        switch envelope.message {
        case let .auth_challenge(challenge)?:
            return handle(challenge)
        case let .auth_result(result)?:
            return handle(result)
        default:
            // Anything else before auth completes is a peer that skipped the handshake.
            return .failed
        }
    }

    private func handle(_ challenge: AuthChallenge) -> Step {
        guard nonceS == nil, challenge.nonce.count == WailoCrypto.nonceLength else { return .failed }
        let nonceS = challenge.nonce
        let ephemeralS = challenge.ephemeral_key
        guard let shared = WailoCrypto.agree(ephemeral, peer: ephemeralS) else { return .failed }

        let publicKey: Data
        switch resolveIdentity(offered: challenge.public_key) {
        case let .use(key): publicKey = key
        case let .reject(step): return step
        }
        let studioId = WailoCrypto.studioId(publicKey: publicKey)

        guard WailoCrypto.isValidStudioSignature(
            challenge.signature, publicKey: publicKey, studioId: studioId,
            nonceD: nonceD, nonceS: nonceS, ephemeralD: ephemeralD, ephemeralS: ephemeralS
        ) else { return .failed }

        self.nonceS = nonceS
        self.ephemeralS = ephemeralS
        self.shared = shared
        self.resolvedStudioId = studioId

        // No mac means Studio can sign as itself but will not answer under our key: it has either
        // forgotten this device or refuses unpaired ones. Requiring the signature first is what makes
        // the refusal that follows trustworthy — otherwise anyone on the network could send one and
        // park the device in a re-pair prompt.
        if challenge.mac.isEmpty {
            declining = true
            return .ignore
        }

        let authKey = WailoCrypto.authKey(shared: shared, deviceKey: deviceKey)
        let expectedMac = WailoCrypto.studioMac(
            authKey: authKey, studioId: studioId,
            nonceD: nonceD, nonceS: nonceS, ephemeralD: ephemeralD, ephemeralS: ephemeralS
        )
        guard WailoCrypto.constantTimeEquals(challenge.mac, expectedMac) else { return .failed }

        acceptedPublicKey = publicKey
        let proof = WailoCrypto.deviceProof(
            authKey: authKey, studioId: studioId,
            nonceD: nonceD, nonceS: nonceS, ephemeralD: ephemeralD, ephemeralS: ephemeralS
        )
        return .send(Envelope {
            $0.message = .auth_response(AuthResponse(proof: proof, session_counter: sessionCounter + 1))
        })
    }

    private enum Identity {
        case use(Data)
        case reject(Step)
    }

    /// Which key to verify against, and whether the answer is acceptable at all.
    ///
    /// The pinned case is the one that matters: an address this device has used before, now answered
    /// by a different identity, is either a machine that changed hands or someone standing in the
    /// path. Both look identical from here, so neither is resolved automatically.
    private func resolveIdentity(offered: Data) -> Identity {
        if let pinned = pinnedPublicKey {
            guard offered.isEmpty || offered == pinned else {
                return .reject(.identityChanged(
                    expected: WailoCrypto.studioId(publicKey: pinned),
                    actual: WailoCrypto.studioId(publicKey: offered)
                ))
            }
            return .use(pinned)
        }
        // Nothing pinned: take what is offered, but a named identity still has to match its own
        // fingerprint, which keeps "sid is the hash of the key" true even here.
        guard !offered.isEmpty else { return .reject(.failed) }
        if let expected = expectedStudioId, WailoCrypto.studioId(publicKey: offered) != expected {
            return .reject(.failed)
        }
        return .use(offered)
    }

    private func handle(_ result: AuthResult) -> Step {
        if declining {
            return .refused(result.reason.isEmpty ? "This Studio will not accept this device." : result.reason)
        }
        guard let nonceS, let shared, let studioId = resolvedStudioId, let acceptedPublicKey else {
            return .failed
        }
        // A rejection at this point came from a peer that already proved it holds our key, so it is
        // worth surfacing rather than silently retrying.
        guard result.ok else {
            return .refused(result.reason.isEmpty ? "This Studio does not recognise this device." : result.reason)
        }
        // On a first contact the long-term key is derived from this connection's agreed secret, by
        // both ends independently, so it never crosses the wire.
        let longTerm = deviceKey
            ?? WailoCrypto.tofuDeviceKey(shared: shared, studioId: studioId, deviceId: deviceId)
        return .established(Established(
            sessionKey: WailoCrypto.sessionKey(
                shared: shared, deviceKey: deviceKey, nonceD: nonceD, nonceS: nonceS
            ),
            pairing: WailoPairing(
                studioId: studioId,
                deviceKey: longTerm.bytes,
                publicKey: acceptedPublicKey,
                sessionCounter: sessionCounter + 1,
                refused: false,
                lastHost: host,
                trustedOnFirstUse: deviceKey == nil
            )
        ))
    }

    // MARK: - what the trust mode supplies

    private var expectedStudioId: String? {
        switch trust {
        case .firstContact: return nil
        case let .invited(studioId, _, _, _): return studioId
        case let .paired(studioId, _, _, _): return studioId
        }
    }

    private var deviceKey: SymmetricKey? {
        switch trust {
        case .firstContact: return nil
        case let .invited(_, key, _, _): return key
        case let .paired(_, key, _, _): return key
        }
    }

    private var pinnedPublicKey: Data? {
        switch trust {
        case .firstContact: return nil
        case let .invited(_, _, key, _): return key
        case let .paired(_, _, key, _): return key
        }
    }

    private var sessionCounter: UInt64 {
        switch trust {
        case .firstContact, .invited: return 0
        case let .paired(_, _, _, counter): return counter
        }
    }

    private var pairedByCode: Bool {
        switch trust {
        case let .invited(_, _, _, byCode): return byCode
        case .firstContact, .paired: return false
        }
    }
}
