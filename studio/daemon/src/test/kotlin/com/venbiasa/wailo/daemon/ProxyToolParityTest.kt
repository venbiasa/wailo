package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.CaptureSource
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.HostSeed
import com.venbiasa.wailo.protocol.Header
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The rule sets are shared, so a rule written once has to act on both capture paths (ADR-0067). These
 * drive real sockets through the bundled proxy rather than calling the bridge directly: the thing worth
 * proving is that a client's own connection sees the answer, not that a matcher returned an object.
 */
class ProxyToolParityTest {
    private val closeables = mutableListOf<Closeable>()
    private val engine = WailoEngine(port = freePort())
    private val host = HeadlessHost.wrap(engine)
    private val proxy = ProxyController(host, 0).also { closeables += it }

    @AfterTest
    fun tearDown() {
        closeables.reversed().forEach { runCatching(it::close) }
        host.stop()
    }

    @Test
    fun aMapLocalRuleAnswersAProxiedRequestWithoutTheOrigin() {
        // Bound and released, so nothing is listening: reaching the origin at all would fail the relay.
        val deadPort = freePort()
        runBlocking {
            host.upsertMapLocalRule(
                HostMapLocalRule(
                    id = "m1",
                    urlPattern = "http://127.0.0.1:$deadPort/*",
                    statusCode = 201,
                    headers = listOf(Header("Content-Type", "application/json")),
                    body = """{"mapped":true}""".toByteArray(),
                ),
            )
        }
        startProxy()

        val response = get("http://127.0.0.1:$deadPort/thing")

        assertTrue(response.startsWith("HTTP/1.1 201"), response)
        assertTrue(response.endsWith("""{"mapped":true}"""), response)
        val row = awaitRow()
        assertEquals(CaptureSource.PROXY, row.source)
        assertEquals(201, row.exchange.response?.code)
    }

    @Test
    fun aSeedSpendsOnAProxiedHold() {
        val origin = origin { out -> out.respond("from the network") }
        runBlocking {
            host.upsertBreakpointRule(
                HostBreakpointRule(id = "b1", urlPattern = "http://127.0.0.1:${origin.port}/*", onResponse = true),
            )
            host.upsertSeed(HostSeed(id = "s1", urlPattern = "http://127.0.0.1:${origin.port}/*", statusCode = 503, body = "seeded".toByteArray()))
            host.fillSeeds()
        }
        startProxy()

        val response = get("http://127.0.0.1:${origin.port}/poll")

        assertTrue(response.startsWith("HTTP/1.1 503"), response)
        assertTrue(response.endsWith("seeded"), response)
        assertTrue(host.listHolds().isEmpty(), "the spend must release the hold")
    }

    @Test
    fun aBreakpointHoldIsResumableFromTheSameQueueTheCliUses() {
        val origin = origin { out -> out.respond("original") }
        runBlocking {
            host.upsertBreakpointRule(
                HostBreakpointRule(id = "b1", urlPattern = "http://127.0.0.1:${origin.port}/*", onResponse = true),
            )
            host.setSeedsEnabled(false)
        }
        startProxy()

        val client = thread(isDaemon = true) { get("http://127.0.0.1:${origin.port}/held") }
        val hold = runBlocking { host.waitForHold() }
        assertEquals("original", hold?.response?.body?.utf8())
        assertTrue(host.resumeHold(hold!!.correlationId), "the hold must be resolvable by correlation id")
        client.join(5_000)
        assertTrue(host.listHolds().isEmpty())
    }

    @Test
    fun aBlockedHostIsStillRelayedButNotRecorded() {
        val origin = origin { out -> out.respond("served anyway") }
        host.updateCaptureFilter(false, emptyList(), true, listOf("127.0.0.1"))
        startProxy()

        val response = get("http://127.0.0.1:${origin.port}/quiet")

        assertTrue(response.endsWith("served anyway"), response)
        Thread.sleep(200)
        assertTrue(engine.exchanges.value.isEmpty(), "a blocked host must not be recorded")
    }

    private fun startProxy() {
        assertTrue(proxy.start(0), "the proxy must bind an ephemeral port")
        host.setCapturing(true)
    }

    private fun awaitRow() = generateSequence { engine.exchanges.value.firstOrNull() ?: Thread.sleep(50).let { null } }
        .take(60)
        .firstOrNull()
        ?: error("no exchange was recorded")

    private fun get(url: String): String = Socket().use { client ->
        client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.status.value.port), 2_000)
        client.soTimeout = 10_000
        client.getOutputStream().apply {
            write("GET $url HTTP/1.1\r\nHost: parity-test\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        String(client.getInputStream().readBytes())
    }

    private fun origin(handle: (OutputStream) -> Unit): TestOrigin {
        val socket = ServerSocket()
        socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val origin = TestOrigin(socket)
        closeables += origin
        thread(isDaemon = true, name = "parity-origin") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    client.use {
                        runCatching {
                            it.getInputStream().consumeHead()
                            handle(it.getOutputStream())
                        }
                    }
                }
            }
        }
        return origin
    }
}

private class TestOrigin(private val socket: ServerSocket) : Closeable {
    val port: Int get() = socket.localPort
    override fun close() = socket.close()
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun OutputStream.respond(body: String) {
    write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
    flush()
}

private fun InputStream.consumeHead() {
    val line = ByteArrayOutputStream()
    while (true) {
        val next = read()
        if (next < 0) return
        if (next == '\n'.code) {
            if (line.size() == 0) return
            line.reset()
        } else if (next != '\r'.code) {
            line.write(next)
        }
    }
}
