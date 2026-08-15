import Foundation

/// Everything needed to pair with one Studio, however the user supplied it.
///
/// A scanned QR fills all of this in one shot, including the public key, so Studio's signed hello is
/// pinned on the first connection. A typed code cannot carry a key, so `publicKey` is nil and the
/// device pins the key in the signed v3 hello after checking its fingerprint; the authenticated result
/// then proves Studio selected the same code-derived credential.
public struct WailoPairingInvite: Equatable, Sendable {

    public let studioId: String
    public let host: String
    public let port: Int
    /// X9.63 uncompressed point, matching what `AuthStudioHelloV3.public_key` carries.
    public let publicKey: Data?
    public let pairingSecret: Data
    /// Tells Studio which half of its offer to answer under. The QR's secret and the typed code's are
    /// independent so neither is capped by the other's entropy, which leaves Studio unable to guess.
    public let pairedByCode: Bool

    /// Parses the `wailo://pair` URL a Studio QR encodes. Returns nil for anything malformed rather
    /// than partially applying it — a half-read invite would pair against the wrong identity.
    public init?(qr text: String) {
        guard let components = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme == "wailo",
              components.host == "pair" else { return nil }

        let items = (components.queryItems ?? []).reduce(into: [String: String]()) { map, item in
            map[item.name] = item.value
        }
        guard let studioId = items["sid"], !studioId.isEmpty,
              let host = items["h"], !host.isEmpty,
              let port = items["p"].flatMap(Int.init), WailoAddress.portRange.contains(port),
              let secret = items["s"].flatMap(Data.init(base64URL:)),
              secret.count == WailoCrypto.keyLength else { return nil }

        let publicKey = items["k"].flatMap(Data.init(base64URL:))
        // A key that does not hash to the identity it claims is a malformed invite, not an attack we
        // need to tolerate — refusing here keeps the "sid is the fingerprint" invariant total.
        if let publicKey, WailoCrypto.studioId(publicKey: publicKey) != studioId { return nil }

        self.studioId = studioId
        self.host = host
        self.port = port
        self.publicKey = publicKey
        self.pairingSecret = secret
        self.pairedByCode = false
    }

    /// The typed-code path: the user picks a discovered desktop and types what Studio is showing.
    public init?(code: String, studioId: String, host: String, port: Int) {
        guard let code = WailoPairingCode.normalize(code) else { return nil }
        self.studioId = studioId
        self.host = host
        self.port = port
        self.publicKey = nil
        self.pairingSecret = WailoCrypto.stretch(code: code, studioId: studioId)
        self.pairedByCode = true
    }
}

/// The fallback code's alphabet: Crockford base32, which drops I, L, O and U so nothing reads
/// ambiguously off a screen. Ten characters carry ~50 bits, which is only safe to type because
/// `WailoCrypto.stretch` puts a slow KDF behind it.
///
/// Public because a host that builds its own pairing screen needs the same rules the SDK derives
/// under — what a keystroke may be, how long the code is — and guessing at them means an entry field
/// that accepts input the handshake will silently reject.
public enum WailoPairingCode {

    public static let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
    public static let length = 10

    /// Accepts the grouping and casing a human actually types, and rejects anything that is not
    /// exactly this alphabet — an unrecognised character would otherwise stretch into a silently
    /// wrong secret and surface as an unexplained handshake failure.
    public static func normalize(_ text: String) -> String? {
        let stripped = text.uppercased().filter { !$0.isWhitespace && $0 != "-" }
        guard stripped.count == length, stripped.allSatisfy({ alphabet.contains($0) }) else { return nil }
        return stripped
    }

    /// Everything typeable kept, everything else dropped, capped at [length]. For filtering a field on
    /// each keystroke, where [normalize] can only answer once the last character has landed. Anything
    /// this returns at full length is accepted by [normalize].
    public static func sanitize(_ text: String) -> String {
        String(text.uppercased().filter(alphabet.contains).prefix(length))
    }
}

extension Data {
    init?(base64URL text: String) {
        var base64 = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64.append(String(repeating: "=", count: (4 - base64.count % 4) % 4))
        guard let decoded = Data(base64Encoded: base64) else { return nil }
        self = decoded
    }

    var base64URL: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
