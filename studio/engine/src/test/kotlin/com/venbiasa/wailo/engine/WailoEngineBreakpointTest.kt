package com.venbiasa.wailo.engine

import com.venbiasa.wailo.protocol.BreakpointAction
import com.venbiasa.wailo.protocol.BreakpointDecision
import com.venbiasa.wailo.protocol.BreakpointHit
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.BreakpointRule
import com.venbiasa.wailo.protocol.BreakpointRules
import com.venbiasa.wailo.protocol.BreakpointRulesAck
import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The breakpoints control channel (ADR-0027): versioned rule pushes + acks (anti-entropy, like Map
 * Local), a device's BreakpointHit surfacing on [WailoEngine.pausedExchanges], the resume/abort decision
 * routing back to the origin session, and a disconnect dropping that session's holds (fail-open).
 */
class WailoEngineBreakpointTest {

    private val port = 18988
    private val engine = WailoEngine(port = port, ackRetryMs = 300)
    private val client = HttpClient(CIO) { install(WebSockets) }

    @AfterTest
    fun tearDown() {
        client.close()
        engine.stop()
    }

    @Test
    fun connectedClientReceivesBreakpointSnapshotWithEpoch() = runBlocking {
        engine.start()
        engine.updateBreakpointRules(listOf(BREAKPOINT_RULE))

        val received = withTimeoutOrNull(5_000) {
            var snapshot: BreakpointRules? = null
            client.webSocket(host = "localhost", port = port, path = "/") {
                send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    Envelope.ADAPTER.decode(frame.readBytes()).breakpoint_rules?.let {
                        snapshot = it
                        return@webSocket
                    }
                }
            }
            snapshot
        }

        assertNotNull(received)
        assertEquals(1, received.rules.size)
        val rule = received.rules.first()
        assertEquals("https://example.com/*", rule.url_pattern)
        assertTrue(rule.on_request)
        assertTrue(rule.on_response)
        assertTrue(received.epoch > 0)
    }

    @Test
    fun unackedBreakpointRulesAreRepushed() = runBlocking {
        engine.start()
        engine.updateBreakpointRules(listOf(BREAKPOINT_RULE))

        var pushes = 0
        client.webSocket(host = "localhost", port = port, path = "/") {
            send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
            withTimeoutOrNull(1_500) {
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    Envelope.ADAPTER.decode(frame.readBytes()).breakpoint_rules?.let { pushes++ }
                }
            }
        }
        assertTrue(pushes >= 2, "an unacked breakpoint snapshot must be re-pushed; saw $pushes push(es)")
    }

    @Test
    fun ackedBreakpointRulesAreNotRepushed() = runBlocking {
        engine.start()
        engine.updateBreakpointRules(listOf(BREAKPOINT_RULE))

        var extraAfterAck = 0
        client.webSocket(host = "localhost", port = port, path = "/") {
            send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
            var acked = false
            withTimeoutOrNull(1_500) {
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    Envelope.ADAPTER.decode(frame.readBytes()).breakpoint_rules?.let { snapshot ->
                        if (acked) {
                            extraAfterAck++
                        } else {
                            send(Frame.Binary(true, Envelope(breakpoint_rules_ack = BreakpointRulesAck(epoch = snapshot.epoch)).encode()))
                            acked = true
                        }
                    }
                }
            }
        }
        assertEquals(0, extraAfterAck, "an acked breakpoint snapshot must not be re-pushed")
    }

    @Test
    fun breakpointHitAppearsInPausedExchanges() = runBlocking {
        engine.start()
        var paused: PausedExchange? = null
        client.webSocket(host = "localhost", port = port, path = "/") {
            send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
            send(
                Frame.Binary(
                    true,
                    Envelope(
                        breakpoint_hit = BreakpointHit(
                            correlation_id = "c1",
                            rule_id = "bp1",
                            phase = BreakpointPhase.BREAKPOINT_PHASE_RESPONSE,
                            request = HttpRequest(method = "GET", url = "https://example.com/ping"),
                            response = HttpResponse(code = 500, message = "Server Error"),
                        ),
                    ).encode(),
                ),
            )
            // Capture the paused entry *before* the connection closes — a disconnect drops it.
            awaitPaused("c1")
            paused = engine.pausedExchanges.value.firstOrNull { it.correlationId == "c1" }
        }

        val captured = paused
        assertNotNull(captured)
        assertEquals(BreakpointPhase.BREAKPOINT_PHASE_RESPONSE, captured.phase)
        assertEquals("pixel-test", captured.deviceName)
        assertEquals("https://example.com/ping", captured.request?.url)
        assertEquals(500, captured.response?.code)
    }

    @Test
    fun resumeBreakpointRoutesEditedDecisionToOriginSession() = runBlocking {
        engine.start()
        val decision = withTimeoutOrNull(5_000) {
            var out: BreakpointDecision? = null
            client.webSocket(host = "localhost", port = port, path = "/") {
                send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
                send(
                    Frame.Binary(
                        true,
                        Envelope(
                            breakpoint_hit = BreakpointHit(
                                correlation_id = "c1",
                                rule_id = "bp1",
                                phase = BreakpointPhase.BREAKPOINT_PHASE_REQUEST,
                                request = HttpRequest(method = "GET", url = "https://example.com/ping"),
                            ),
                        ).encode(),
                    ),
                )
                awaitPaused("c1")
                engine.resumeBreakpoint(
                    "c1",
                    editedRequest = HttpRequest(method = "POST", url = "https://example.com/edited"),
                )
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    Envelope.ADAPTER.decode(frame.readBytes()).breakpoint_decision?.let {
                        out = it
                        return@webSocket
                    }
                }
            }
            out
        }

        assertNotNull(decision)
        assertEquals("c1", decision.correlation_id)
        assertEquals(BreakpointAction.BREAKPOINT_ACTION_PROCEED, decision.action)
        assertEquals("POST", decision.edited_request?.method)
        assertEquals("https://example.com/edited", decision.edited_request?.url)
        assertTrue(engine.pausedExchanges.value.isEmpty(), "a resumed hold must leave the paused list")
    }

    @Test
    fun abortBreakpointRoutesAbortDecision() = runBlocking {
        engine.start()
        val decision = withTimeoutOrNull(5_000) {
            var out: BreakpointDecision? = null
            client.webSocket(host = "localhost", port = port, path = "/") {
                send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
                send(
                    Frame.Binary(
                        true,
                        Envelope(
                            breakpoint_hit = BreakpointHit(
                                correlation_id = "c2",
                                rule_id = "bp1",
                                phase = BreakpointPhase.BREAKPOINT_PHASE_REQUEST,
                                request = HttpRequest(method = "GET", url = "https://example.com/ping"),
                            ),
                        ).encode(),
                    ),
                )
                awaitPaused("c2")
                engine.abortBreakpoint("c2")
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    Envelope.ADAPTER.decode(frame.readBytes()).breakpoint_decision?.let {
                        out = it
                        return@webSocket
                    }
                }
            }
            out
        }

        assertNotNull(decision)
        assertEquals("c2", decision.correlation_id)
        assertEquals(BreakpointAction.BREAKPOINT_ACTION_ABORT, decision.action)
    }

    @Test
    fun disconnectDropsPausedExchange() = runBlocking {
        engine.start()
        client.webSocket(host = "localhost", port = port, path = "/") {
            send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
            send(
                Frame.Binary(
                    true,
                    Envelope(
                        breakpoint_hit = BreakpointHit(
                            correlation_id = "c3",
                            rule_id = "bp1",
                            phase = BreakpointPhase.BREAKPOINT_PHASE_REQUEST,
                            request = HttpRequest(method = "GET", url = "https://example.com/ping"),
                        ),
                    ).encode(),
                ),
            )
            awaitPaused("c3")
            assertTrue(engine.pausedExchanges.value.any { it.correlationId == "c3" })
        }

        // Once the origin session closes, the engine must drop its holds (the device fails them open).
        val dropped = withTimeoutOrNull(3_000) {
            while (engine.pausedExchanges.value.any { it.correlationId == "c3" }) delay(20)
            true
        }
        assertEquals(true, dropped, "a disconnecting session's paused exchanges must be dropped")
    }

    private suspend fun awaitPaused(correlationId: String) {
        withTimeoutOrNull(3_000) {
            while (engine.pausedExchanges.value.none { it.correlationId == correlationId }) delay(20)
        }
    }

    private companion object {
        val HELLO = Hello(device_name = "pixel-test", app_id = "com.venbiasa.test", platform = "jvm")
        val BREAKPOINT_RULE = BreakpointRule(
            id = "bp1",
            enabled = true,
            url_pattern = "https://example.com/*",
            on_request = true,
            on_response = true,
        )
    }
}
