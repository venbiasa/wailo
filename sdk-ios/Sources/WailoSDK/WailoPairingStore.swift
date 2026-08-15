import Foundation
import Security

/// One paired Studio, as the device remembers it.
public struct WailoPairing: Codable, Equatable, Sendable {

    public let studioId: String
    /// Random handle scoped to this Studio relationship. It is revealed only after Studio proves its
    /// identity and is replaced, rather than reused, after Forget (ADR-0060).
    public internal(set) var deviceAlias: String = ""
    /// The long-term relationship secret, derived from an invite or the authenticated TOFU exchange
    /// and never transmitted.
    var deviceKey: Data
    /// Pinned at pairing, as an X9.63 uncompressed point. Everything after that connection is
    /// authenticated against this and nothing else, so a peer advertising the same `sid` with a
    /// different key simply fails.
    var publicKey: Data
    /// Advances on each successful handshake. Studio reads a counter that moves backwards as a cloned
    /// key — it cannot stop the clone, but it turns a silent compromise into a visible one.
    public internal(set) var sessionCounter: UInt64
    /// Set by an authenticated refusal; it stops retries until a human chooses Retry or Forget.
    public internal(set) var refused: Bool
    /// Where this Studio was last reached. Lets a manually typed LAN address resolve back to the
    /// identity it belongs to, since a typed IP says nothing about who is listening on it.
    public internal(set) var lastHost: String
    /// Accepted on first use rather than through a QR or typed code (ADR-0040). Worth showing: nobody
    /// proved anything at that moment beyond answering at an address the user typed, so this is the
    /// one entry a careful user might want to re-establish deliberately.
    public internal(set) var trustedOnFirstUse: Bool = false
}

extension WailoPairing {

    private enum CodingKeys: String, CodingKey {
        case studioId
        case deviceAlias
        case deviceKey
        case publicKey
        case sessionCounter
        case refused
        case lastHost
        case trustedOnFirstUse
    }

    /// Keychain records outlive SDK upgrades. Keep authentication material mandatory, but default
    /// metadata added by newer builds so one missing field cannot silently discard the device's key.
    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        studioId = try values.decode(String.self, forKey: .studioId)
        deviceAlias = try values.decode(String.self, forKey: .deviceAlias)
        guard deviceAlias.utf8.count == 32,
              deviceAlias.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else {
            throw DecodingError.dataCorruptedError(
                forKey: .deviceAlias,
                in: values,
                debugDescription: "V3 device aliases must be 32 lowercase hexadecimal characters"
            )
        }
        deviceKey = try values.decode(Data.self, forKey: .deviceKey)
        publicKey = try values.decode(Data.self, forKey: .publicKey)
        sessionCounter = try values.decodeIfPresent(UInt64.self, forKey: .sessionCounter) ?? 0
        refused = try values.decodeIfPresent(Bool.self, forKey: .refused) ?? false
        lastHost = try values.decodeIfPresent(String.self, forKey: .lastHost) ?? ""
        trustedOnFirstUse = try values.decodeIfPresent(Bool.self, forKey: .trustedOnFirstUse) ?? false
    }

    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(studioId, forKey: .studioId)
        try values.encode(deviceAlias, forKey: .deviceAlias)
        try values.encode(deviceKey, forKey: .deviceKey)
        try values.encode(publicKey, forKey: .publicKey)
        try values.encode(sessionCounter, forKey: .sessionCounter)
        try values.encode(refused, forKey: .refused)
        try values.encode(lastHost, forKey: .lastHost)
        try values.encode(trustedOnFirstUse, forKey: .trustedOnFirstUse)
    }
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

    // A clean namespace is the v3 migration: old global-id records remain unread rather than being
    // mistaken for Studio-scoped aliases. The host app does not need to mutate Keychain at upgrade.
    private static let service = "com.venbiasa.wailo.pairing.v3"

    #if DEBUG
    /// Swappable in debug builds only, because otherwise the WiFi handshake has no test coverage at
    /// all: the Keychain refuses generic-password items to an unsigned macOS test host, which is the
    /// same constraint [WailoSecretStorage] exists for. The shipping SDK keeps one place for its keys.
    static var shared = WailoPairingStore(storage: WailoKeychain(service: service))
    #else
    static let shared = WailoPairingStore(storage: WailoKeychain(service: service))
    #endif

    private let storage: WailoSecretStorage
    private let lock = NSLock()

    init(storage: WailoSecretStorage) {
        self.storage = storage
    }

    func newDeviceAlias() -> String {
        WailoCrypto.randomNonce().prefix(16).hexadecimal
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
        for account in storage.accounts() { storage.set(nil, for: account) }
    }

    var all: [WailoPairing] {
        lock.lock()
        defer { lock.unlock() }
        return storage.accounts()
            .compactMap { storage.data(for: $0) }
            .compactMap { try? JSONDecoder().decode(WailoPairing.self, from: $0) }
            .sorted { $0.studioId < $1.studioId }
    }
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
