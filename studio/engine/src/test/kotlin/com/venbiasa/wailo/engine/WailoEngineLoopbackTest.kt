package com.venbiasa.wailo.engine

import com.venbiasa.wailo.protocol.BodyRequest
import com.venbiasa.wailo.protocol.BodyResponse
import com.venbiasa.wailo.protocol.CaptureFilter
import com.venbiasa.wailo.protocol.CaptureFilterAck
import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.protocol.MapLocalRule
import com.venbiasa.wailo.protocol.RuleAck
import com.venbiasa.wailo.protocol.RuleSet
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Framed Envelopes from a real WebSocket client are decoded by the engine and published on its
 * StateFlow — the server half of the M2 wire path (client half lives in sdk-android). Also covers the
 * reverse control channel (Map Local): versioned rule pushes + acks and the body-fetch RPC (ADR-0019). */
class WailoEngineLoopbackTest {

    // Each test rebinds a fresh engine, so a fixed port would race the previous one's teardown.
    private val port = ServerSocket(0).use { it.localPort }
    // Small ack-retry so the anti-entropy re-push is observable quickly in tests.
    private val engine = WailoEngine(port = port, ackRetryMs = 300)
    private val client = HttpClient(CIO) { install(WebSockets) }

    @AfterTest
    fun tearDown() {
        client.close()
        engine.bodyProvider = null
        engine.stop()
    }

    @Test
    fun framedExchangeReachesTheStateFlow() = runBlocking {
        engine.start()
        client.webSocket(host = "localhost", port = port, path = "/") {
            send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
            send(Frame.Binary(true, Envelope(exchange = EXCHANGE).encode()))
            // Keep the socket open briefly so the server drains both frames before close.
            delay(500)
        }

        val received = awaitFirstRow(timeoutMs = 5_000)
        assertEquals(1, received.size)
        val row = received.first()
        assertEquals("com.venbiasa.test", row.appId)
        assertEquals("pixel-test", row.deviceName)
        assertEquals("https://example.com/ping", row.exchange.request?.url)
        assertEquals(200, row.exchange.response?.code)
    }

    @Test
    fun connectedClientReceivesRuleSnapshotWithEpoch() = runBlocking {
        engine.start()
        // Rules set before the client connects must be delivered on connect (the reverse of the
        // capture path: this is the desktop -> device control channel).
        engine.updateRules(listOf(RULE))

        val received = withTimeoutOrNull(5_000) {
            var snapshot: RuleSet? = null
            client.webSocket(host = "localhost", port = port, path = "/") {
                send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    Envelope.ADAPTER.decode(frame.readBytes()).rule_set?.let {
                        snapshot = it
                        return@webSocket
                    }
                }
            }
            snapshot
        }

        assertNotNull(received)
        assertEquals(1, received.rules.size)
        assertEquals("https://example.com/*", received.rules.first().url_pattern)
        // A snapshot is versioned so the device can ack it; every update stamps a fresh, non-zero epoch.
        assertTrue(received.epoch > 0)
    }

    @Test
    fun unackedSnapshotIsRepushed() = runBlocking {
        engine.start()
        engine.updateRules(listOf(RULE))

        // Never ack: the engine must keep re-pushing the snapshot (anti-entropy), so we see it more
        // than once — the initial push plus at least one reconcile.
        var pushes = 0
        client.webSocket(host = "localhost", port = port, path = "/") {
            send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
            withTimeoutOrNull(1_500) {
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    Envelope.ADAPTER.decode(frame.readBytes()).rule_set?.let { pushes++ }
                }
            }
        }
        assertTrue(pushes >= 2, "an unacked snapshot must be re-pushed; saw $pushes push(es)")
    }

    @Test
    fun ackedSnapshotIsNotRepushed() = runBlocking {
        engine.start()
        engine.updateRules(listOf(RULE))

        // Ack the first snapshot; the engine must then stop re-pushing, so no further rule_set arrives.
        var extraAfterAck = 0
        client.webSocket(host = "localhost", port = port, path = "/") {
            send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
            var acked = false
            withTimeoutOrNull(1_500) {
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    Envelope.ADAPTER.decode(frame.readBytes()).rule_set?.let { snapshot ->
                        if (acked) {
                            extraAfterAck++
                        } else {
                            send(Frame.Binary(true, Envelope(rule_ack = RuleAck(epoch = snapshot.epoch)).encode()))
                            acked = true
                        }
                    }
                }
            }
        }
        assertEquals(0, extraAfterAck, "an acked snapshot must not be re-pushed")
    }

    @Test
    fun connectedClientReceivesCaptureFilterSnapshotWithEpoch() = runBlocking {
        engine.start()
        // The capture filter follows the same connect-time delivery + versioning as the rule set.
        engine.updateCaptureFilter(
            allowlistEnabled = true,
            allowPatterns = listOf("example.com", "*.api.test"),
            blocklistEnabled = false,
            blockPatterns = emptyList(),
        )

        val received = withTimeoutOrNull(5_000) {
            var snapshot: CaptureFilter? = null
            client.webSocket(host = "localhost", port = port, path = "/") {
                send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    Envelope.ADAPTER.decode(frame.readBytes()).capture_filter?.let {
                        snapshot = it
                        return@webSocket
                    }
                }
            }
            snapshot
        }

        assertNotNull(received)
        assertTrue(received.allowlist_enabled)
        assertEquals(listOf("example.com", "*.api.test"), received.allow_patterns)
        assertFalse(received.blocklist_enabled)
        assertTrue(received.epoch > 0)
    }

    @Test
    fun unackedCaptureFilterIsRepushed() = runBlocking {
        engine.start()
        engine.updateCaptureFilter(
            allowlistEnabled = true,
            allowPatterns = listOf("example.com"),
            blocklistEnabled = false,
            blockPatterns = emptyList(),
        )

        // Never ack: the engine must keep re-pushing the filter (anti-entropy), like the rule set.
        var pushes = 0
        client.webSocket(host = "localhost", port = port, path = "/") {
            send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
            withTimeoutOrNull(1_500) {
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    Envelope.ADAPTER.decode(frame.readBytes()).capture_filter?.let { pushes++ }
                }
            }
        }
        assertTrue(pushes >= 2, "an unacked capture filter must be re-pushed; saw $pushes push(es)")
    }

    @Test
    fun ackedCaptureFilterIsNotRepushed() = runBlocking {
        engine.start()
        engine.updateCaptureFilter(
            allowlistEnabled = true,
            allowPatterns = listOf("example.com"),
            blocklistEnabled = false,
            blockPatterns = emptyList(),
        )

        // Ack the first capture-filter snapshot; the engine must then stop re-pushing it.
        var extraAfterAck = 0
        client.webSocket(host = "localhost", port = port, path = "/") {
            send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
            var acked = false
            withTimeoutOrNull(1_500) {
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    Envelope.ADAPTER.decode(frame.readBytes()).capture_filter?.let { snapshot ->
                        if (acked) {
                            extraAfterAck++
                        } else {
                            send(Frame.Binary(true, Envelope(capture_filter_ack = CaptureFilterAck(epoch = snapshot.epoch)).encode()))
                            acked = true
                        }
                    }
                }
            }
        }
        assertEquals(0, extraAfterAck, "an acked capture filter must not be re-pushed")
    }

    @Test
    fun bodyRequestIsAnsweredByProvider() = runBlocking {
        engine.start()
        engine.bodyProvider = MapLocalBodyProvider { ruleId, _, _ ->
            if (ruleId == "r1") {
                ServedBody(
                    code = 201,
                    headers = listOf(Header(name = "Content-Type", value_ = "text/plain")),
                    body = "mapped!".toByteArray(),
                    delayMillis = 450,
                )
            } else {
                null
            }
        }

        val response = fetchBodyResponse(BodyRequest(correlation_id = "c1", rule_id = "r1", url = "https://x/y", method = "GET"))

        assertNotNull(response)
        assertEquals("c1", response.correlation_id)
        assertTrue(response.found)
        assertEquals(201, response.code)
        assertEquals("mapped!", response.body.utf8())
        assertEquals(450, response.delay_ms)
        assertEquals("Content-Type", response.headers.first().name)
    }

    @Test
    fun bodyRequestWithoutProviderIsNotFound() = runBlocking {
        engine.start()
        engine.bodyProvider = null

        val response = fetchBodyResponse(BodyRequest(correlation_id = "c2", rule_id = "whatever", url = "https://x/y", method = "GET"))

        assertNotNull(response)
        assertEquals("c2", response.correlation_id)
        assertFalse(response.found)
    }

    // Opens a connection, sends [request], and returns the first BodyResponse the engine sends back.
    private suspend fun fetchBodyResponse(request: BodyRequest): BodyResponse? = withTimeoutOrNull(5_000) {
        var out: BodyResponse? = null
        client.webSocket(host = "localhost", port = port, path = "/") {
            send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
            send(Frame.Binary(true, Envelope(body_request = request).encode()))
            for (frame in incoming) {
                if (frame !is Frame.Binary) continue
                Envelope.ADAPTER.decode(frame.readBytes()).body_response?.let {
                    out = it
                    return@webSocket
                }
            }
        }
        out
    }

    private fun awaitFirstRow(timeoutMs: Long): List<CapturedExchange> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = engine.exchanges.value
            if (current.isNotEmpty()) return current
            Thread.sleep(50)
        }
        return engine.exchanges.value
    }

    private companion object {
        val HELLO = Hello(device_name = "pixel-test", app_id = "com.venbiasa.test", platform = "jvm")
        val EXCHANGE = HttpExchange(
            id = "e1",
            started_at_epoch_ms = 1_000,
            duration_ms = 7,
            request = HttpRequest(method = "GET", url = "https://example.com/ping"),
            response = HttpResponse(code = 200, message = "OK"),
        )
        val RULE = MapLocalRule(
            id = "r1",
            enabled = true,
            url_pattern = "https://example.com/*",
        )
    }
}
