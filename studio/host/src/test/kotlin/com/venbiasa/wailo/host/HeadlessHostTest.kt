package com.venbiasa.wailo.host

import com.venbiasa.wailo.engine.DeviceConnection
import com.venbiasa.wailo.engine.DeviceTransport
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.protocol.BreakpointAction
import com.venbiasa.wailo.protocol.BreakpointHit
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class HeadlessHostTest {

    @Test
    fun fillAnswersAHoldThatArrivedBeforeTheSeedsDid() = runBlocking {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        val connection = FakeConnection()
        val serving = launch { engine.attach(connection) }
        try {
            connection.incoming.send(Envelope(hello = HELLO).encode())
            connection.incoming.send(
                Envelope(
                    breakpoint_hit = BreakpointHit(
                        correlation_id = "hold",
                        rule_id = "bp",
                        phase = BreakpointPhase.BREAKPOINT_PHASE_RESPONSE,
                        request = HttpRequest(method = "GET", url = "https://example.com/ping"),
                        response = HttpResponse(code = 500),
                    ),
                ).encode(),
            )
            withTimeout(2_000) {
                while (engine.pausedExchanges.value.none { it.correlationId == "hold" }) delay(10)
            }

            // Arming after the traffic paused is the ordinary way to reach for seeds, so Fill sweeps
            // what is already waiting rather than leaving it for a re-trigger (ADR-0044).
            host.upsertSeed(
                HostSeed(
                    id = "seed",
                    urlPattern = "https://example.com/*",
                    statusCode = 202,
                    body = "{}".toByteArray(),
                ),
            )
            host.fillSeeds()

            val decision = withTimeout(2_000) {
                while (true) {
                    val envelope = Envelope.ADAPTER.decode(connection.outgoing.receive())
                    envelope.breakpoint_decision?.let { return@withTimeout it }
                }
                error("unreachable")
            }
            assertEquals("hold", decision.correlation_id)
            assertEquals(BreakpointAction.BREAKPOINT_ACTION_PROCEED, decision.action)
            assertEquals(202, decision.edited_response?.code)
            assertEquals("{}", decision.edited_response?.body?.utf8())
            assertTrue(host.seedQueue.value.isEmpty())
            // Spending removes it from the run, never from the library it was armed out of.
            assertEquals("seed", host.seeds.value.single().id)
        } finally {
            connection.incoming.close()
            serving.join()
            host.stop()
        }
    }

    @Test
    fun registeredMapLocalRulePushesMetadataAndServesBody() = runBlocking {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        try {
            host.upsertMapLocalRule(
                HostMapLocalRule(
                    id = "fixture",
                    urlPattern = "https://api.example.com/*",
                    method = "GET",
                    statusCode = 201,
                    headers = listOf(Header(name = "Content-Type", value_ = "application/json")),
                    body = """{"ok":true}""".toByteArray(),
                ),
            )

            assertEquals("fixture", engine.rules.value.rules.single().id)
            val served = engine.bodyProvider?.serve("fixture", "https://api.example.com/items", "GET")
            assertNotNull(served)
            assertEquals(201, served.code)
            assertEquals("""{"ok":true}""", served.body.decodeToString())
            assertEquals(
                served.body.size.toString(),
                served.headers.single { it.name.equals("Content-Length", ignoreCase = true) }.value_,
            )
            assertNull(engine.bodyProvider?.serve("fixture", "https://other.example.com/items", "GET"))

            host.setMapLocalEnabled(false)
            assertTrue(engine.rules.value.rules.isEmpty())
            assertNull(engine.bodyProvider?.serve("fixture", "https://api.example.com/items", "GET"))
            host.setMapLocalEnabled(true)
            assertEquals("fixture", engine.rules.value.rules.single().id)

            assertTrue(host.removeMapLocalRule("fixture"))
            assertFalse(host.removeMapLocalRule("fixture"))
        } finally {
            host.stop()
        }
    }

    @Test
    fun registeredBreakpointRulesCanBeDisabledWithoutBeingDeleted() = runBlocking {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        try {
            host.upsertBreakpointRule(
                HostBreakpointRule(
                    id = "errors",
                    urlPattern = "https://api.example.com/*",
                    method = "POST",
                    onRequest = true,
                    onResponse = false,
                ),
            )

            val pushed = engine.breakpointRules.value.rules.single()
            assertEquals("errors", pushed.id)
            // The wire field stays repeated as a fold target, so one method is a one-element list (ADR-0087).
            assertEquals(listOf("POST"), pushed.methods)
            assertTrue(pushed.on_request)
            assertFalse(pushed.on_response)

            host.setBreakpointsEnabled(false)
            assertTrue(engine.breakpointRules.value.rules.isEmpty())
            assertEquals("errors", host.breakpointRules.value.single().id)

            host.setBreakpointsEnabled(true)
            assertEquals("errors", engine.breakpointRules.value.rules.single().id)
            assertTrue(host.removeBreakpointRule("errors"))
            assertFalse(host.removeBreakpointRule("errors"))
        } finally {
            host.stop()
        }
    }

    @Test
    fun fillArmsOnlyTheEnabledSeedsAndTheMasterDropsTheQueueItDeclines() = runBlocking {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        try {
            host.replaceSeeds(
                listOf(
                    HostSeed(id = "on", urlPattern = "https://example.com/a"),
                    HostSeed(id = "off", urlPattern = "https://example.com/b", enabled = false),
                ),
                enabled = true,
            )
            host.fillSeeds()
            assertEquals("on", host.seedQueue.value.single().id)

            // Unlike Map Local and breakpoints, whose masters only withhold the push, this one is
            // destructive by design: a run it declines is discarded rather than left armed.
            host.setSeedsEnabled(false)
            host.fillSeeds()
            assertTrue(host.seedQueue.value.isEmpty())
            assertEquals(2, host.seeds.value.size)
        } finally {
            host.stop()
        }
    }

    @Test
    fun editingTheLibraryReprojectsTheArmedQueue() = runBlocking {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        try {
            host.replaceSeeds(
                listOf(
                    HostSeed(id = "a", urlPattern = "https://example.com/a", statusCode = 200),
                    HostSeed(id = "b", urlPattern = "https://example.com/b"),
                ),
                enabled = true,
            )
            host.fillSeeds()

            host.upsertSeed(HostSeed(id = "a", urlPattern = "https://example.com/a", statusCode = 503))
            assertEquals(503, host.seedQueue.value.first { it.id == "a" }.statusCode)

            assertTrue(host.removeSeed("b"))
            assertEquals(listOf("a"), host.seedQueue.value.map { it.id })
        } finally {
            host.stop()
        }
    }

    @Test
    fun aHoldNoSeedAnsweredIsReportedAsDecidedSoAFrontendKnowsItNeedsAHuman() = runBlocking {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        val connection = FakeConnection()
        val serving = launch { engine.attach(connection) }
        try {
            connection.incoming.send(Envelope(hello = HELLO).encode())
            connection.incoming.send(
                Envelope(
                    breakpoint_hit = BreakpointHit(
                        correlation_id = "unmatched",
                        rule_id = "bp",
                        phase = BreakpointPhase.BREAKPOINT_PHASE_RESPONSE,
                        request = HttpRequest(method = "GET", url = "https://example.com/ping"),
                        response = HttpResponse(code = 500),
                    ),
                ).encode(),
            )

            withTimeout(2_000) {
                while ("unmatched" !in host.triagedHolds.value) delay(10)
            }
            // Still held: decided means "nothing answered it", which is exactly when a window should open.
            assertEquals("unmatched", engine.pausedExchanges.value.single().correlationId)
        } finally {
            connection.incoming.close()
            serving.join()
            host.stop()
        }
    }

    private class FakeConnection : DeviceConnection {
        override val id = "usb:test"
        override val transport = DeviceTransport.USB
        override val isTrusted = true
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val outgoing = Channel<ByteArray>(Channel.UNLIMITED)

        override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()

        override suspend fun send(bytes: ByteArray) {
            outgoing.send(bytes)
        }

        override fun close() {
            incoming.close()
            outgoing.close()
        }
    }

    private companion object {
        val HELLO = Hello(
            device_name = "device",
            app_id = "com.example",
            platform = "android",
        )
    }
}
