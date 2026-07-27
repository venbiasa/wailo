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
import com.venbiasa.wailo.protocol.MapLocalRule
import com.venbiasa.wailo.protocol.RuleSet
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
                    for (frame in incoming) {
                        if (frame !is Frame.Binary) continue
                        val env = Envelope.ADAPTER.decode(frame.readBytes())
                        if (env.rule_ack != null || env.capture_filter_ack != null || env.breakpoint_rules_ack != null) {
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
                while (seen.size < 3) {
                    val env = acks.receive()
                    env.rule_ack?.let { seen["rule"] = it.epoch }
                    env.capture_filter_ack?.let { seen["filter"] = it.epoch }
                    env.breakpoint_rules_ack?.let { seen["bp"] = it.epoch }
                }
            }
            assertEquals(1L, seen["rule"])
            assertEquals(2L, seen["filter"])
            assertEquals(3L, seen["bp"])
            // Each store was updated on receipt, before the ack was sent, so it is visible now.
            assertEquals("r1", WailoRuleStore.match("https://api.example.com/x", "GET")?.id)
            assertFalse(WailoCaptureFilterStore.shouldCapture("ads.com"))
            assertEquals("b1", WailoBreakpointStore.match("https://anything", "GET")?.ruleId)
        } finally {
            client.stop()
            server.stop(0, 0)
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
            val mapped = withContext(Dispatchers.IO) {
                client.fetchBody(ruleId = "r1", url = "https://api.example.com/x", method = "GET")
            }
            assertEquals(200, mapped?.code)
            assertEquals("mocked", mapped?.body?.utf8())
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
}
