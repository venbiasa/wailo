import Foundation
import Network

/// A desktop found advertising itself on the local network.
public struct WailoService: Hashable, Identifiable, Sendable {

    /// The Bonjour instance name — the Mac's hostname, which is what a human picks out of a list.
    public let name: String
    public let host: String
    public let port: Int

    /// Studio's public-key fingerprint, from the TXT record. Empty for a desktop too old to advertise
    /// one, which the coordinator treats the same as unpaired: something has to be scanned first.
    ///
    /// Read straight off the browse result, before resolving, so a desktop this device has no pairing
    /// for is never even dialled. The value is unauthenticated on its own — anyone can claim any
    /// string — but the handshake binds it: the key that signs has to hash back to this.
    public let studioId: String

    public var id: String { "\(name)|\(host):\(port)" }
    public var address: String { "\(host):\(port)" }
}

/// Finds desktops advertising `_wailo._tcp`, so a device on the same WiFi reaches the engine without
/// anyone typing the Mac's LAN IP. iOS has no `adb reverse` to hide behind and DHCP moves the address,
/// so a hard-coded host means a rebuild every time the network changes — ADR-0004 deferred this,
/// ADR-0035 lands it.
///
/// State is confined to a private serial queue, so this is safe to call from any thread.
final class WailoDiscovery: @unchecked Sendable {

    static let serviceType = "_wailo._tcp"

    /// TXT key carrying Studio's public-key fingerprint.
    static let studioIdKey = "sid"

    /// Backoff for rebuilding a browser the OS reported as failed. Doubles up to the cap because the
    /// commonest cause is permanent — a host app that never declared `NSBonjourServices` /
    /// `NSLocalNetworkUsageDescription` gets denied on every attempt — and retrying that at a fixed
    /// couple of seconds would spin for the life of the process. The cap still recovers a genuinely
    /// transient failure, just less eagerly.
    private static let minRestartDelay: TimeInterval = 2.0
    private static let maxRestartDelay: TimeInterval = 30.0

    private let queue = DispatchQueue(label: "com.venbiasa.wailo.discovery")
    private var browser: NWBrowser?
    /// Throwaway connections used purely to turn a Bonjour service into an address, keyed by instance name.
    private var resolvers: [String: NWConnection] = [:]
    private var resolved: [String: WailoService] = [:]
    /// Kept beside the resolved set because the TXT record arrives with the browse result, well before
    /// the address does.
    private var studioIds: [String: String] = [:]
    private var started = false
    private var restartDelay = WailoDiscovery.minRestartDelay

    /// Fires on the discovery queue whenever the resolved set changes.
    var onChange: (([WailoService]) -> Void)?

    func start() {
        queue.async {
            guard !self.started else { return }
            self.started = true
            self.restartDelay = Self.minRestartDelay
            self.browse()
        }
    }

    func stop() {
        queue.async {
            self.started = false
            self.browser?.cancel()
            self.browser = nil
            for resolver in self.resolvers.values { resolver.cancel() }
            self.resolvers.removeAll()
            let hadResults = !self.resolved.isEmpty
            self.resolved.removeAll()
            self.studioIds.removeAll()
            if hadResults { self.publish() }
        }
    }

    // MARK: - queue-confined

    private func browse() {
        let parameters = NWParameters()
        // AWDL peer-to-peer would let this find a Mac with no shared WiFi, but it also spins up the
        // radio and widens the local-network prompt's scope for a case the desktop can't serve anyway.
        parameters.includePeerToPeer = false

        let browser = NWBrowser(for: .bonjour(type: Self.serviceType, domain: nil), using: parameters)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            self?.queue.async { self?.handle(results) }
        }
        browser.stateUpdateHandler = { [weak self] state in
            self?.queue.async {
                switch state {
                case .ready: self?.restartDelay = Self.minRestartDelay
                case .failed: self?.restart()
                default: break
                }
            }
        }
        self.browser = browser
        browser.start(queue: queue)
    }

    private func handle(_ results: Set<NWBrowser.Result>) {
        var seen = Set<String>()
        for result in results {
            guard case let .service(name, type, domain, _) = result.endpoint else { continue }
            seen.insert(name)
            studioIds[name] = Self.studioId(from: result.metadata)
            guard resolvers[name] == nil, resolved[name] == nil else { continue }
            resolve(name: name, endpoint: .service(name: name, type: type, domain: domain, interface: nil))
        }

        let vanished = Set(resolvers.keys).union(resolved.keys).subtracting(seen)
        guard !vanished.isEmpty else { return }
        for name in vanished {
            resolvers.removeValue(forKey: name)?.cancel()
            resolved.removeValue(forKey: name)
            studioIds.removeValue(forKey: name)
        }
        publish()
    }

    private static func studioId(from metadata: NWBrowser.Result.Metadata) -> String {
        guard case let .bonjour(record) = metadata else { return "" }
        return record[Self.studioIdKey] ?? ""
    }

    /// Bonjour hands back a *service*, but `URLSessionWebSocketTask` needs a `ws://host:port` URL and
    /// Network.framework exposes no standalone "resolve this service" call. Opening a throwaway
    /// connection and reading the endpoint the OS settled on is the supported way to get one — and it
    /// inherits Happy Eyeballs' IPv4/IPv6 choice instead of us guessing from a raw DNS answer. The cost
    /// is one short-lived TCP connect per desktop found, which the engine sees as a client that hung up
    /// before sending a request.
    private func resolve(name: String, endpoint: NWEndpoint) {
        let connection = NWConnection(to: endpoint, using: .tcp)
        resolvers[name] = connection
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                let remote = connection.currentPath?.remoteEndpoint
                self.queue.async { self.finishResolve(name: name, remote: remote) }
            case .failed, .cancelled:
                self.queue.async { self.finishResolve(name: name, remote: nil) }
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func finishResolve(name: String, remote: NWEndpoint?) {
        // The cancel below re-enters via `.cancelled`; the missing key makes the second pass a no-op.
        guard let connection = resolvers.removeValue(forKey: name) else { return }
        connection.cancel()
        guard case let .hostPort(host, port)? = remote, let address = Self.address(from: host) else { return }
        resolved[name] = WailoService(
            name: name,
            host: address,
            port: Int(port.rawValue),
            studioId: studioIds[name] ?? ""
        )
        publish()
    }

    private func restart() {
        guard started else { return }
        browser?.cancel()
        browser = nil
        let delay = restartDelay
        restartDelay = min(delay * 2, Self.maxRestartDelay)
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, self.started, self.browser == nil else { return }
            self.browse()
        }
    }

    private func publish() {
        let snapshot = resolved.values.sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
        onChange?(snapshot)
    }

    private static func address(from host: NWEndpoint.Host) -> String? {
        switch host {
        case let .ipv4(address):
            return scopeless(address.debugDescription)
        case let .ipv6(address):
            // A v4-mapped address dials fine as a dotted quad and keeps the URL readable; a genuine v6
            // literal has to be bracketed to be a legal URL authority.
            if let mapped = address.asIPv4 { return scopeless(mapped.debugDescription) }
            return "[\(scopeless(address.debugDescription))]"
        case let .name(name, _):
            return name
        @unknown default:
            return nil
        }
    }

    /// `IPv4Address`/`IPv6Address` print a `%en0` zone suffix when scoped, which is illegal in a URL.
    private static func scopeless(_ address: String) -> String {
        String(address.prefix { $0 != "%" })
    }
}
