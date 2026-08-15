import XCTest
import Network
import Wire
import WailoProtocol
@testable import WailoSDK

/// End-to-end transport check: stands up a real WebSocket server (Network.framework) and asserts
/// `WailoClient` opens with a decodable `Hello` and then streams a live exchange — the same
/// bytes the Kotlin `engine` decodes. The Swift analog of the desktop client<->server loopback test.
final class WailoClientLoopbackTests: XCTestCase {

    func testStreamsHelloThenExchange() throws {
        let server = LoopbackWebSocketServer()
        let port = try server.start()

        let helloReceived = expectation(description: "hello")
        let exchangeReceived = expectation(description: "exchange")
        server.onEnvelope = { envelope in
            switch envelope.message {
            case let .hello(hello):
                XCTAssertEqual(hello.platform, "ios")
                XCTAssertEqual(hello.app_id, "com.test")
                helloReceived.fulfill()
            case let .exchange(exchange):
                XCTAssertEqual(exchange.id, "e1")
                exchangeReceived.fulfill()
            default:
                break
            }
        }

        let client = WailoClient(
            hello: Hello(device_name: "test", app_id: "com.test", platform: "ios"),
            url: loopbackURL(port)
        )
        client.start()
        // Captured during the (healthy) connection's handshake; delivered live after the Hello.
        client.onExchange(HttpExchange(id: "e1", started_at_epoch_ms: 1, duration_ms: 2, error: "", edited: false))

        wait(for: [helloReceived, exchangeReceived], timeout: 10)
        client.stopAndWaitForTeardown()
        server.stop()
    }

    /// The panel's crash, end to end. An address typed with its port made the coordinator build
    /// `ws://127.0.0.1:<port>:8899/` — not a URL Foundation will parse — and the transport force-unwrapped
    /// it, taking the host app down. The port has to be split off the address and dialled.
    func testAddressCarryingItsPortIsDialled() throws {
        // Pinned before start so no Bonjour browser (and no permission prompt) enters this test.
        WailoHostStore.host = "127.0.0.1"
        defer { WailoHostStore.clear() }

        let server = LoopbackWebSocketServer()
        let port = try server.start()
        defer { server.stop() }

        let helloReceived = expectation(description: "hello on the port typed into the address")
        helloReceived.assertForOverFulfill = false
        server.onEnvelope = { envelope in
            if case .hello = envelope.message { helloReceived.fulfill() }
        }

        Wailo.start(appId: "com.test.inline", deviceName: "inline-port", alsoLogToConsole: false)
        defer { Wailo.stop() }

        XCTAssertTrue(Wailo.setHost("127.0.0.1:\(port)"))
        wait(for: [helloReceived], timeout: 10)
        XCTAssertEqual(Wailo.activeAddress, "127.0.0.1:\(port)")
    }

    /// Map Local rides the same socket (desktop -> device RuleSet pushes). Prove the reconnect
    /// hardening left that path intact: a RuleSet pushed by the server must flow through the receive
    /// loop into `WailoRuleStore`, and the client must ack the snapshot's epoch back (ADR-0019). The
    /// existing Map Local tests set the store directly and never exercise this wire path.
    func testAppliesRuleSetPushedByServerAndAcksEpoch() throws {
        WailoRuleStore.shared.replace([])
        defer { WailoRuleStore.shared.replace([]) }

        let server = LoopbackWebSocketServer()
        let port = try server.start()
        let ackReceived = expectation(description: "rule ack")
        ackReceived.assertForOverFulfill = false
        // Once the client's Hello arrives, push a rule set back down the same connection; then expect
        // the client to echo the epoch in a RuleAck.
        server.onEnvelope = { [weak server] envelope in
            switch envelope.message {
            case .hello:
                let rule = MapLocalRule(id: "pushed", enabled: true, url_pattern: "https://pushed.test/*") {
                    $0.methods = []
                }
                let push = Envelope { $0.message = .rule_set(RuleSet(epoch: 7) { $0.rules = [rule] }) }
                if let data = try? ProtoEncoder().encode(push) { server?.push(data) }
            case let .rule_ack(ack) where ack.epoch == 7:
                ackReceived.fulfill()
            default:
                break
            }
        }

        let client = WailoClient(
            hello: Hello(device_name: "test", app_id: "com.test", platform: "ios"),
            url: loopbackURL(port)
        )
        client.start()
        defer { client.stopAndWaitForTeardown() }

        // The pushed rule must land in the store via `handleIncoming`.
        var applied = false
        for _ in 0..<100 where !applied {
            if WailoRuleStore.shared.match(url: "https://pushed.test/a", method: "GET") != nil {
                applied = true
            } else {
                Thread.sleep(forTimeInterval: 0.05)
            }
        }
        XCTAssertTrue(applied, "a RuleSet pushed over the socket must reach WailoRuleStore")
        wait(for: [ackReceived], timeout: 10)
        server.stop()
    }

    /// The desktop is the source of truth for Map Local: when the connection drops, the device must
    /// discard the cached snapshot so no rule outlives the desktop. A reconnect re-pushes it.
    func testDropsCachedRulesWhenConnectionCut() throws {
        WailoRuleStore.shared.replace([])
        defer { WailoRuleStore.shared.replace([]) }

        // Own the port so the server can leave without the OS handing it to someone else.
        let port = try reserveEphemeralPort()
        let server = LoopbackWebSocketServer()
        server.onEnvelope = { [weak server] envelope in
            guard case .hello = envelope.message else { return }
            let rule = MapLocalRule(id: "pushed", enabled: true, url_pattern: "https://pushed.test/*") {
                $0.methods = []
            }
            let push = Envelope { $0.message = .rule_set(RuleSet(epoch: 1) { $0.rules = [rule] }) }
            if let data = try? ProtoEncoder().encode(push) { server?.push(data) }
        }
        try server.start(on: port)

        let client = WailoClient(
            hello: Hello(device_name: "test", app_id: "com.test", platform: "ios"),
            url: loopbackURL(port),
            reconnectDelay: 0.2
        )
        client.start()
        defer { client.stopAndWaitForTeardown() }

        // Precondition: the pushed rule is cached and matching.
        var applied = false
        for _ in 0..<100 where !applied {
            if WailoRuleStore.shared.match(url: "https://pushed.test/a", method: "GET") != nil {
                applied = true
            } else {
                Thread.sleep(forTimeInterval: 0.05)
            }
        }
        XCTAssertTrue(applied, "precondition: the pushed rule must be cached before the drop")

        // Desktop disappears; the client must notice and clear the cached snapshot.
        server.stop()

        var cleared = false
        for _ in 0..<200 where !cleared {
            if WailoRuleStore.shared.match(url: "https://pushed.test/a", method: "GET") == nil {
                cleared = true
            } else {
                Thread.sleep(forTimeInterval: 0.05)
            }
        }
        XCTAssertTrue(cleared, "a cut connection must clear the cached Map Local rules")
    }

    /// Breakpoint rules ride the same control channel as Map Local (ADR-0027): a `BreakpointRules`
    /// snapshot pushed by the desktop must land in `WailoBreakpointStore` via the receive loop, and the
    /// client must ack the snapshot's epoch back so a lost push self-repairs (anti-entropy).
    func testAppliesBreakpointRulesPushedByServerAndAcksEpoch() throws {
        WailoBreakpointStore.shared.replace([])
        defer { WailoBreakpointStore.shared.replace([]) }

        let server = LoopbackWebSocketServer()
        let port = try server.start()
        let ackReceived = expectation(description: "breakpoint rule ack")
        ackReceived.assertForOverFulfill = false
        server.onEnvelope = { [weak server] envelope in
            switch envelope.message {
            case .hello:
                let rule = BreakpointRule(
                    id: "bp",
                    enabled: true,
                    url_pattern: "https://bp.test/*",
                    on_request: true,
                    on_response: false
                )
                let push = Envelope { $0.message = .breakpoint_rules(BreakpointRules(epoch: 5) { $0.rules = [rule] }) }
                if let data = try? ProtoEncoder().encode(push) { server?.push(data) }
            case let .breakpoint_rules_ack(ack) where ack.epoch == 5:
                ackReceived.fulfill()
            default:
                break
            }
        }

        let client = WailoClient(
            hello: Hello(device_name: "test", app_id: "com.test", platform: "ios"),
            url: loopbackURL(port)
        )
        client.start()
        defer { client.stopAndWaitForTeardown() }

        var applied = false
        for _ in 0..<100 where !applied {
            if WailoBreakpointStore.shared.match(url: "https://bp.test/a", method: "GET") != nil {
                applied = true
            } else {
                Thread.sleep(forTimeInterval: 0.05)
            }
        }
        XCTAssertTrue(applied, "a BreakpointRules push must reach WailoBreakpointStore")
        wait(for: [ackReceived], timeout: 10)
        server.stop()
    }

    /// The breakpoint hold round-trips over the socket: `pauseRequest` streams a `BreakpointHit`, and the
    /// desktop's `BreakpointDecision` (correlated by id) resolves the hold. A PROCEED carrying an edited
    /// request must surface as `.proceed(edited)` so the interceptor sends the desktop's version.
    func testBreakpointDecisionResolvesPendingHit() throws {
        let server = LoopbackWebSocketServer()
        let port = try server.start()
        let helloReceived = expectation(description: "hello")
        helloReceived.assertForOverFulfill = false
        // On a hit, answer PROCEED with an edited request echoing the hit's correlation id.
        server.onEnvelope = { [weak server] envelope in
            switch envelope.message {
            case .hello:
                helloReceived.fulfill()
            case let .breakpoint_hit(hit):
                let decision = Envelope {
                    $0.message = .breakpoint_decision(BreakpointDecision(
                        correlation_id: hit.correlation_id,
                        action: .BREAKPOINT_ACTION_PROCEED
                    ) {
                        $0.edited_request = HttpRequest(
                            method: "POST",
                            url: "https://edited.test/x",
                            body: Data("edited".utf8),
                            body_size: 6,
                            body_truncated: false
                        )
                    })
                }
                if let data = try? ProtoEncoder().encode(decision) { server?.push(data) }
            default:
                break
            }
        }

        let client = WailoClient(
            hello: Hello(device_name: "test", app_id: "com.test", platform: "ios"),
            url: loopbackURL(port)
        )
        client.start()
        defer { client.stopAndWaitForTeardown() }
        // Wait for the link so the hit is streamed, not failed open for want of a socket.
        wait(for: [helloReceived], timeout: 10)

        let resolved = expectation(description: "decision resolved")
        var decided: WailoRequestDecision?
        client.pauseRequest(
            ruleId: "bp",
            request: HttpRequest(method: "GET", url: "https://bp.test/x", body: Data(), body_size: 0, body_truncated: false)
        ) { decision in
            decided = decision
            resolved.fulfill()
        }
        wait(for: [resolved], timeout: 10)

        guard case let .proceed(edited) = decided else {
            XCTFail("expected proceed with edits, got \(String(describing: decided))")
            server.stop()
            return
        }
        XCTAssertEqual(edited?.method, "POST")
        XCTAssertEqual(edited?.url, "https://edited.test/x")
        server.stop()
    }

    /// Fail-open on disconnect (ADR-0027): a request held at a breakpoint must not hang forever if the
    /// desktop vanishes mid-decision. When the socket drops with a hit still pending, the hold resolves
    /// `.proceed(nil)` so the interceptor sends the original request to the real network.
    func testBreakpointFailsOpenWhenConnectionCut() throws {
        let server = LoopbackWebSocketServer()
        let port = try server.start()
        let hitReceived = expectation(description: "hit received")
        hitReceived.assertForOverFulfill = false
        // Receive the hit but never answer; only the drop may resolve the hold.
        server.onEnvelope = { envelope in
            if case .breakpoint_hit = envelope.message { hitReceived.fulfill() }
        }

        let client = WailoClient(
            hello: Hello(device_name: "test", app_id: "com.test", platform: "ios"),
            url: loopbackURL(port),
            reconnectDelay: 0.2
        )
        client.start()
        defer { client.stopAndWaitForTeardown() }

        let resolved = expectation(description: "hold resolved by disconnect")
        var decided: WailoRequestDecision?
        client.pauseRequest(
            ruleId: "bp",
            request: HttpRequest(method: "GET", url: "https://bp.test/x", body: Data(), body_size: 0, body_truncated: false)
        ) { decision in
            decided = decision
            resolved.fulfill()
        }
        // Only cut the link once the hold is registered (its hit has reached the server).
        wait(for: [hitReceived], timeout: 10)
        server.stop()

        wait(for: [resolved], timeout: 10)
        guard case .proceed(nil) = decided else {
            XCTFail("expected fail-open proceed(nil), got \(String(describing: decided))")
            return
        }
    }

    /// The lazy body-fetch RPC (ADR-0019): a matched request asks the desktop for the body over the
    /// socket, and the desktop's BodyResponse (correlated by id) resolves the fetch. Drives the real
    /// `fetchBody` wire path end to end.
    func testFetchBodyRoundTripsOverSocket() throws {
        let server = LoopbackWebSocketServer()
        let port = try server.start()
        let helloReceived = expectation(description: "hello")
        helloReceived.assertForOverFulfill = false
        // Answer any body request with a fixed mapped response, echoing its correlation id.
        server.onEnvelope = { [weak server] envelope in
            switch envelope.message {
            case .hello:
                helloReceived.fulfill()
            case let .body_request(request):
                let response = Envelope {
                    $0.message = .body_response(BodyResponse(
                        correlation_id: request.correlation_id,
                        found: true,
                        code: 201,
                        body: Data("mapped!".utf8)
                    ) {
                        $0.headers = [Header(name: "Content-Type", value: "text/plain")]
                    })
                }
                if let data = try? ProtoEncoder().encode(response) { server?.push(data) }
            default:
                break
            }
        }

        let client = WailoClient(
            hello: Hello(device_name: "test", app_id: "com.test", platform: "ios"),
            url: loopbackURL(port)
        )
        client.start()
        defer { client.stopAndWaitForTeardown() }
        wait(for: [helloReceived], timeout: 10)

        let fetched = expectation(description: "body fetched")
        var mapped: WailoMappedResponse?
        client.fetchBody(ruleId: "r1", url: "https://x.test/y", method: "GET") { result in
            mapped = result
            fetched.fulfill()
        }
        wait(for: [fetched], timeout: 10)

        XCTAssertEqual(mapped?.code, 201)
        XCTAssertEqual(mapped.map { String(decoding: $0.body, as: UTF8.self) }, "mapped!")
        server.stop()
    }

    /// Fail-open: with no socket, a body fetch must resolve nil immediately (the interceptor then hits
    /// the real network) rather than hang until its timeout.
    func testFetchBodyFailsOpenWhenNotConnected() {
        let client = WailoClient(
            hello: Hello(device_name: "test", app_id: "com.test", platform: "ios"),
            url: loopbackURL(1)
        )
        // Never started: there is no task, so the fetch has no authority to ask.
        let fetched = expectation(description: "fetch resolved")
        var mapped: WailoMappedResponse? = WailoMappedResponse(code: 0, headers: [], body: Data())
        client.fetchBody(ruleId: "r1", url: "https://x.test/y", method: "GET") { result in
            mapped = result
            fetched.fulfill()
        }
        wait(for: [fetched], timeout: 5)
        XCTAssertNil(mapped, "a fetch with no connection must fail open (nil), not hang")
    }

    /// A live tap keeps no backlog. The desktop isn't up when the client starts, so an exchange
    /// captured while down must be dropped (never buffered for replay); but the connect loop must
    /// still recover the link so an exchange captured *after* the reconnect is delivered. This is the
    /// case the old buffering behavior replayed on reconnect (the "stale traffic after a restart" bug).
    func testDropsWhileDownButStreamsAfterReconnect() throws {
        let port = try reserveEphemeralPort()

        let client = WailoClient(
            hello: Hello(device_name: "test", app_id: "com.test", platform: "ios"),
            url: loopbackURL(port),
            reconnectDelay: 0.2
        )
        client.start()
        // Captured while nothing is listening → dropped, not buffered.
        client.onExchange(HttpExchange(id: "early", started_at_epoch_ms: 1, duration_ms: 2, error: "", edited: false))

        // Let a few connect attempts fail before the desktop shows up.
        Thread.sleep(forTimeInterval: 0.6)

        let server = LoopbackWebSocketServer()
        let helloReceived = expectation(description: "hello after late start")
        helloReceived.assertForOverFulfill = false
        let liveReceived = expectation(description: "post-reconnect exchange delivered")
        liveReceived.assertForOverFulfill = false
        // The first exchange the server ever sees proves "early" was dropped, not replayed.
        var firstExchangeId: String?
        server.onEnvelope = { envelope in
            switch envelope.message {
            case .hello:
                helloReceived.fulfill()
            case let .exchange(exchange):
                if firstExchangeId == nil { firstExchangeId = exchange.id }
                if exchange.id == "live" { liveReceived.fulfill() }
            default:
                break
            }
        }
        try server.start(on: port)

        // Reconnect first (Hello proves the tap is live again), then capture a fresh exchange.
        wait(for: [helloReceived], timeout: 10)
        client.onExchange(HttpExchange(id: "live", started_at_epoch_ms: 3, duration_ms: 4, error: "", edited: false))
        wait(for: [liveReceived], timeout: 10)
        XCTAssertEqual(firstExchangeId, "live", "an exchange captured while disconnected must be dropped, not replayed")

        client.stopAndWaitForTeardown()
        server.stop()
    }

    /// The desktop goes away mid-session and comes back on the same port. The client must notice the
    /// drop, keep retrying, reconnect (re-sending Hello), and deliver a new exchange.
    func testReconnectsAfterServerDrops() throws {
        let port = try reserveEphemeralPort()

        let server1 = LoopbackWebSocketServer()
        let firstHello = expectation(description: "first hello")
        firstHello.assertForOverFulfill = false
        server1.onEnvelope = { envelope in
            if case .hello = envelope.message { firstHello.fulfill() }
        }
        try server1.start(on: port)

        let client = WailoClient(
            hello: Hello(device_name: "test", app_id: "com.test", platform: "ios"),
            url: loopbackURL(port),
            reconnectDelay: 0.2
        )
        client.start()
        wait(for: [firstHello], timeout: 10)

        // Desktop disappears.
        server1.stop()
        Thread.sleep(forTimeInterval: 0.4)

        // Desktop returns on the same port; the client should reconnect and resume streaming.
        let server2 = LoopbackWebSocketServer()
        let reconnectHello = expectation(description: "hello after reconnect")
        reconnectHello.assertForOverFulfill = false
        let postDropExchange = expectation(description: "exchange after reconnect")
        postDropExchange.assertForOverFulfill = false
        server2.onEnvelope = { envelope in
            switch envelope.message {
            case .hello:
                reconnectHello.fulfill()
            case let .exchange(exchange) where exchange.id == "after":
                postDropExchange.fulfill()
            default:
                break
            }
        }
        try server2.start(on: port)
        // Capture only after the reconnect Hello proves the tap is live again — a live tap drops
        // anything captured during the gap between attempts.
        wait(for: [reconnectHello], timeout: 10)
        client.onExchange(HttpExchange(id: "after", started_at_epoch_ms: 3, duration_ms: 4, error: "", edited: false))
        wait(for: [postDropExchange], timeout: 10)
        client.stopAndWaitForTeardown()
        server2.stop()
    }

    func testMissingPongPublishesDisconnectAndRedials() throws {
        let server = LoopbackWebSocketServer(autoReplyPing: false)
        let port = try server.start()
        let disconnected = expectation(description: "connection status clears after pong timeout")
        disconnected.assertForOverFulfill = false
        let redialled = expectation(description: "client retries after pong timeout")
        redialled.assertForOverFulfill = false
        let lock = NSLock()
        var wasConnected = false
        var reportedDisconnect = false
        var dialCount = 0

        let client = WailoClient(
            hello: Hello(device_name: "test", app_id: "com.test", platform: "ios"),
            url: loopbackURL(port),
            reconnectDelay: 0.05,
            pingInterval: 0.05,
            pingTimeout: 0.1
        )
        client.onConnectionChange = { connected in
            lock.lock()
            defer { lock.unlock() }
            if connected {
                wasConnected = true
            } else if wasConnected, !reportedDisconnect {
                reportedDisconnect = true
                disconnected.fulfill()
            }
        }
        client.onDialling = {
            lock.lock()
            defer { lock.unlock() }
            dialCount += 1
            if dialCount == 2 { redialled.fulfill() }
        }
        client.start()

        wait(for: [disconnected, redialled], timeout: 5)
        client.stopAndWaitForTeardown()
        server.stop()
    }
}
