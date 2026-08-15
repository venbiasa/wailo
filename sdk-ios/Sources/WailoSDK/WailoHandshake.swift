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
        case firstContact(expectedStudioId: String?)
        /// The user named an address that is not yet associated with one saved Studio. StudioHello's
        /// identity selects a matching pairing when there is one; only a genuinely new identity falls
        /// back to first contact.
        case knownOrFirstContact([WailoPairing], expectedStudioId: String?)
        /// A QR or typed code supplied the secret out of band. `publicKey` is present for a QR, which
        /// carries it; a typed code pins the signed StudioHello identity after the code-derived proof
        /// authenticates it.
        case invited(studioId: String, pairingSecret: Data, publicKey: Data?, byCode: Bool)
        /// Established previously: both the key and the identity are pinned, and neither may move.
        case paired(WailoPairing)
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
        /// Studio authenticated a refusal. Deliberately distinct from `failed` so the UI can offer
        /// the explicit Retry/Forget recovery instead of silently looping.
        case refused(String)
        /// Something else is answering at an address this device has been to before. Never resolved
        /// automatically — that decision is the entire value of having pinned the key (ADR-0040).
        case identityChanged(expected: String, actual: String)
        /// Something claimed this identity and could not prove it. Hang up without a word.
        case failed
        case ignore
    }

    private let trust: Trust
    private let host: String

    private let nonceD = WailoCrypto.randomNonce()
    private let ephemeral = WailoCrypto.generateEphemeral()
    private let ephemeralD: Data
    private let pendingDeviceAlias = WailoCrypto.randomNonce().prefix(16).hexadecimal

    private var nonceS: Data?
    private var ephemeralS: Data?
    private var shared: Data?
    private var resolvedStudioId: String?
    private var acceptedPublicKey: Data?
    private var resolvedPairing: WailoPairing?
    private var deviceAlias: String?
    private var selectedDeviceKey: SymmetricKey?
    private var mode: AuthModeV3?
    private var sessionCounter: UInt64?
    private var pairingRequired = false

    init(trust: Trust, host: String) {
        self.trust = trust
        self.host = host
        self.ephemeralD = ephemeral.publicKey.x963Representation
    }

    func begin() -> Envelope {
        Envelope {
            $0.message = .auth_client_hello_v3(AuthClientHelloV3(
                version: 3,
                nonce: nonceD,
                ephemeral_key: ephemeralD
            ))
        }
    }

    func handle(_ envelope: Envelope) -> Step {
        switch envelope.message {
        case let .auth_studio_hello_v3(hello)?:
            return handle(hello)
        case let .auth_result_v3(result)?:
            return handle(result)
        default:
            // Anything else before auth completes is a peer that skipped the handshake.
            return .failed
        }
    }

    private func handle(_ hello: AuthStudioHelloV3) -> Step {
        guard nonceS == nil, hello.nonce.count == WailoCrypto.nonceLength else { return .failed }
        let nonceS = hello.nonce
        let ephemeralS = hello.ephemeral_key
        guard let shared = WailoCrypto.agree(ephemeral, peer: ephemeralS) else { return .failed }
        guard !hello.public_key.isEmpty else { return .failed }
        let studioId = WailoCrypto.studioId(publicKey: hello.public_key)
        guard WailoCrypto.isValidStudioHelloV3(
            hello.signature,
            publicKey: hello.public_key,
            studioId: studioId,
            nonceD: nonceD,
            nonceS: nonceS,
            ephemeralD: ephemeralD,
            ephemeralS: ephemeralS,
            pairingRequired: hello.pairing_required
        ) else { return .failed }

        let publicKey: Data
        switch resolveIdentity(offered: hello.public_key, studioId: studioId) {
        case let .use(key): publicKey = key
        case let .reject(step): return step
        }

        self.nonceS = nonceS
        self.ephemeralS = ephemeralS
        self.shared = shared
        self.resolvedStudioId = studioId
        acceptedPublicKey = publicKey
        pairingRequired = hello.pairing_required

        let selected = credential(shared: shared, studioId: studioId)
        deviceAlias = selected.alias
        selectedDeviceKey = selected.key
        mode = selected.mode
        sessionCounter = selected.counter
        let authKey = WailoCrypto.authKeyV3(shared: shared, deviceKey: selected.key)
        return .send(Envelope {
            $0.message = .auth_device_proof_v3(AuthDeviceProofV3(
                device_alias: selected.alias,
                mode: selected.mode,
                session_counter: selected.counter,
                proof: WailoCrypto.deviceProofV3(
                    authKey: authKey,
                    studioId: studioId,
                    nonceD: nonceD,
                    nonceS: nonceS,
                    ephemeralD: ephemeralD,
                    ephemeralS: ephemeralS,
                    pairingRequired: hello.pairing_required,
                    deviceAlias: selected.alias,
                    mode: selected.mode.rawValue,
                    sessionCounter: selected.counter
                )
            ))
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
    private func resolveIdentity(offered: Data, studioId: String) -> Identity {
        switch trust {
        case let .firstContact(expected):
            if let expected, expected != studioId {
                return .reject(.identityChanged(expected: expected, actual: studioId))
            }
            return .use(offered)
        case let .knownOrFirstContact(pairings, expected):
            if let expected, expected != studioId {
                return .reject(.identityChanged(expected: expected, actual: studioId))
            }
            if let pairing = pairings.first(where: { $0.studioId == studioId }) {
                guard pairing.publicKey == offered else { return .reject(.failed) }
                resolvedPairing = pairing
            }
            return .use(offered)
        case let .invited(expected, _, pinned, _):
            guard studioId == expected else { return .reject(.failed) }
            guard pinned == nil || pinned == offered else { return .reject(.failed) }
            return .use(offered)
        case let .paired(pairing):
            guard studioId == pairing.studioId, offered == pairing.publicKey else {
                return .reject(.identityChanged(
                    expected: pairing.studioId,
                    actual: studioId
                ))
            }
            resolvedPairing = pairing
            return .use(offered)
        }
    }

    private func handle(_ result: AuthResultV3) -> Step {
        guard let nonceS,
              let ephemeralS,
              let shared,
              let studioId = resolvedStudioId,
              let acceptedPublicKey,
              let deviceAlias,
              let selectedDeviceKey,
              let mode,
              let sessionCounter else {
            return .failed
        }
        guard WailoCrypto.isValidResultSignatureV3(
            result.signature,
            publicKey: acceptedPublicKey,
            studioId: studioId,
            nonceD: nonceD,
            nonceS: nonceS,
            ephemeralD: ephemeralD,
            ephemeralS: ephemeralS,
            pairingRequired: pairingRequired,
            deviceAlias: deviceAlias,
            mode: mode.rawValue,
            sessionCounter: sessionCounter,
            resultCode: result.code.rawValue
        ) else { return .failed }

        guard result.code == .AUTH_RESULT_CODE_V3_OK else {
            return .refused(refusalMessage(for: result.code))
        }
        let authKey = WailoCrypto.authKeyV3(shared: shared, deviceKey: selectedDeviceKey)
        let expectedProof = WailoCrypto.studioProofV3(
            authKey: authKey,
            studioId: studioId,
            nonceD: nonceD,
            nonceS: nonceS,
            ephemeralD: ephemeralD,
            ephemeralS: ephemeralS,
            pairingRequired: pairingRequired,
            deviceAlias: deviceAlias,
            mode: mode.rawValue,
            sessionCounter: sessionCounter,
            resultCode: result.code.rawValue
        )
        guard WailoCrypto.constantTimeEquals(result.proof, expectedProof) else { return .failed }

        return .established(Established(
            sessionKey: WailoCrypto.sessionKeyV3(
                shared: shared,
                deviceKey: selectedDeviceKey,
                nonceD: nonceD,
                nonceS: nonceS
            ),
            pairing: WailoPairing(
                studioId: studioId,
                deviceAlias: deviceAlias,
                deviceKey: selectedDeviceKey.bytes,
                publicKey: acceptedPublicKey,
                sessionCounter: sessionCounter,
                refused: false,
                lastHost: host,
                trustedOnFirstUse: resolvedPairing?.trustedOnFirstUse ?? (mode == .AUTH_MODE_V3_TOFU)
            )
        ))
    }

    private struct Credential {
        let alias: String
        let key: SymmetricKey
        let mode: AuthModeV3
        let counter: UInt64
    }

    private func credential(shared: Data, studioId: String) -> Credential {
        if let pairing = resolvedPairing {
            return Credential(
                alias: pairing.deviceAlias,
                key: SymmetricKey(data: pairing.deviceKey),
                mode: .AUTH_MODE_V3_KNOWN,
                counter: pairing.sessionCounter + 1
            )
        }
        switch trust {
        case let .invited(_, pairingSecret, _, byCode):
            return Credential(
                alias: pendingDeviceAlias,
                key: WailoCrypto.deviceKeyV3(
                    pairingSecret: pairingSecret,
                    studioId: studioId,
                    deviceAlias: pendingDeviceAlias
                ),
                mode: byCode ? .AUTH_MODE_V3_INVITED_CODE : .AUTH_MODE_V3_INVITED_QR,
                counter: 1
            )
        case .firstContact, .knownOrFirstContact:
            return Credential(
                alias: pendingDeviceAlias,
                key: WailoCrypto.tofuDeviceKeyV3(
                    shared: shared,
                    studioId: studioId,
                    deviceAlias: pendingDeviceAlias
                ),
                mode: .AUTH_MODE_V3_TOFU,
                counter: 1
            )
        case .paired:
            preconditionFailure("a paired trust always resolves before credential selection")
        }
    }

    private func refusalMessage(for code: AuthResultCodeV3) -> String {
        switch code {
        case .AUTH_RESULT_CODE_V3_PAIRING_REQUIRED:
            return "This Studio only accepts paired devices. Pair from its Devices panel."
        case .AUTH_RESULT_CODE_V3_PAIRING_OFFER_INVALID:
            return "The pairing offer expired or was already used. Start pairing again in Studio."
        case .AUTH_RESULT_CODE_V3_UNKNOWN_DEVICE:
            return "This Studio no longer recognises this device. Forget it here, then connect or pair again."
        case .AUTH_RESULT_CODE_V3_OK, .AUTH_RESULT_CODE_V3_UNSPECIFIED:
            return "This Studio did not accept this device."
        }
    }
}
