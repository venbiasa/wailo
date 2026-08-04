package com.venbiasa.wailo.engine

import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpExchange
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
