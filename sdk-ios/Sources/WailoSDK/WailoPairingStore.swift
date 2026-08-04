import Foundation
import Security

/// One paired Studio, as the device remembers it.
public struct WailoPairing: Codable, Equatable, Sendable {

    public let studioId: String
    /// The long-term secret `K`. Derived from the pairing secret, never transmitted.
    var deviceKey: Data
    /// Pinned at pairing, as an X9.63 uncompressed point. Everything after that connection is
    /// authenticated against this and nothing else, so a peer advertising the same `sid` with a
    /// different key simply fails.
    var publicKey: Data
    /// Advances on each successful handshake. Studio reads a counter that moves backwards as a cloned
    /// key — it cannot stop the clone, but it turns a silent compromise into a visible one.
    public internal(set) var sessionCounter: UInt64
    /// Set when Studio said it does not know us. Kept rather than acted on: `AuthResult` arrives
    /// unauthenticated, so deleting the key here would hand anyone a way to force a re-pair on demand.
    /// It only stops the 2-second reconnect loop until a human clears it in the panel.
    public internal(set) var refused: Bool
    /// Where this Studio was last reached. Lets a manually typed LAN address resolve back to the
    /// identity it belongs to, since a typed IP says nothing about who is listening on it.
    public internal(set) var lastHost: String
    /// Accepted on first use rather than through a QR or typed code (ADR-0040). Worth showing: nobody
    /// proved anything at that moment beyond answering at an address the user typed, so this is the
    /// one entry a careful user might want to re-establish deliberately.
    public internal(set) var trustedOnFirstUse: Bool = false
}

/// Where the device's half of every pairing lives.
///
/// The Keychain rather than `UserDefaults` — which is where `WailoHostStore` keeps the manual host —
/// because these are secrets and that file is not. Storage is behind a protocol so tests can run
/// without a signed binary; the Keychain refuses generic-password items to unsigned macOS test hosts.
protocol WailoSecretStorage: AnyObject {
    func data(for account: String) -> Data?
    func set(_ data: Data?, for account: String)
    func accounts() -> [String]
}

final class WailoPairingStore {

    static let shared = WailoPairingStore(storage: WailoKeychain(service: "com.venbiasa.wailo.pairing"))

    private let storage: WailoSecretStorage
    private let lock = NSLock()

    init(storage: WailoSecretStorage) {
        self.storage = storage
    }

    /// A stable, opaque handle for this install. Opaque on purpose: it is the one identifier that
    /// crosses the wire before either side has proved anything, so it must not leak the device's name
    /// or the app's bundle id the way `Hello` does.
    var deviceId: String {
        lock.lock()
        defer { lock.unlock() }
        if let existing = storage.data(for: Self.deviceIdAccount).flatMap({ String(data: $0, encoding: .utf8) }) {
            return existing
        }
        let generated = WailoCrypto.randomNonce().prefix(16).hexadecimal
        storage.set(Data(generated.utf8), for: Self.deviceIdAccount)
        return generated
    }

    func pairing(studioId: String) -> WailoPairing? {
        lock.lock()
        defer { lock.unlock() }
        return storage.data(for: studioId).flatMap { try? JSONDecoder().decode(WailoPairing.self, from: $0) }
    }

    func save(_ pairing: WailoPairing) {
        lock.lock()
        defer { lock.unlock() }
        guard let encoded = try? JSONEncoder().encode(pairing) else { return }
        storage.set(encoded, for: pairing.studioId)
    }

    func forget(studioId: String) {
        lock.lock()
        defer { lock.unlock() }
        storage.set(nil, for: studioId)
    }

    func forgetAll() {
        lock.lock()
        defer { lock.unlock() }
        for account in storage.accounts() where account != Self.deviceIdAccount {
            storage.set(nil, for: account)
        }
    }

    var all: [WailoPairing] {
        lock.lock()
        defer { lock.unlock() }
        return storage.accounts()
            .filter { $0 != Self.deviceIdAccount }
            .compactMap { storage.data(for: $0) }
            .compactMap { try? JSONDecoder().decode(WailoPairing.self, from: $0) }
            .sorted { $0.studioId < $1.studioId }
    }

    private static let deviceIdAccount = "__wailo_device_id"
}

/// Generic-password items, one per Studio.
final class WailoKeychain: WailoSecretStorage {

    private let service: String

    init(service: String) {
        self.service = service
    }

    func data(for account: String) -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    func set(_ data: Data?, for account: String) {
        let query = baseQuery(account: account)
        guard let data else {
            SecItemDelete(query as CFDictionary)
            return
        }
        let attributes = [kSecValueData as String: data]
        if SecItemUpdate(query as CFDictionary, attributes as CFDictionary) == errSecSuccess { return }

        var insert = query
        insert[kSecValueData as String] = data
        // The keys are only useful while the device is unlocked and this one is not worth syncing to
        // another device — a pairing is between one Studio and one install.
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(insert as CFDictionary, nil)
    }

    func accounts() -> [String] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        query[kSecReturnData as String] = false
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let items = result as? [[String: Any]] else { return [] }
        return items.compactMap { $0[kSecAttrAccount as String] as? String }
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
