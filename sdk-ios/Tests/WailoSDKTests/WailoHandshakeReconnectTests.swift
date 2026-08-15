import CryptoKit
import Foundation
import Wire
import WailoProtocol
import XCTest
@testable import WailoSDK

/// What has to survive a dropped link on Wi-Fi (ADR-0039, ADR-0040).
///
/// The reconnect is the whole test. A first contact works against any Studio because neither side has
/// anything to prove yet — so a device that keeps introducing itself as a stranger looks perfectly
/// healthy right up until the link drops, at which point Studio remembers it and starts demanding the
/// key, and every retry after that fails authentication in silence. That was the shape of the bug this
/// file exists for: connect once, background the app, and the device could never get back in until
/// Studio forgot it.
final class WailoHandshakeReconnectTests: XCTestCase {

    private var realStore: WailoPairingStore!

    override func setUp() {
        super.setUp()
        realStore = WailoPairingStore.shared
        WailoPairingStore.shared = WailoPairingStore(storage: InMemorySecretStorage())
        WailoCoordinator.forcesWifiHandshakeForTesting = true
        WailoHostStore.clear()
    }

    override func tearDown() {
        Wailo.stop()
        _ = Wailo.setHost(nil)
        WailoCoordinator.forcesWifiHandshakeForTesting = false
        WailoPairingStore.shared = realStore
        WailoHostStore.clear()
        super.tearDown()
    }

    func testReconnectAfterATrustedFirstContactProvesTheKeyItLeftBehind() throws {
        let studio = FakeStudio()
        let server = LoopbackWebSocketServer()
        let port = try server.start()
        defer { server.stop() }
        studio.serve(on: server)

        Wailo.start(
            appId: "com.test.reconnect",
            deviceName: "reconnect",
            host: "127.0.0.1",
            port: Int(port),
            alsoLogToConsole: false
        )

        waitUntilTrue { studio.admissions.count == 1 }
        XCTAssertEqual(studio.admissions.first, .firstContact)
        XCTAssertEqual(WailoPairingStore.shared.all.count, 1, "a first contact has to leave a key behind")

        server.dropConnections()

        waitUntilTrue(timeout: 20) { studio.admissions.count == 2 }
        XCTAssertEqual(
            studio.admissions.last,
            .proved,
            "Studio remembers the device now, so the reconnect must prove the stored key rather than ask to be trusted again"
        )
        XCTAssertEqual(studio.rejections, 0)
    }

    func testForgettingOnDeviceDisconnectsThenReconnectsWithAFreshAlias() throws {
        let studio = FakeStudio()
        let server = LoopbackWebSocketServer()
        let port = try server.start()
        defer { server.stop() }
        studio.serve(on: server)

        XCTAssertTrue(Wailo.setHost("127.0.0.1", port: Int(port)))
        Wailo.start(
            appId: "com.test.forget",
            deviceName: "forget",
            alsoLogToConsole: false
        )
        waitUntilTrue { studio.admissions.count == 1 && Wailo.isConnected }
        let pairing = try XCTUnwrap(WailoPairingStore.shared.all.first)

        Wailo.forgetPairing(studioId: pairing.studioId)

        waitUntilTrue { !Wailo.isConnected }
        XCTAssertFalse(studio.knows(pairing.deviceAlias), "online Forget must revoke the live alias in Studio")
        let oldAlias = pairing.deviceAlias
        let requestsBeforeRetry = studio.authRequests
        Thread.sleep(forTimeInterval: 2.25)
        XCTAssertEqual(
            studio.authRequests,
            requestsBeforeRetry,
            "Forget must not reconnect until explicit Connect"
        )
        XCTAssertTrue(Wailo.setHost("127.0.0.1", port: Int(port)))
        waitUntilTrue { studio.authRequests > requestsBeforeRetry }
        waitUntilTrue { studio.admissions.count == 2 && Wailo.isConnected }
        let replacement = try XCTUnwrap(WailoPairingStore.shared.all.first)
        XCTAssertNotEqual(oldAlias, replacement.deviceAlias)
        XCTAssertEqual(studio.admissions.last, .firstContact)
    }

    func testPairingStoreRejectsARecordWithoutAValidV3DeviceAlias() throws {
        let storage = InMemorySecretStorage()
        let store = WailoPairingStore(storage: storage)
        let publicKey = P256.Signing.PrivateKey().publicKey.x963Representation
        let studioId = WailoCrypto.studioId(publicKey: publicKey)
        let current = WailoPairing(
            studioId: studioId,
            deviceAlias: "00112233445566778899aabbccddeeff",
            deviceKey: Data(repeating: 0x2a, count: 32),
            publicKey: publicKey,
            sessionCounter: 7,
            refused: false,
            lastHost: "192.168.1.20",
            trustedOnFirstUse: false
        )
        let encoded = try JSONEncoder().encode(current)
        var legacy = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        legacy.removeValue(forKey: "deviceAlias")
        storage.set(try JSONSerialization.data(withJSONObject: legacy), for: studioId)

        XCTAssertNil(store.pairing(studioId: studioId))

        legacy["deviceAlias"] = "00112233445566778899AABBCCDDEEFF"
        storage.set(try JSONSerialization.data(withJSONObject: legacy), for: studioId)
        XCTAssertNil(store.pairing(studioId: studioId))
    }

    /// A new address says where Studio moved, not which saved identity it is. StudioHello supplies
    /// that identity; choosing first-contact before reading it makes a known device use the wrong key.
    func testChangingAStudiosAddressFindsItsPairingAmongSeveralSavedDesktops() throws {
        let studio = FakeStudio()
        let server = LoopbackWebSocketServer()
        let port = try server.start()
        defer { server.stop() }
        studio.serve(on: server)

        XCTAssertTrue(Wailo.setHost("localhost", port: Int(port)))
        Wailo.start(
            appId: "com.test.address-change",
            deviceName: "address-change",
            alsoLogToConsole: false
        )

        waitUntilTrue { studio.admissions.count == 1 }
        XCTAssertEqual(studio.admissions.first, .firstContact)
        waitUntilTrue { WailoPairingStore.shared.all.count == 1 }

        let unrelatedKey = P256.Signing.PrivateKey().publicKey.x963Representation
        WailoPairingStore.shared.save(WailoPairing(
            studioId: WailoCrypto.studioId(publicKey: unrelatedKey),
            deviceAlias: "ffeeddccbbaa99887766554433221100",
            deviceKey: Data(repeating: 0x7a, count: 32),
            publicKey: unrelatedKey,
            sessionCounter: 1,
            refused: false,
            lastHost: "10.0.0.9",
            trustedOnFirstUse: false
        ))

        XCTAssertTrue(Wailo.setHost("127.0.0.1", port: Int(port)))

        waitUntilTrue { studio.admissions.count >= 2 }
        XCTAssertEqual(
            studio.admissions.last,
            .proved,
            "the Studio identity in the challenge must select its saved key after DHCP moves its address"
        )
        waitUntilTrue {
            WailoPairingStore.shared.pairing(studioId: studio.studioId)?.lastHost == "127.0.0.1"
        }
        XCTAssertEqual(Wailo.activeAddress, "127.0.0.1:\(port)")
        XCTAssertTrue(
            WailoPairingStore.shared.pairing(studioId: studio.studioId)?.trustedOnFirstUse == true,
            "moving an address must not rewrite how the Studio was originally trusted"
        )
        XCTAssertEqual(studio.rejections, 0)
    }

    func testAnUnknownStudioStillGetsFirstContactWhenOtherPairingsExist() throws {
        let knownStudio = FakeStudio()
        let newStudio = FakeStudio()

        let known = WailoHandshake(trust: .firstContact(expectedStudioId: nil), host: "10.0.0.2")
        guard case let .established(established) = knownStudio.run(known) else {
            return XCTFail("the existing Studio must establish the saved pairing")
        }

        let new = WailoHandshake(
            trust: .knownOrFirstContact([established.pairing], expectedStudioId: nil),
            host: "10.0.0.77"
        )
        guard case let .established(result) = newStudio.run(new) else {
            return XCTFail("a user-chosen address with a new identity must remain eligible for first contact")
        }

        XCTAssertEqual(result.pairing.studioId, newStudio.studioId)
        XCTAssertTrue(result.pairing.trustedOnFirstUse)
    }

    func testExpectedIdentityRejectsAnotherStudioAtTheSameAddress() throws {
        let expected = FakeStudio()
        let replacement = FakeStudio()
        let handshake = WailoHandshake(
            trust: .firstContact(expectedStudioId: expected.studioId),
            host: "10.0.0.2"
        )

        guard case let .identityChanged(wanted, actual) = replacement.run(handshake) else {
            return XCTFail("an address occupant with another signed identity must require confirmation")
        }
        XCTAssertEqual(wanted, expected.studioId)
        XCTAssertEqual(actual, replacement.studioId)
    }

    func testLiveIdentityMismatchStopsWithoutHelloAndForgetClearsIt() throws {
        let expected = FakeStudio()
        let replacement = FakeStudio()
        let server = LoopbackWebSocketServer()
        let port = try server.start()
        defer { server.stop() }
        replacement.serve(on: server)
        WailoPairingStore.shared.save(WailoPairing(
            studioId: expected.studioId,
            deviceAlias: "00112233445566778899aabbccddeeff",
            deviceKey: Data(repeating: 1, count: 32),
            publicKey: expected.publicKey,
            sessionCounter: 1,
            refused: false,
            lastHost: "127.0.0.1",
            trustedOnFirstUse: true
        ))

        XCTAssertTrue(Wailo.setHost(
            "127.0.0.1",
            port: Int(port),
            expectedStudioId: expected.studioId
        ))
        Wailo.start(appId: "com.test.mismatch", deviceName: "mismatch", alsoLogToConsole: false)

        waitUntilTrue { Wailo.identityChange != nil }
        XCTAssertEqual(Wailo.connectionPhase, .identityMismatch)
        XCTAssertFalse(Wailo.isConnected)
        let requests = replacement.authRequests
        Thread.sleep(forTimeInterval: 2.25)
        XCTAssertEqual(replacement.authRequests, requests)

        Wailo.forgetPairing(studioId: expected.studioId)
        waitUntilTrue {
            Wailo.identityChange == nil &&
                WailoPairingStore.shared.pairing(studioId: expected.studioId) == nil
        }
        XCTAssertEqual(Wailo.connectionPhase, .stopped)
    }

    func testStrictModeRefusalIsAuthenticated() {
        let studio = FakeStudio()
        studio.requirePairing = true
        let handshake = WailoHandshake(
            trust: .firstContact(expectedStudioId: nil),
            host: "10.0.0.2"
        )

        guard case let .refused(reason) = studio.run(handshake) else {
            return XCTFail("strict mode must refuse an uninvited TOFU connection")
        }
        XCTAssertFalse(reason.isEmpty)
    }

    func testStrictModeAcceptsQrAndCodeInvitations() throws {
        let qrStudio = FakeStudio()
        qrStudio.requirePairing = true
        qrStudio.offer = WailoCrypto.randomNonce()
        let qrText = "wailo://pair?sid=\(qrStudio.studioId)&h=10.0.0.2&p=8899" +
            "&k=\(qrStudio.publicKey.base64URL)&s=\(try XCTUnwrap(qrStudio.offer).base64URL)"
        let qr = try XCTUnwrap(WailoPairingInvite(qr: qrText))
        let qrHandshake = WailoHandshake(
            trust: .invited(
                studioId: qr.studioId,
                pairingSecret: qr.pairingSecret,
                publicKey: qr.publicKey,
                byCode: false
            ),
            host: qr.host
        )
        guard case .established = qrStudio.run(qrHandshake) else {
            return XCTFail("a live QR invite must satisfy strict mode")
        }

        let code = "ABCDEFGHJK"
        let codeStudio = FakeStudio()
        codeStudio.requirePairing = true
        codeStudio.offerCode = code
        let invite = try XCTUnwrap(WailoPairingInvite(
            code: code,
            studioId: codeStudio.studioId,
            host: "10.0.0.3",
            port: 8899
        ))
        let codeHandshake = WailoHandshake(
            trust: .invited(
                studioId: invite.studioId,
                pairingSecret: invite.pairingSecret,
                publicKey: nil,
                byCode: true
            ),
            host: invite.host
        )
        guard case .established = codeStudio.run(codeHandshake) else {
            return XCTFail("a live typed-code invite must satisfy strict mode")
        }
    }

    func testExpiredInvitationProducesAnAuthenticatedRefusal() throws {
        let studio = FakeStudio()
        studio.requirePairing = true
        studio.offer = WailoCrypto.randomNonce()
        let qrText = "wailo://pair?sid=\(studio.studioId)&h=10.0.0.2&p=8899" +
            "&k=\(studio.publicKey.base64URL)&s=\(try XCTUnwrap(studio.offer).base64URL)"
        let invite = try XCTUnwrap(WailoPairingInvite(qr: qrText))
        studio.offer = nil
        let handshake = WailoHandshake(
            trust: .invited(
                studioId: invite.studioId,
                pairingSecret: invite.pairingSecret,
                publicKey: invite.publicKey,
                byCode: false
            ),
            host: invite.host
        )

        guard case let .refused(reason) = studio.run(handshake) else {
            return XCTFail("an expired invite must produce Studio's signed refusal")
        }
        XCTAssertTrue(reason.localizedCaseInsensitiveContains("expired"))
    }

    func testForgedHelloResultAndProofAreRejected() {
        for configure in [
            { (studio: FakeStudio) in studio.forgeHelloSignature = true },
            { (studio: FakeStudio) in studio.forgeResultSignature = true },
            { (studio: FakeStudio) in studio.forgeResultProof = true },
        ] {
            let studio = FakeStudio()
            configure(studio)
            let handshake = WailoHandshake(
                trust: .firstContact(expectedStudioId: nil),
                host: "10.0.0.2"
            )
            guard case .failed = studio.run(handshake) else {
                return XCTFail("forged v3 authentication material must fail closed")
            }
        }
    }

    func testV2ChallengeIsRejectedAsADowngrade() {
        let handshake = WailoHandshake(
            trust: .firstContact(expectedStudioId: nil),
            host: "10.0.0.2"
        )
        _ = handshake.begin()

        // Former Envelope field 14 carrying an empty AuthChallenge. The tag is reserved in v3, so it
        // decodes as an unknown field and cannot dispatch into a legacy state machine.
        let legacy = try! ProtoDecoder().decode(Envelope.self, from: Data([0x72, 0x00]))
        guard case .failed = handshake.handle(legacy) else {
            return XCTFail("a v3 device must not negotiate the v2 challenge")
        }
    }

    func testDifferentStudiosReceiveDifferentAliases() throws {
        let first = FakeStudio().run(WailoHandshake(
            trust: .firstContact(expectedStudioId: nil),
            host: "10.0.0.2"
        ))
        let second = FakeStudio().run(WailoHandshake(
            trust: .firstContact(expectedStudioId: nil),
            host: "10.0.0.3"
        ))
        guard case let .established(one) = first,
              case let .established(two) = second else {
            return XCTFail("both Studios must accept explicit first contact")
        }

        XCTAssertNotEqual(one.pairing.deviceAlias, two.pairing.deviceAlias)
    }

    func testClientHelloDoesNotRevealThePairedAlias() throws {
        let studio = FakeStudio()
        guard case let .established(established) = studio.run(WailoHandshake(
            trust: .firstContact(expectedStudioId: nil),
            host: "10.0.0.2"
        )) else {
            return XCTFail("first contact must establish a pairing")
        }

        let hello = WailoHandshake(
            trust: .paired(established.pairing),
            host: "10.0.0.2"
        ).begin()
        guard case .auth_client_hello_v3? = hello.message else {
            return XCTFail("the first frame must be an anonymous v3 client hello")
        }
        let encoded = try ProtoEncoder().encode(hello)
        XCTAssertNil(encoded.range(of: Data(established.pairing.deviceAlias.utf8)))
    }

    func testOfflineForgetUsesAFreshAliasAndKeepsOtherStudios() throws {
        let studio = FakeStudio()
        let otherStudio = FakeStudio()
        guard case let .established(first) = studio.run(WailoHandshake(
            trust: .firstContact(expectedStudioId: nil),
            host: "10.0.0.2"
        )), case let .established(other) = otherStudio.run(WailoHandshake(
            trust: .firstContact(expectedStudioId: nil),
            host: "10.0.0.3"
        )) else {
            return XCTFail("both first contacts must establish pairings")
        }
        WailoPairingStore.shared.save(first.pairing)
        WailoPairingStore.shared.save(other.pairing)

        WailoPairingStore.shared.forget(studioId: studio.studioId)

        XCTAssertNil(WailoPairingStore.shared.pairing(studioId: studio.studioId))
        XCTAssertEqual(
            WailoPairingStore.shared.pairing(studioId: otherStudio.studioId)?.deviceAlias,
            other.pairing.deviceAlias
        )
        guard case let .established(replacement) = studio.run(WailoHandshake(
            trust: .firstContact(expectedStudioId: studio.studioId),
            host: "10.0.0.2"
        )) else {
            return XCTFail("explicit reconnect after offline Forget must establish a fresh relationship")
        }
        XCTAssertNotEqual(replacement.pairing.deviceAlias, first.pairing.deviceAlias)
        XCTAssertEqual(
            WailoPairingStore.shared.pairing(studioId: otherStudio.studioId)?.deviceAlias,
            other.pairing.deviceAlias
        )
    }

    func testStudioSideForgetProducesAnAuthenticatedUnknownDeviceRefusal() {
        let studio = FakeStudio()
        guard case let .established(first) = studio.run(WailoHandshake(
            trust: .firstContact(expectedStudioId: nil),
            host: "10.0.0.2"
        )) else {
            return XCTFail("first contact must establish a pairing")
        }
        studio.forget(first.pairing.deviceAlias)

        guard case let .refused(reason) = studio.run(WailoHandshake(
            trust: .paired(first.pairing),
            host: "10.0.0.2"
        )) else {
            return XCTFail("Studio Forget must produce an authenticated refusal")
        }
        XCTAssertTrue(reason.localizedCaseInsensitiveContains("no longer"))
    }

    func testSocketOpeningNeverReportsConnectedBeforeAuthentication() throws {
        let server = LoopbackWebSocketServer()
        let port = try server.start()
        defer { server.stop() }

        Wailo.start(
            appId: "com.test.phase",
            deviceName: "phase",
            host: "127.0.0.1",
            port: Int(port),
            alsoLogToConsole: false
        )

        waitUntilTrue { Wailo.connectionPhase == .authenticating }
        XCTAssertFalse(Wailo.isConnected)
    }
}

// MARK: - helpers

/// Studio's half of the Wi-Fi handshake, and the part of `DeviceAdmission` that makes the reconnect
/// interesting: it *remembers* devices. A lenient stub would admit every connection and prove nothing.
private final class FakeStudio {

    enum Admission: Equatable { case firstContact, proved }

    private let identity = P256.Signing.PrivateKey()
    private let lock = NSLock()
    private var known: [String: Data] = [:]
    private var pending: Pending?
    private var admitted: [Admission] = []
    private var rejected = 0
    private var requested = 0
    private var codec: WailoFrameCodec?
    private var activeAlias: String?
    var requirePairing = false
    var offer: Data?
    var offerCode: String?
    var forgeHelloSignature = false
    var forgeResultSignature = false
    var forgeResultProof = false

    var studioId: String { WailoCrypto.studioId(publicKey: identity.publicKey.x963Representation) }
    var publicKey: Data { identity.publicKey.x963Representation }

    var admissions: [Admission] {
        lock.lock(); defer { lock.unlock() }
        return admitted
    }

    var rejections: Int {
        lock.lock(); defer { lock.unlock() }
        return rejected
    }

    var authRequests: Int {
        lock.lock(); defer { lock.unlock() }
        return requested
    }

    func knows(_ alias: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return known[alias] != nil
    }

    func forget(_ alias: String) {
        lock.lock()
        known.removeValue(forKey: alias)
        if activeAlias == alias { activeAlias = nil }
        lock.unlock()
    }

    /// Answers a live client over the loopback server. Handshake frames only — once a session is sealed
    /// everything after it is opaque here, which is fine: what is being asserted is who got in.
    func serve(on server: LoopbackWebSocketServer) {
        server.onEnvelope = { [weak self, weak server] envelope in
            guard let self, let server, let reply = self.answer(to: envelope) else { return }
            for response in reply {
                guard let data = try? ProtoEncoder().encode(response) else { continue }
                server.push(data)
            }
        }
    }

    /// Drives one handshake in-process, for the cases where a socket only adds latency.
    func run(_ handshake: WailoHandshake) -> WailoHandshake.Step {
        var step = handshake.handle(challenge(for: handshake.begin()))
        while case let .send(response) = step {
            guard let answers = answer(to: response) else { return .failed }
            for answer in answers { step = handshake.handle(answer) }
        }
        return step
    }

    private func challenge(for request: Envelope) -> Envelope {
        guard let answers = answer(to: request), let first = answers.first else {
            fatalError("an AuthClientHelloV3 always gets a StudioHelloV3")
        }
        return first
    }

    private func answer(to envelope: Envelope) -> [Envelope]? {
        switch envelope.message {
        case let .auth_client_hello_v3(request)?: return [studioHello(for: request)]
        case let .auth_device_proof_v3(response)?: return verify(response)
        case let .sealed_frame(frame)?: return handleSealed(frame)
        default: return nil
        }
    }

    private func studioHello(for request: AuthClientHelloV3) -> Envelope {
        let nonceS = WailoCrypto.randomNonce()
        let ephemeral = WailoCrypto.generateEphemeral()
        let ephemeralS = ephemeral.publicKey.x963Representation
        let shared = WailoCrypto.agree(ephemeral, peer: request.ephemeral_key) ?? Data()

        lock.lock()
        requested += 1
        pending = Pending(
            shared: shared,
            nonceD: request.nonce,
            nonceS: nonceS,
            ephemeralD: request.ephemeral_key,
            ephemeralS: ephemeralS
        )
        lock.unlock()

        let transcript = helloTranscript(
            role: "wailo/studio-hello/v3",
            nonceD: request.nonce,
            nonceS: nonceS,
            ephemeralD: request.ephemeral_key,
            ephemeralS: ephemeralS,
            pairingRequired: requirePairing
        )
        let signature = forgeHelloSignature
            ? Data(repeating: 0, count: 64)
            : ((try? identity.signature(for: transcript).rawRepresentation) ?? Data())
        return Envelope {
            $0.message = .auth_studio_hello_v3(AuthStudioHelloV3(
                nonce: nonceS,
                public_key: identity.publicKey.x963Representation,
                ephemeral_key: ephemeralS,
                signature: signature,
                pairing_required: requirePairing
            ))
        }
    }

    private func verify(_ response: AuthDeviceProofV3) -> [Envelope]? {
        lock.lock()
        defer { lock.unlock() }
        guard let pending else { return nil }
        self.pending = nil

        let firstContact: Bool
        let deviceKey: SymmetricKey
        switch response.mode {
        case .AUTH_MODE_V3_KNOWN:
            guard let stored = known[response.device_alias] else {
                return refusal(response, pending: pending, code: .AUTH_RESULT_CODE_V3_UNKNOWN_DEVICE)
            }
            deviceKey = SymmetricKey(data: stored)
            firstContact = false
        case .AUTH_MODE_V3_TOFU:
            guard !requirePairing else {
                return refusal(response, pending: pending, code: .AUTH_RESULT_CODE_V3_PAIRING_REQUIRED)
            }
            deviceKey = WailoCrypto.tofuDeviceKeyV3(
                shared: pending.shared,
                studioId: studioId,
                deviceAlias: response.device_alias
            )
            firstContact = true
        case .AUTH_MODE_V3_INVITED_QR:
            guard let offer else {
                return refusal(response, pending: pending, code: .AUTH_RESULT_CODE_V3_PAIRING_OFFER_INVALID)
            }
            deviceKey = WailoCrypto.deviceKeyV3(
                pairingSecret: offer,
                studioId: studioId,
                deviceAlias: response.device_alias
            )
            firstContact = false
        case .AUTH_MODE_V3_INVITED_CODE:
            guard let offerCode else {
                return refusal(response, pending: pending, code: .AUTH_RESULT_CODE_V3_PAIRING_OFFER_INVALID)
            }
            deviceKey = WailoCrypto.deviceKeyV3(
                pairingSecret: WailoCrypto.stretch(code: offerCode, studioId: studioId),
                studioId: studioId,
                deviceAlias: response.device_alias
            )
            firstContact = false
        case .AUTH_MODE_V3_UNSPECIFIED:
            return refusal(response, pending: pending, code: .AUTH_RESULT_CODE_V3_PAIRING_OFFER_INVALID)
        }
        let authKey = WailoCrypto.authKeyV3(shared: pending.shared, deviceKey: deviceKey)
        let expected = WailoCrypto.deviceProofV3(
            authKey: authKey,
            studioId: studioId,
            nonceD: pending.nonceD, nonceS: pending.nonceS,
            ephemeralD: pending.ephemeralD, ephemeralS: pending.ephemeralS,
            pairingRequired: requirePairing,
            deviceAlias: response.device_alias,
            mode: response.mode.rawValue,
            sessionCounter: response.session_counter
        )
        guard WailoCrypto.constantTimeEquals(response.proof, expected) else {
            rejected += 1
            return nil
        }
        known[response.device_alias] = deviceKey.bytes
        offer = nil
        offerCode = nil
        admitted.append(firstContact ? .firstContact : .proved)
        codec = WailoFrameCodec(
            sessionKey: WailoCrypto.sessionKeyV3(
                shared: pending.shared,
                deviceKey: deviceKey,
                nonceD: pending.nonceD,
                nonceS: pending.nonceS
            ),
            sealing: .studioToDevice,
            opening: .deviceToStudio
        )
        activeAlias = response.device_alias
        return [result(response, pending: pending, authKey: authKey, code: .AUTH_RESULT_CODE_V3_OK)]
    }

    private func handleSealed(_ frame: SealedFrame) -> [Envelope]? {
        lock.lock()
        defer { lock.unlock() }
        guard let codec,
              let plaintext = try? codec.open(seq: frame.seq, ciphertext: frame.ciphertext),
              let envelope = try? ProtoDecoder().decode(Envelope.self, from: plaintext),
              case let .revoke_device(revoke)? = envelope.message,
              let activeAlias else {
            return nil
        }
        known.removeValue(forKey: activeAlias)
        self.activeAlias = nil
        let acknowledgement = Envelope {
            $0.message = .revoke_device_ack(RevokeDeviceAck(request_id: revoke.request_id))
        }
        guard let encoded = try? ProtoEncoder().encode(acknowledgement),
              let sealed = try? codec.seal(encoded) else {
            return nil
        }
        return [Envelope {
            $0.message = .sealed_frame(SealedFrame(seq: sealed.seq, ciphertext: sealed.ciphertext))
        }]
    }

    private func refusal(
        _ response: AuthDeviceProofV3,
        pending: Pending,
        code: AuthResultCodeV3
    ) -> [Envelope] {
        rejected += 1
        return [result(response, pending: pending, authKey: nil, code: code)]
    }

    private func result(
        _ response: AuthDeviceProofV3,
        pending: Pending,
        authKey: SymmetricKey?,
        code: AuthResultCodeV3
    ) -> Envelope {
        let transcript = resultTranscript(
            role: "wailo/result-signature/v3",
            pending: pending,
            response: response,
            code: code
        )
        let proof = forgeResultProof ? Data(repeating: 0, count: 32) : authKey.map {
            WailoCrypto.studioProofV3(
                authKey: $0,
                studioId: studioId,
                nonceD: pending.nonceD,
                nonceS: pending.nonceS,
                ephemeralD: pending.ephemeralD,
                ephemeralS: pending.ephemeralS,
                pairingRequired: requirePairing,
                deviceAlias: response.device_alias,
                mode: response.mode.rawValue,
                sessionCounter: response.session_counter,
                resultCode: code.rawValue
            )
        } ?? Data()
        let signature = forgeResultSignature
            ? Data(repeating: 0, count: 64)
            : ((try? identity.signature(for: transcript).rawRepresentation) ?? Data())
        return Envelope {
            $0.message = .auth_result_v3(AuthResultV3(
                code: code,
                proof: proof,
                signature: signature
            ))
        }
    }

    private func helloTranscript(
        role: String,
        nonceD: Data,
        nonceS: Data,
        ephemeralD: Data,
        ephemeralS: Data,
        pairingRequired: Bool
    ) -> Data {
        label(role, Data(studioId.utf8)) + nonceD + nonceS + ephemeralD + ephemeralS +
            Data([pairingRequired ? 1 : 0])
    }

    private func resultTranscript(
        role: String,
        pending: Pending,
        response: AuthDeviceProofV3,
        code: AuthResultCodeV3
    ) -> Data {
        helloTranscript(
            role: role,
            nonceD: pending.nonceD,
            nonceS: pending.nonceS,
            ephemeralD: pending.ephemeralD,
            ephemeralS: pending.ephemeralS,
            pairingRequired: requirePairing
        ) + label("wailo/device-alias/v3", Data(response.device_alias.utf8)) +
            fixed(response.mode.rawValue) + fixed(response.session_counter) + fixed(code.rawValue)
    }

    private func label(_ text: String, _ suffix: Data) -> Data {
        Data(text.utf8) + Data([0]) + suffix + Data([0])
    }

    private func fixed(_ value: Int32) -> Data {
        withUnsafeBytes(of: value.bigEndian) { Data($0) }
    }

    private func fixed(_ value: UInt64) -> Data {
        withUnsafeBytes(of: value.bigEndian) { Data($0) }
    }

    private struct Pending {
        let shared: Data
        let nonceD: Data
        let nonceS: Data
        let ephemeralD: Data
        let ephemeralS: Data
    }
}

private final class InMemorySecretStorage: WailoSecretStorage {

    private let lock = NSLock()
    private var values: [String: Data] = [:]

    func data(for account: String) -> Data? {
        lock.lock(); defer { lock.unlock() }
        return values[account]
    }

    func set(_ data: Data?, for account: String) {
        lock.lock(); defer { lock.unlock() }
        values[account] = data
    }

    func accounts() -> [String] {
        lock.lock(); defer { lock.unlock() }
        return Array(values.keys)
    }
}

private func waitUntilTrue(
    timeout: TimeInterval = 10,
    _ predicate: @escaping () -> Bool,
    file: StaticString = #filePath,
    line: UInt = #line
) {
    let deadline = Date().addingTimeInterval(timeout)
    while !predicate(), Date() < deadline {
        Thread.sleep(forTimeInterval: 0.05)
    }
    XCTAssertTrue(predicate(), file: file, line: line)
}
