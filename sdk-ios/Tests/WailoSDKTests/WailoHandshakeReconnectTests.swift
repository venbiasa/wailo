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
/// key, and every retry after that fails the mac check in silence. That was the shape of the bug this
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

    /// A new address says where Studio moved, not which saved identity it is. The challenge supplies
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
            deviceKey: Data(repeating: 0x7a, count: 32),
            publicKey: unrelatedKey,
            sessionCounter: 1,
            refused: false,
            lastHost: "10.0.0.9",
            trustedOnFirstUse: false
        ))

        XCTAssertTrue(Wailo.setHost("127.0.0.1", port: Int(port)))

        waitUntilTrue { studio.admissions.count == 2 }
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
        let deviceId = "0123456789abcdef"
        let knownStudio = FakeStudio()
        let newStudio = FakeStudio()

        let known = WailoHandshake(trust: .firstContact, deviceId: deviceId, host: "10.0.0.2")
        guard case let .established(established) = knownStudio.run(known) else {
            return XCTFail("the existing Studio must establish the saved pairing")
        }

        let new = WailoHandshake(
            trust: .knownOrFirstContact([established.pairing]),
            deviceId: deviceId,
            host: "10.0.0.77"
        )
        guard case let .established(result) = newStudio.run(new) else {
            return XCTFail("a user-chosen address with a new identity must remain eligible for first contact")
        }

        XCTAssertEqual(result.pairing.studioId, newStudio.studioId)
        XCTAssertTrue(result.pairing.trustedOnFirstUse)
    }

    /// The mechanism behind the failure above, asserted directly so the reason the upgrade is
    /// load-bearing survives a refactor of how it gets there.
    func testAStrangerIsRejectedOnceStudioHoldsAKeyForIt() throws {
        let studio = FakeStudio()
        let deviceId = "0123456789abcdef"

        let first = WailoHandshake(trust: .firstContact, deviceId: deviceId, host: "10.0.0.2")
        guard case let .established(established) = studio.run(first) else {
            return XCTFail("a first contact must be admitted")
        }

        let stranger = WailoHandshake(trust: .firstContact, deviceId: deviceId, host: "10.0.0.2")
        guard case .failed = studio.run(stranger) else {
            return XCTFail("no key mixed in means a mac neither side agrees on")
        }

        let paired = WailoHandshake(
            trust: .paired(
                studioId: established.pairing.studioId,
                deviceKey: SymmetricKey(data: established.pairing.deviceKey),
                publicKey: established.pairing.publicKey,
                sessionCounter: established.pairing.sessionCounter
            ),
            deviceId: deviceId,
            host: "10.0.0.2"
        )
        guard case .established = studio.run(paired) else {
            return XCTFail("the key the first contact stored must be the one Studio expects")
        }
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

    var studioId: String { WailoCrypto.studioId(publicKey: identity.publicKey.x963Representation) }

    var admissions: [Admission] {
        lock.lock(); defer { lock.unlock() }
        return admitted
    }

    var rejections: Int {
        lock.lock(); defer { lock.unlock() }
        return rejected
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
            fatalError("an AuthRequest always gets a challenge")
        }
        return first
    }

    private func answer(to envelope: Envelope) -> [Envelope]? {
        switch envelope.message {
        case let .auth_request(request)?: return [openChallenge(for: request)]
        case let .auth_response(response)?: return verify(response)
        default: return nil
        }
    }

    private func openChallenge(for request: AuthRequest) -> Envelope {
        let nonceS = WailoCrypto.randomNonce()
        let ephemeral = WailoCrypto.generateEphemeral()
        let ephemeralS = ephemeral.publicKey.x963Representation
        let shared = WailoCrypto.agree(ephemeral, peer: request.ephemeral_key) ?? Data()

        lock.lock()
        let deviceKey = known[request.device_id].map(SymmetricKey.init(data:))
        let authKey = WailoCrypto.authKey(shared: shared, deviceKey: deviceKey)
        pending = Pending(
            deviceId: request.device_id,
            authKey: authKey,
            shared: shared,
            nonceD: request.nonce,
            nonceS: nonceS,
            ephemeralD: request.ephemeral_key,
            ephemeralS: ephemeralS,
            firstContact: deviceKey == nil
        )
        lock.unlock()

        let transcript = WailoCrypto.transcript(
            role: "wailo/studio", studioId: studioId,
            nonceD: request.nonce, nonceS: nonceS, ephemeralD: request.ephemeral_key, ephemeralS: ephemeralS
        )
        return Envelope {
            $0.message = .auth_challenge(AuthChallenge(
                nonce: nonceS,
                signature: (try? identity.signature(for: transcript).rawRepresentation) ?? Data(),
                mac: WailoCrypto.studioMac(
                    authKey: authKey, studioId: studioId,
                    nonceD: request.nonce, nonceS: nonceS, ephemeralD: request.ephemeral_key, ephemeralS: ephemeralS
                ),
                public_key: identity.publicKey.x963Representation,
                ephemeral_key: ephemeralS
            ))
        }
    }

    private func verify(_ response: AuthResponse) -> [Envelope]? {
        lock.lock()
        defer { lock.unlock() }
        guard let pending else { return nil }
        self.pending = nil

        let expected = WailoCrypto.deviceProof(
            authKey: pending.authKey, studioId: studioId,
            nonceD: pending.nonceD, nonceS: pending.nonceS,
            ephemeralD: pending.ephemeralD, ephemeralS: pending.ephemeralS
        )
        guard WailoCrypto.constantTimeEquals(response.proof, expected) else {
            rejected += 1
            return nil
        }
        // Trust on first use: the key is derived from this connection's agreed secret by both ends and
        // kept, which is exactly what makes the *next* connection a keyed one.
        if known[pending.deviceId] == nil {
            known[pending.deviceId] = WailoCrypto
                .tofuDeviceKey(shared: pending.shared, studioId: studioId, deviceId: pending.deviceId)
                .bytes
        }
        admitted.append(pending.firstContact ? .firstContact : .proved)
        return [Envelope { $0.message = .auth_result(AuthResult(ok: true, reason: "")) }]
    }

    private struct Pending {
        let deviceId: String
        let authKey: SymmetricKey
        let shared: Data
        let nonceD: Data
        let nonceS: Data
        let ephemeralD: Data
        let ephemeralS: Data
        let firstContact: Bool
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
