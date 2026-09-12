package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.BodyResponse
import com.venbiasa.wailo.protocol.BreakpointAction
import com.venbiasa.wailo.protocol.BreakpointDecision
import com.venbiasa.wailo.protocol.BreakpointRule
import com.venbiasa.wailo.protocol.BreakpointRules
import com.venbiasa.wailo.protocol.CaptureFilter
import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.protocol.MapLocalRule
import com.venbiasa.wailo.protocol.RuleSet
import com.venbiasa.wailo.protocol.ScriptPhase
import com.venbiasa.wailo.protocol.ScriptRule
import com.venbiasa.wailo.protocol.ScriptRuleSet
import com.venbiasa.wailo.protocol.ScriptTransformResult
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WailoClient's device-bound control channel against a throwaway WebSocket server (loopback, like
 * the iOS `WailoClientLoopbackTests`): it applies + acks pushed snapshots (anti-entropy), round-trips a
 * Map Local body fetch and a breakpoint hold, and fails open when the desktop is (or goes) away. Uses
 * ktor-server purely as a test fixture — the SDK never depends on engine (invariant #3).
 */
class WailoClientControlTest {

    @After
    fun tearDown() {
        WailoRuleStore.replace(emptyList())
        WailoBreakpointStore.replace(emptyList())
        WailoScriptStore.replace(emptyList())
        WailoCaptureFilterStore.reset()
    }

    private fun hello() = Hello(device_name = "test", app_id = "com.test", platform = "android")

    @Test
    fun appliesAndAcksPushedSnapshots() = runBlocking {
        val acks = Channel<Envelope>(Channel.UNLIMITED)
        val port = 18990
        val server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    send(Frame.Binary(true, Envelope(rule_set = RuleSet(rules = listOf(MapLocalRule(id = "r1", enabled = true, url_pattern = "https://api.example.com/*")), epoch = 1L)).encode()))
                    send(Frame.Binary(true, Envelope(capture_filter = CaptureFilter(blocklist_enabled = true, block_patterns = listOf("ads.com"), epoch = 2L)).encode()))
                    send(Frame.Binary(true, Envelope(breakpoint_rules = BreakpointRules(rules = listOf(BreakpointRule(id = "b1", enabled = true, url_pattern = "*", on_request = true)), epoch = 3L)).encode()))
                    send(Frame.Binary(true, Envelope(script_rule_set = ScriptRuleSet(rules = listOf(ScriptRule(id = "s1", enabled = true, url_pattern = "*", on_response = true)), epoch = 4L)).encode()))
                    for (frame in incoming) {
                        if (frame !is Frame.Binary) continue
                        val env = Envelope.ADAPTER.decode(frame.readBytes())
                        if (
                            env.rule_ack != null ||
                            env.capture_filter_ack != null ||
                            env.breakpoint_rules_ack != null ||
                            env.script_rule_set_ack != null
                        ) {
                            acks.trySend(env)
                        }
                    }
                }
            }
        }.also { it.start(wait = false) }

        val client = WailoClient(hello = hello(), host = "localhost", port = port).also { it.start() }
        try {
            val seen = mutableMapOf<String, Long>()
            withTimeout(10_000) {
                while (seen.size < 4) {
                    val env = acks.receive()
                    env.rule_ack?.let { seen["rule"] = it.epoch }
                    env.capture_filter_ack?.let { seen["filter"] = it.epoch }
                    env.breakpoint_rules_ack?.let { seen["bp"] = it.epoch }
                    env.script_rule_set_ack?.let { seen["script"] = it.epoch }
                }
            }
            assertEquals(1L, seen["rule"])
            assertEquals(2L, seen["filter"])
            assertEquals(3L, seen["bp"])
            assertEquals(4L, seen["script"])
            // Each store was updated on receipt, before the ack was sent, so it is visible now.
            assertEquals("r1", WailoRuleStore.match("https://api.example.com/x", "GET")?.id)
            assertFalse(WailoCaptureFilterStore.shouldCapture("ads.com"))
            assertEquals("b1", WailoBreakpointStore.match("https://anything", "GET")?.ruleId)
            assertTrue(WailoScriptStore.matches("https://anything", "GET", ScriptPhase.SCRIPT_PHASE_RESPONSE))
        } finally {
            client.stop()
            server.stop(0, 0)
        }
    }

    @Test
    fun stoppingReplacedClientCannotClearActiveSnapshots() = runBlocking {
        val retiredServer = snapshotServer(port = 18995, id = "retired")
        val activeServer = snapshotServer(port = 18996, id = "active")
        val retired = WailoClient(hello = hello(), host = "localhost", port = 18995).also { it.start() }
        var active: WailoClient? = null
        try {
            awaitSnapshots("retired")
            val replacement = WailoClient(hello = hello(), host = "localhost", port = 18996).also { it.start() }
            active = replacement
            awaitSnapshots("active")

            retired.stop()
            delay(250)

            assertEquals("active", WailoRuleStore.match("https://active.test/x", "GET")?.id)
            assertTrue(WailoCaptureFilterStore.shouldCapture("active.test"))
            assertFalse(WailoCaptureFilterStore.shouldCapture("other.test"))
            assertEquals("active", WailoBreakpointStore.match("https://active.test/x", "GET")?.ruleId)
            assertTrue(WailoScriptStore.matches("https://active.test/x", "GET", ScriptPhase.SCRIPT_PHASE_REQUEST))

            replacement.stop()
            active = null
            assertNull(WailoRuleStore.match("https://active.test/x", "GET"))
            assertTrue(WailoCaptureFilterStore.shouldCapture("other.test"))
            assertNull(WailoBreakpointStore.match("https://active.test/x", "GET"))
            assertFalse(WailoScriptStore.matches("https://active.test/x", "GET", ScriptPhase.SCRIPT_PHASE_REQUEST))
        } finally {
            active?.stop()
            retired.stop()
            activeServer.stop(0, 0)
            retiredServer.stop(0, 0)
        }
    }

    @Test
    fun bodyFetchRoundTrips() = runBlocking {
        val helloSeen = CompletableDeferred<Unit>()
        val port = 18991
        val server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    for (frame in incoming) {
                        if (frame !is Frame.Binary) continue
                        val env = Envelope.ADAPTER.decode(frame.readBytes())
                        env.hello?.let { helloSeen.complete(Unit) }
                        env.body_request?.let { req ->
                            send(
                                Frame.Binary(
                                    true,
                                    Envelope(
                                        body_response = BodyResponse(
                                            correlation_id = req.correlation_id,
                                            found = true,
                                            code = 200,
                                            headers = listOf(Header("Content-Type", "application/json")),
                                            body = "mocked".encodeUtf8(),
                                            delay_ms = 150,
                                        ),
                                    ).encode(),
                                ),
                            )
                        }
                    }
                }
            }
        }.also { it.start(wait = false) }

        val client = WailoClient(hello = hello(), host = "localhost", port = port).also { it.start() }
        try {
            withTimeout(10_000) { helloSeen.await() }
            val started = System.nanoTime()
            val mapped = withContext(Dispatchers.IO) {
                client.fetchBody(ruleId = "r1", url = "https://api.example.com/x", method = "GET")
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertEquals(200, mapped?.code)
            assertEquals("mocked", mapped?.body?.utf8())
            assertTrue("Body response returned after ${elapsedMillis}ms", elapsedMillis >= 120)
        } finally {
            client.stop()
            server.stop(0, 0)
        }
    }

    @Test
    fun breakpointHoldRoundTrips() = runBlocking {
        val helloSeen = CompletableDeferred<Unit>()
        val port = 18992
        val server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    for (frame in incoming) {
                        if (frame !is Frame.Binary) continue
                        val env = Envelope.ADAPTER.decode(frame.readBytes())
                        env.hello?.let { helloSeen.complete(Unit) }
                        env.breakpoint_hit?.let { hit ->
                            send(
                                Frame.Binary(
                                    true,
                                    Envelope(
                                        breakpoint_decision = BreakpointDecision(
                                            correlation_id = hit.correlation_id,
                                            action = BreakpointAction.BREAKPOINT_ACTION_PROCEED,
                                            edited_request = HttpRequest(method = "PUT", url = "https://api.example.com/edited"),
                                        ),
                                    ).encode(),
                                ),
                            )
                        }
                    }
                }
            }
        }.also { it.start(wait = false) }

        val client = WailoClient(hello = hello(), host = "localhost", port = port).also { it.start() }
        try {
            withTimeout(10_000) { helloSeen.await() }
            val decision = withContext(Dispatchers.IO) {
                client.pauseRequest(ruleId = "b1", request = HttpRequest(method = "GET", url = "https://api.example.com/x"))
            }
            val proceed = decision as WailoRequestDecision.Proceed
            assertEquals("PUT", proceed.edited?.method)
            assertEquals("https://api.example.com/edited", proceed.edited?.url)
        } finally {
            client.stop()
            server.stop(0, 0)
        }
    }

    @Test
    fun responseBreakpointAppliesDecisionDelay() = runBlocking {
        val helloSeen = CompletableDeferred<Unit>()
        val port = 18997
        val server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    for (frame in incoming) {
                        if (frame !is Frame.Binary) continue
                        val env = Envelope.ADAPTER.decode(frame.readBytes())
                        env.hello?.let { helloSeen.complete(Unit) }
                        env.breakpoint_hit?.let { hit ->
                            send(
                                Frame.Binary(
                                    true,
                                    Envelope(
                                        breakpoint_decision = BreakpointDecision(
                                            correlation_id = hit.correlation_id,
                                            action = BreakpointAction.BREAKPOINT_ACTION_PROCEED,
                                            edited_response = HttpResponse(code = 202),
                                            delay_ms = 150,
                                        ),
                                    ).encode(),
                                ),
                            )
                        }
                    }
                }
            }
        }.also { it.start(wait = false) }

        val client = WailoClient(hello = hello(), host = "localhost", port = port).also { it.start() }
        try {
            withTimeout(10_000) { helloSeen.await() }
            val started = System.nanoTime()
            val decision = withContext(Dispatchers.IO) {
                client.pauseResponse(
                    ruleId = "b1",
                    request = HttpRequest(method = "GET", url = "https://api.example.com/x"),
                    response = HttpResponse(code = 500),
                )
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            val proceed = decision as WailoResponseDecision.Proceed
            assertEquals(202, proceed.edited?.code)
            assertTrue("Breakpoint response returned after ${elapsedMillis}ms", elapsedMillis >= 120)
        } finally {
            client.stop()
            server.stop(0, 0)
        }
    }

    @Test
    fun scriptTransformRoundTrips() = runBlocking {
        val helloSeen = CompletableDeferred<Unit>()
        val port = 18998
        val server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    for (frame in incoming) {
                        if (frame !is Frame.Binary) continue
                        val envelope = Envelope.ADAPTER.decode(frame.readBytes())
                        envelope.hello?.let { helloSeen.complete(Unit) }
                        envelope.script_transform_request?.let { transform ->
                            send(
                                Frame.Binary(
                                    true,
                                    Envelope(
                                        script_transform_result = ScriptTransformResult(
                                            correlation_id = transform.correlation_id,
                                            request = transform.request?.copy(method = "PATCH"),
                                        ),
                                    ).encode(),
                                ),
                            )
                        }
                    }
                }
            }
        }.also { it.start(wait = false) }

        val client = WailoClient(hello = hello(), host = "localhost", port = port).also { it.start() }
        try {
            withTimeout(10_000) { helloSeen.await() }
            val result = withContext(Dispatchers.IO) {
                client.transform(
                    phase = ScriptPhase.SCRIPT_PHASE_REQUEST,
                    request = HttpRequest(method = "GET", url = "https://example.com"),
                    requestBodyReplayable = true,
                )
            }
            assertEquals("PATCH", result?.request?.method)
        } finally {
            client.stop()
            server.stop(0, 0)
        }
    }

    @Test
    fun notConnectedFailsOpen() = runBlocking {
        // Nothing is listening on this port, so the client never connects.
        val client = WailoClient(hello = hello(), host = "localhost", port = 18994)
        try {
            val mapped = withContext(Dispatchers.IO) { client.fetchBody("r", "https://x", "GET") }
            assertNull(mapped)
            val decision = withContext(Dispatchers.IO) {
                client.pauseRequest("b", HttpRequest(method = "GET", url = "https://x"))
            }
            assertEquals(WailoRequestDecision.Proceed(null), decision)
        } finally {
            client.stop()
        }
    }

    @Test
    fun disconnectFailsOpenHeldBreakpoint() = runBlocking {
        val helloSeen = CompletableDeferred<Unit>()
        val port = 18993
        val server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    // Receive the hit but never answer it — the human "walks away", then the link drops.
                    for (frame in incoming) {
                        if (frame !is Frame.Binary) continue
                        Envelope.ADAPTER.decode(frame.readBytes()).hello?.let { helloSeen.complete(Unit) }
                    }
                }
            }
        }.also { it.start(wait = false) }

        val client = WailoClient(hello = hello(), host = "localhost", port = port).also { it.start() }
        try {
            withTimeout(10_000) { helloSeen.await() }
            val held = async(Dispatchers.IO) {
                client.pauseRequest("b", HttpRequest(method = "GET", url = "https://x"))
            }
            // Let the hit reach the server and the hold register, then force a disconnect.
            delay(500)
            server.stop(0, 0)
            val decision = withTimeout(10_000) { held.await() }
            assertEquals(WailoRequestDecision.Proceed(null), decision)
        } finally {
            client.stop()
            server.stop(0, 0)
        }
    }

    private fun snapshotServer(port: Int, id: String) = embeddedServer(CIO, port = port) {
        install(WebSockets)
        routing {
            webSocket("/") {
                send(
                    Frame.Binary(
                        true,
                        Envelope(
                            rule_set = RuleSet(
                                rules = listOf(
                                    MapLocalRule(
                                        id = id,
                                        enabled = true,
                                        url_pattern = "https://$id.test/*",
                                    ),
                                ),
                                epoch = 1L,
                            ),
                        ).encode(),
                    ),
                )
                send(
                    Frame.Binary(
                        true,
                        Envelope(
                            script_rule_set = ScriptRuleSet(
                                rules = listOf(
                                    ScriptRule(
                                        id = id,
                                        enabled = true,
                                        url_pattern = "https://$id.test/*",
                                        on_request = true,
                                    ),
                                ),
                                epoch = 1L,
                            ),
                        ).encode(),
                    ),
                )
                send(
                    Frame.Binary(
                        true,
                        Envelope(
                            capture_filter = CaptureFilter(
                                allowlist_enabled = true,
                                allow_patterns = listOf("$id.test"),
                                epoch = 1L,
                            ),
                        ).encode(),
                    ),
                )
                send(
                    Frame.Binary(
                        true,
                        Envelope(
                            breakpoint_rules = BreakpointRules(
                                rules = listOf(
                                    BreakpointRule(
                                        id = id,
                                        enabled = true,
                                        url_pattern = "https://$id.test/*",
                                        on_request = true,
                                    ),
                                ),
                                epoch = 1L,
                            ),
                        ).encode(),
                    ),
                )
                for (frame in incoming) {
                    if (frame is Frame.Binary) Envelope.ADAPTER.decode(frame.readBytes())
                }
            }
        }
    }.also { it.start(wait = false) }

    private suspend fun awaitSnapshots(id: String) {
        withTimeout(10_000) {
            while (
                WailoRuleStore.match("https://$id.test/x", "GET")?.id != id ||
                WailoBreakpointStore.match("https://$id.test/x", "GET")?.ruleId != id ||
                !WailoScriptStore.matches(
                    "https://$id.test/x",
                    "GET",
                    ScriptPhase.SCRIPT_PHASE_REQUEST,
                )
            ) {
                delay(10)
            }
        }
    }
}
