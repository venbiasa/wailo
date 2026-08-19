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
import okio.ByteString.Companion.toByteString

class HeadlessHostTest {

    @Test
    fun holdRetriesWhenSeedProviderBecomesReady() = runBlocking {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        val connection = FakeConnection()
        val serving = launch { engine.attach(connection) }
        try {
            host.armSeeds(listOf(HostSeed(id = "seed", urlPattern = "https://example.com/*")))
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

            // The hold arrived before body resolution was configured. Installing the provider must
            // retrigger triage rather than leaving the earlier "seen" marker to wedge the device.
            host.seedResponseProvider = SeedResponseProvider {
                HttpResponse(code = 202, body = "{}".toByteArray().toByteString())
            }

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
            assertTrue(host.seedQueue.value.isEmpty())
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
                    methods = listOf("GET"),
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
                    methods = listOf("POST"),
                    onRequest = true,
                    onResponse = false,
                ),
            )

            val pushed = engine.breakpointRules.value.rules.single()
            assertEquals("errors", pushed.id)
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
    fun seedsMasterDropsTheQueueItDeclinesToFill() = runBlocking {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        try {
            host.seedResponseProvider = SeedResponseProvider { HttpResponse(code = 200) }
            host.fillSeeds(listOf(HostSeed(id = "seed", urlPattern = "https://example.com/*")))
            assertEquals("seed", host.seedQueue.value.single().id)

            // Unlike Map Local and breakpoints, whose masters only withhold the push, this one is
            // destructive by design: a run it declines is discarded rather than left armed.
            host.seedsEnabled = false
            host.fillSeeds()
            assertTrue(host.seedQueue.value.isEmpty())
        } finally {
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
