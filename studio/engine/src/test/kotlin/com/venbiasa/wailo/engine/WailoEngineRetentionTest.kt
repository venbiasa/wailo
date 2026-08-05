package com.venbiasa.wailo.engine

import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.HttpExchange
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class WailoEngineRetentionTest {

    @Test
    fun theOldestExchangesFallOffOnceTheCapIsFull() = runBlocking {
        val cap = WailoEngine.RETAINED_RANGE.first
        val overflow = 20
        val engine = WailoEngine(maxRetained = cap)
        val connection = FakeConnection()
        val serving = launch { engine.attach(connection) }
        try {
            repeat(cap + overflow) { connection.incoming.send(exchange("e$it")) }
            // The session handler reads the channel in order, so seeing the last id means every earlier
            // one has already been through record() — including the ones that should have fallen off.
            engine.awaitExchange("e${cap + overflow - 1}")

            assertEquals(cap, engine.exchanges.value.size)
            assertEquals("e$overflow", engine.exchanges.value.first().exchange.id)
        } finally {
            connection.incoming.close()
            serving.join()
        }
    }

    @Test
    fun loweringTheCapTrimsWhatIsAlreadyHeld() = runBlocking {
        val floor = WailoEngine.RETAINED_RANGE.first
        val held = floor + 50
        val engine = WailoEngine(maxRetained = held)
        val connection = FakeConnection()
        val serving = launch { engine.attach(connection) }
        try {
            repeat(held) { connection.incoming.send(exchange("e$it")) }
            engine.awaitExchange("e${held - 1}")

            engine.setMaxRetained(floor)

            assertEquals(floor, engine.maxRetained.value)
            assertEquals(floor, engine.exchanges.value.size)
            assertEquals("e${held - floor}", engine.exchanges.value.first().exchange.id)
        } finally {
            connection.incoming.close()
            serving.join()
        }
    }

    @Test
    fun aCapUnderTheFloorIsClampedRatherThanKeepingNothing() {
        val engine = WailoEngine()

        engine.setMaxRetained(0)

        assertEquals(WailoEngine.RETAINED_RANGE.first, engine.maxRetained.value)
    }

    private class FakeConnection : DeviceConnection {
        override val id = "usb:retention"
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
        fun exchange(id: String): ByteArray = Envelope(exchange = HttpExchange(id = id)).encode()

        suspend fun WailoEngine.awaitExchange(id: String) = withTimeout(5_000) {
            while (exchanges.value.none { it.exchange.id == id }) delay(10)
        }
    }
}
