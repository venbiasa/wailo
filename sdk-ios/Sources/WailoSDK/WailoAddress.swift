import Foundation
import Network

/// A desktop address known to be diallable: a host that is legal inside a URL authority, plus the port
/// the text named (`nil` when it named none, leaving the choice to the caller).
///
/// Every address here arrives as free text — the on-device panel's field, a `-WailoHost` launch
/// argument, a literal passed to `Wailo.start` — and `URL(string:)` returns nil for text it cannot
/// parse. Typing an IP together with its port into the address field yields `ws://10.0.0.2:8080:8899/`,
/// which is one of those, so the transport's force-unwrap turned a typo into a crash of the *host app*;
/// and since the text was persisted before it was ever dialled, the crash then repeated on every launch.
/// Parsing is therefore the one gate in front of `UserDefaults` and the transport: text that names no
/// diallable address is refused, never stored.
///
/// Forgiving about shape, strict about legality. `host:port`, a bare IPv6 literal, and a pasted
/// `ws://host:port/` all resolve, because each is a plausible thing to type and none is ambiguous.
/// Anything Foundation would reject is refused up front rather than dialled and left to look like "the
/// desktop isn't running".
public struct WailoAddress: Equatable, Sendable, CustomStringConvertible {

    public static let portRange = 1...65_535

    /// Ready to drop into a URL authority — an IPv6 literal keeps the brackets it needs there.
    public let host: String

    /// The port the text named, or `nil` for "whatever the caller resolves to": a discovered service's
    /// port, a separately configured one, or `Wailo.defaultPort`.
    public let port: Int?

    /// Canonical `host` or `host:port`, which re-parses to the same address.
    public var description: String { port.map { "\(host):\($0)" } ?? host }

    public init?(_ text: String) {
        guard let (host, portText) = Self.split(Self.authority(of: text)) else { return nil }

        var port: Int?
        if let portText {
            guard let value = Int(portText), Self.portRange.contains(value) else { return nil }
            port = value
        }
        guard Self.isDiallable(host: host, port: port) else { return nil }

        self.host = host
        self.port = port
    }

    /// The `ws://` URL for a host and port. Optional on purpose, and the only place in the SDK that
    /// turns an address into a URL: `URL(string:)` failing here is the crash this type exists to
    /// prevent, so no caller gets to force it.
    static func webSocketURL(host: String, port: Int) -> URL? {
        URL(string: "ws://\(host):\(port)/")
    }

    // MARK: - parsing

    /// Reduces a pasted address to its `host[:port]` authority. Addresses get copied from somewhere —
    /// Studio prints its own as a URL — so a scheme and a trailing slash are decoration, not intent.
    private static func authority(of text: String) -> String {
        var authority = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let scheme = authority.range(of: "://") {
            authority = String(authority[scheme.upperBound...])
        }
        while authority.hasSuffix("/") {
            authority.removeLast()
        }
        return authority
    }

    private static func split(_ authority: String) -> (host: String, port: String?)? {
        guard !authority.isEmpty else { return nil }

        if authority.hasPrefix("[") {
            guard let close = authority.firstIndex(of: "]") else { return nil }
            let host = String(authority[...close])
            let rest = authority[authority.index(after: close)...]
            if rest.isEmpty { return (host, nil) }
            guard rest.hasPrefix(":") else { return nil }
            return (host, String(rest.dropFirst()))
        }

        let parts = authority.split(separator: ":", omittingEmptySubsequences: false)
        switch parts.count {
        case 1:
            return (authority, nil)
        case 2:
            return (String(parts[0]), String(parts[1]))
        default:
            // Several colons and no brackets reads as an IPv6 literal typed bare. Bracketing is what
            // makes it a legal authority, and a port could not have been expressed without them —
            // `isDiallable` is what rejects the other reading of the same text, a doubled port.
            return ("[\(authority)]", nil)
        }
    }

    private static let illegalInHost = CharacterSet.whitespacesAndNewlines
        .union(CharacterSet(charactersIn: "/?#@[]\\"))

    private static func isDiallable(host: String, port: Int?) -> Bool {
        if host.hasPrefix("[") {
            // Foundation takes a bracketed authority on trust without parsing what is inside, so a
            // `10.0.0.2:8080:9000` double-port typo would otherwise pass as an IPv6 literal.
            guard host.hasSuffix("]"),
                  IPv6Address(String(host.dropFirst().dropLast())) != nil else { return false }
        } else if host.isEmpty || host.rangeOfCharacter(from: illegalInHost) != nil {
            return false
        }
        // Foundation decides what it will parse, so an address is valid exactly when the URL it forms
        // exists. This is the check whose absence crashed the app.
        return webSocketURL(host: host, port: port ?? Wailo.defaultPort) != nil
    }
}
