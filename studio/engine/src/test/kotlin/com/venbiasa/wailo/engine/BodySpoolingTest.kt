package com.venbiasa.wailo.engine

import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * The engine's half of the disk-body contract (ADR-0069): a recorded exchange keeps its metadata and
 * gives up its bytes, and anything that drops a row drops the bytes with it.
 */
class BodySpoolingTest {

    @Test
    fun aRecordedExchangeKeepsItsMetadataAndGivesUpItsBytes() = runBlocking {
        val store = RecordingBodyStore()
        val engine = WailoEngine(bodyStore = store)

        engine.feed(
            exchange(
                "e1",
                request = HttpRequest(method = "POST", url = "https://example.com/x", body = "hello".encodeUtf8()),
                response = HttpResponse(code = 200, body = "world!".encodeUtf8()),
            ),
        )

        val row = engine.exchanges.value.single()
        assertEquals("POST", row.exchange.request?.method)
        assertEquals(0, row.exchange.request?.body?.size, "the inline request body should have been dropped")
        assertEquals(0, row.exchange.response?.body?.size, "the inline response body should have been dropped")
        assertEquals(5L, assertNotNull(row.requestBody).size)
        assertEquals(6L, assertNotNull(row.responseBody).size)
        assertEquals("hello", engine.readBody(row.requestBody!!, 0, 64).decodeToString())
        assertEquals("world!", engine.readBody(row.responseBody!!, 0, 64).decodeToString())
    }

    @Test
    fun anEmptyBodyGetsNoHandle() = runBlocking {
        val engine = WailoEngine(bodyStore = RecordingBodyStore())

        engine.feed(exchange("e1", request = HttpRequest(method = "GET", url = "https://example.com/")))

        val row = engine.exchanges.value.single()
        assertNull(row.requestBody)
        assertNull(row.responseBody)
    }

    @Test
    fun exchangesThatFallOffTheCapTakeTheirBodiesWithThem() = runBlocking {
        val store = RecordingBodyStore()
        val cap = WailoEngine.RETAINED_RANGE.first
        val engine = WailoEngine(maxRetained = cap, bodyStore = store)

        engine.feed(*bodiedExchanges(cap + 20))

        assertEquals(cap, engine.exchanges.value.size)
        assertEquals(cap, store.live.size, "evicted rows left their bodies behind")
    }

    @Test
    fun loweringTheCapFreesTheBodiesItDiscards() = runBlocking {
        val store = RecordingBodyStore()
        val floor = WailoEngine.RETAINED_RANGE.first
        val engine = WailoEngine(maxRetained = floor + 50, bodyStore = store)
        engine.feed(*bodiedExchanges(floor + 50))

        engine.setMaxRetained(floor)

        assertEquals(floor, store.live.size)
    }

    @Test
    fun clearingTheCaptureFreesEveryBody() = runBlocking {
        val store = RecordingBodyStore()
        val engine = WailoEngine(bodyStore = store)
        engine.feed(*bodiedExchanges(20))

        engine.clear()

        assertEquals(0, store.live.size)
    }

    /** What the store calls when the volume it spools to is filling up. */
    @Test
    fun evictingUnderDiskPressureRetiresTheOldestRowsAndTheirBodies() = runBlocking {
        val store = RecordingBodyStore()
        val engine = WailoEngine(bodyStore = store)
        engine.feed(*bodiedExchanges(20))

        engine.evictOldest(5)

        assertEquals(15, engine.exchanges.value.size)
        assertEquals("e5", engine.exchanges.value.first().exchange.id)
        assertEquals(15, store.live.size)
    }

    /**
     * A body the store could not take must not read back as an empty one. Marking the side truncated is
     * the existing vocabulary for "this many bytes existed and we do not have them".
     */
    @Test
    fun aBodyTheStoreRefusesIsReportedAsTruncatedRatherThanAsEmpty() = runBlocking {
        val engine = WailoEngine(bodyStore = RefusingBodyStore())

        engine.feed(
            exchange(
                "e1",
                response = HttpResponse(code = 200, body = "payload".encodeUtf8(), body_size = 7),
            ),
        )

        val row = engine.exchanges.value.single()
        assertNull(row.responseBody)
        assertTrue(row.exchange.response?.body_truncated == true)
        assertEquals(7L, row.exchange.response?.body_size)
    }

    private class RecordingBodyStore : BodyStore {
        val live = ConcurrentHashMap<String, ByteArray>()

        override fun openSink(): BodySink = object : BodySink {
            private val buffer = okio.Buffer()
            private var committed = false

            override fun write(chunk: ByteArray, offset: Int, length: Int) {
                buffer.write(chunk, offset, length)
            }

            override fun commit(): BodyRef? {
                if (committed || buffer.size == 0L) return null
                committed = true
                val bytes = buffer.readByteArray()
                val id = UUID.randomUUID().toString()
                live[id] = bytes
                return BodyRef(id, bytes.size.toLong())
            }

            override fun close() = buffer.clear()
        }

        override fun read(ref: BodyRef, offset: Long, length: Int): ByteArray {
            val bytes = live[ref.id] ?: return ByteArray(0)
            if (offset >= bytes.size) return ByteArray(0)
            return bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
        }

        override fun open(ref: BodyRef): InputStream = ByteArrayInputStream(live[ref.id] ?: ByteArray(0))

        override fun release(refs: Collection<BodyRef>) {
            refs.forEach { live.remove(it.id) }
        }

        override fun close() = live.clear()
    }

    /** Stands in for a full volume: the sink accepts writes and then cannot seal them. */
    private class RefusingBodyStore : BodyStore {
        override fun openSink(): BodySink = object : BodySink {
            override fun write(chunk: ByteArray, offset: Int, length: Int) = Unit
            override fun commit(): BodyRef? = throw java.io.IOException("No space left on device")
            override fun close() = Unit
        }

        override fun read(ref: BodyRef, offset: Long, length: Int) = ByteArray(0)
        override fun open(ref: BodyRef): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun release(refs: Collection<BodyRef>) = Unit
        override fun close() = Unit
    }

    private class FakeConnection : DeviceConnection {
        override val id = "usb:spooling"
        override val transport = DeviceTransport.USB
        override val isTrusted = true
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)

        override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()
        override suspend fun send(bytes: ByteArray) = Unit
        override fun close() {
            incoming.close()
        }
    }

    private companion object {
        fun body(index: Int): ByteString = "body-$index".encodeUtf8()

        fun exchange(id: String, request: HttpRequest? = null, response: HttpResponse? = null) =
            HttpExchange(id = id, request = request, response = response)

        fun bodiedExchanges(count: Int): Array<HttpExchange> =
            Array(count) { exchange("e$it", response = HttpResponse(code = 200, body = body(it))) }

        /**
         * Push exchanges through the real session loop rather than reaching past it, so what is under
         * test is the path a device actually takes into `record`.
         */
        suspend fun WailoEngine.feed(vararg exchanges: HttpExchange) = coroutineScope {
            val connection = FakeConnection()
            val serving = launch { attach(connection) }
            exchanges.forEach { connection.incoming.send(Envelope(exchange = it).encode()) }
            // The session handler drains the channel in order, so seeing the last id means every earlier
            // exchange has already been recorded.
            withTimeout(5_000) {
                val last = exchanges.last().id
                while (this@feed.exchanges.value.none { it.exchange.id == last }) delay(5)
            }
            connection.incoming.close()
            serving.join()
        }
    }
}
