package com.venbiasa.wailo.engine

import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.ScriptPhase
import com.venbiasa.wailo.protocol.ScriptRule
import com.venbiasa.wailo.protocol.ScriptRuleSetAck
import com.venbiasa.wailo.protocol.ScriptTransformRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WailoEngineDeviceConnectionTest {

    @Test
    fun transportNeutralUsbConnectionUsesTheNormalSessionPipeline() = runBlocking {
        val engine = WailoEngine()
        val connection = FakeConnection()
        val serving = launch { engine.attach(connection) }

        connection.incoming.send(Envelope(hello = HELLO).encode())
        connection.incoming.send(Envelope(exchange = HttpExchange(id = "usb-exchange")).encode())

        withTimeout(2_000) {
            while (engine.exchanges.value.isEmpty() || engine.connectedDevices.value.isEmpty()) delay(10)
        }
        assertEquals("usb-exchange", engine.exchanges.value.single().exchange.id)
        val device = engine.connectedDevices.value.single()
        assertEquals(DeviceTransport.USB, device.transport)
        assertEquals("usb-device", device.deviceName)

        connection.incoming.close()
        serving.join()
        assertTrue(engine.connectedDevices.value.isEmpty())
        assertTrue(connection.closed)
    }

    @Test
    fun lanRebindDoesNotDropUsbConnection() = runBlocking {
        val engine = WailoEngine(port = freePort())
        val connection = FakeConnection()
        val serving = launch { engine.attach(connection) }
        try {
            connection.incoming.send(Envelope(hello = HELLO).encode())
            withTimeout(2_000) {
                while (engine.connectedDevices.value.isEmpty()) delay(10)
            }

            assertTrue(engine.rebind(freePort()))
            assertTrue(!connection.closed)
            assertEquals(DeviceTransport.USB, engine.connectedDevices.value.single().transport)

            connection.incoming.send(Envelope(exchange = HttpExchange(id = "after-rebind")).encode())
            withTimeout(2_000) {
                while (engine.exchanges.value.none { it.exchange.id == "after-rebind" }) delay(10)
            }
        } finally {
            connection.incoming.close()
            serving.join()
            engine.stop()
        }
    }

    @Test
    fun scriptSnapshotAndTransformUseTheNormalSessionPipeline() = runBlocking {
        val engine = WailoEngine(ackRetryMs = 10_000)
        engine.updateScriptRules(
            listOf(ScriptRule(id = "script", enabled = true, url_pattern = "*", on_request = true)),
        )
        engine.scriptTransformProvider = ScriptTransformProvider { request ->
            request.identityResult().copy(request = request.request?.copy(method = "PATCH"))
        }
        val connection = FakeConnection()
        val serving = launch { engine.attach(connection) }
        try {
            var scriptEpoch = 0L
            withTimeout(2_000) {
                while (scriptEpoch == 0L) {
                    val envelope = Envelope.ADAPTER.decode(connection.outgoing.receive())
                    envelope.script_rule_set?.let {
                        assertEquals("script", it.rules.single().id)
                        scriptEpoch = it.epoch
                    }
                }
            }
            connection.incoming.send(
                Envelope(script_rule_set_ack = ScriptRuleSetAck(epoch = scriptEpoch)).encode(),
            )
            connection.incoming.send(
                Envelope(
                    script_transform_request = ScriptTransformRequest(
                        correlation_id = "transform",
                        phase = ScriptPhase.SCRIPT_PHASE_REQUEST,
                        request = HttpRequest(method = "GET", url = "https://example.com"),
                        request_body_replayable = true,
                    ),
                ).encode(),
            )
            val result = withTimeout(2_000) {
                while (true) {
                    val envelope = Envelope.ADAPTER.decode(connection.outgoing.receive())
                    envelope.script_transform_result?.let { return@withTimeout it }
                }
                error("unreachable")
            }
            assertEquals("transform", result.correlation_id)
            assertEquals("PATCH", result.request?.method)
        } finally {
            connection.incoming.close()
            serving.join()
            engine.stop()
        }
    }

    private class FakeConnection : DeviceConnection {
        override val id = "usb:test"
        override val transport = DeviceTransport.USB
        override val isTrusted = true
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
        var closed = false

        override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()

        override suspend fun send(bytes: ByteArray) {
            outgoing.send(bytes)
        }

        override fun close() {
            closed = true
            incoming.close()
            outgoing.close()
        }
    }

    private companion object {
        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        val HELLO = Hello(
            device_name = "usb-device",
            app_id = "com.venbiasa.usb",
            platform = "ios",
        )
    }
}
