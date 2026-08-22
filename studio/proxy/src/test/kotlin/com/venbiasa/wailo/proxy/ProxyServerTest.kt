package com.venbiasa.wailo.proxy

import com.venbiasa.wailo.protocol.HttpExchange
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyServerTest {
    private val closeables = mutableListOf<Closeable>()

    @AfterTest
    fun tearDown() {
        closeables.reversed().forEach { runCatching(it::close) }
        closeables.clear()
    }

    @Test
    fun aPlainRequestReachesTheOriginAndIsCaptured() {
        val origin = origin { _, out -> out.respond("hello from origin") }
        val sink = RecordingSink()
        val proxy = proxy(sink)

        val response = proxy.request("GET http://127.0.0.1:${origin.port}/greet HTTP/1.1")

        assertTrue(response.contains("hello from origin"), response)
        val row = sink.awaitOne()
        assertEquals("GET", row.exchange.request?.method)
        assertEquals("http://127.0.0.1:${origin.port}/greet", row.exchange.request?.url)
        assertEquals(200, row.exchange.response?.code)
        assertEquals("hello from origin", sink.body(row.responseBody))
    }

    @Test
    fun theOriginSeesTheRequestInOriginFormWithItsHost() {
        val origin = origin { request, out ->
            out.respond(request.first().trim() + "|" + request.first { it.startsWith("Host:") }.trim())
        }
        val proxy = proxy(RecordingSink())

        val response = proxy.request("GET http://127.0.0.1:${origin.port}/a/b?c=d HTTP/1.1")

        assertTrue(response.contains("GET /a/b?c=d HTTP/1.1|Host: 127.0.0.1:${origin.port}"), response)
    }

    @Test
    fun aRequestBodyIsForwardedAndCaptured() {
        val origin = origin { request, out ->
            val length = request.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
            out.respond("received $length")
        }
        val sink = RecordingSink()
        val proxy = proxy(sink)

        val response = proxy.request(
            "POST http://127.0.0.1:${origin.port}/submit HTTP/1.1",
            headers = listOf("Content-Length: 11"),
            body = "hello world",
        )

        assertTrue(response.contains("received 11"), response)
        val row = sink.awaitOne()
        assertEquals("hello world", sink.body(row.requestBody))
    }

    @Test
    fun aChunkedResponseReachesTheClientChunkedAndIsCapturedWhole() {
        val origin = origin { _, out ->
            out.write(
                (
                    "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                        "5\r\nfirst\r\n6\r\nsecond\r\n0\r\n\r\n"
                    ).toByteArray(),
            )
            out.flush()
        }
        val sink = RecordingSink()
        val proxy = proxy(sink)

        val response = proxy.request("GET http://127.0.0.1:${origin.port}/stream HTTP/1.1")

        assertTrue(response.contains("5\r\nfirst\r\n6\r\nsecond\r\n0\r\n\r\n"), response)
        assertEquals("firstsecond", sink.body(sink.awaitOne().responseBody))
    }

    @Test
    fun aGzippedResponseIsForwardedCompressedAndCapturedReadable() {
        val payload = "the quick brown fox".repeat(20)
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(payload.toByteArray()) }
        }.toByteArray()
        val origin = origin { _, out ->
            out.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n" +
                        "Content-Length: ${compressed.size}\r\n\r\n"
                    ).toByteArray(),
            )
            out.write(compressed)
            out.flush()
        }
        val sink = RecordingSink()
        val proxy = proxy(sink)

        val raw = proxy.requestBytes("GET http://127.0.0.1:${origin.port}/gz HTTP/1.1")

        assertTrue(raw.toList().windowed(compressed.size).any { it == compressed.toList() }, "raw bytes must pass through")
        val row = sink.awaitOne()
        assertEquals(payload, sink.body(row.responseBody))
        // The recorded headers describe the recorded body, so a decoded body cannot claim to be gzip.
        val headers = row.exchange.response?.headers.orEmpty()
        assertNull(headers.firstOrNull { it.name.equals("Content-Encoding", true) })
        assertEquals(payload.length.toString(), headers.first { it.name.equals("Content-Length", true) }.value_)
    }

    @Test
    fun aConnectIsTunnelledOpaquelyAndRecordedAsLocked() {
        // Not TLS, just bytes: the point is that the proxy relays them untouched and captures neither.
        val origin = rawOrigin { input, output ->
            val echoed = ByteArray(5).also { input.readNBytes(it, 0, 5) }
            output.write(echoed)
            output.flush()
        }
        val sink = RecordingSink()
        val proxy = proxy(sink)

        Socket().use { client ->
            client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.port), 2_000)
            client.soTimeout = 5_000
            val out = client.getOutputStream()
            out.write("CONNECT 127.0.0.1:${origin.port} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            out.flush()
            val input = client.getInputStream()
            val established = readHead(input)
            assertEquals("200", established?.second)
            out.write("bytes".toByteArray())
            out.flush()
            assertEquals("bytes", String(input.readNBytes(5)))
        }

        val row = sink.awaitOne()
        assertEquals("CONNECT", row.exchange.request?.method)
        assertEquals("https://127.0.0.1:${origin.port}", row.exchange.request?.url)
        assertEquals(200, row.exchange.response?.code)
        assertNull(row.requestBody)
        assertNull(row.responseBody)
    }

    @Test
    fun browsingToTheProxyPortExplainsItself() {
        val proxy = proxy(RecordingSink())

        val response = proxy.request("GET /favicon.ico HTTP/1.1")

        assertTrue(response.startsWith("HTTP/1.1 400 Bad Request"), response)
        assertTrue(response.contains("Set it as your HTTP proxy"), response)
    }

    @Test
    fun anUnreachableOriginIsAGatewayErrorAndAFailedRow() {
        val sink = RecordingSink()
        val proxy = proxy(sink)
        // Bound and immediately released, so the port is almost certainly nobody's.
        val deadPort = ServerSocket(0).use { it.localPort }

        val response = proxy.request("GET http://127.0.0.1:$deadPort/gone HTTP/1.1")

        assertTrue(response.startsWith("HTTP/1.1 502 Bad Gateway"), response)
        val row = sink.awaitOne()
        assertTrue(row.exchange.error.isNotEmpty(), "a failed relay must record why")
        assertNull(row.exchange.response)
    }

    @Test
    fun aPausedCaptureStillRelays() {
        val origin = origin { _, out -> out.respond("still served") }
        val sink = RecordingSink(recording = false)
        val proxy = proxy(sink)

        val response = proxy.request("GET http://127.0.0.1:${origin.port}/x HTTP/1.1")

        assertTrue(response.contains("still served"), response)
        assertTrue(sink.bodies.isEmpty(), "a paused capture must not spool anything")
    }

    private fun proxy(sink: ProxyCaptureSink): ProxyServer =
        ProxyServer.start(0, sink).also { closeables += it }

    private fun origin(handle: (List<String>, OutputStream) -> Unit): TestOrigin =
        rawOrigin { input, output ->
            val request = buildList {
                while (true) {
                    val line = input.bufferedReadLine() ?: break
                    if (line.isBlank()) break
                    add(line)
                }
            }
            handle(request, output)
        }

    private fun rawOrigin(handle: (InputStream, OutputStream) -> Unit): TestOrigin {
        val socket = ServerSocket()
        socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val origin = TestOrigin(socket)
        closeables += origin
        thread(isDaemon = true, name = "test-origin") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    client.use { runCatching { handle(it.getInputStream(), it.getOutputStream()) } }
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

private fun ProxyServer.request(
    startLine: String,
    headers: List<String> = emptyList(),
    body: String? = null,
): String = String(requestBytes(startLine, headers, body))

private fun ProxyServer.requestBytes(
    startLine: String,
    headers: List<String> = emptyList(),
    body: String? = null,
): ByteArray = Socket().use { client ->
    client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
    client.soTimeout = 5_000
    val out = client.getOutputStream()
    val head = buildString {
        append("$startLine\r\n")
        append("Host: proxy-test\r\n")
        headers.forEach { append("$it\r\n") }
        append("Connection: close\r\n\r\n")
    }
    out.write(head.toByteArray())
    body?.let { out.write(it.toByteArray()) }
    out.flush()
    client.getInputStream().readBytes()
}

private fun OutputStream.respond(body: String) {
    write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
    flush()
}

private fun InputStream.bufferedReadLine(): String? {
    val line = ByteArrayOutputStream()
    while (true) {
        val next = read()
        if (next < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1)
        if (next == '\n'.code) break
        line.write(next)
    }
    return line.toString(Charsets.ISO_8859_1).removeSuffix("\r")
}

private class RecordingSink(private val recording: Boolean = true) : ProxyCaptureSink {
    val bodies = ConcurrentHashMap<String, ByteArray>()
    private val rows = CopyOnWriteArrayList<Row>()
    private val recorded = CountDownLatch(1)

    class Row(
        val exchange: HttpExchange,
        val client: String,
        val requestBody: ProxyBodyRef?,
        val responseBody: ProxyBodyRef?,
    )

    override fun isRecording(): Boolean = recording

    override fun openBody(): ProxyBodySink = object : ProxyBodySink {
        private val buffer = ByteArrayOutputStream()
        override fun write(chunk: ByteArray, offset: Int, length: Int) = buffer.write(chunk, offset, length)
        override fun commit(): ProxyBodyRef? {
            if (buffer.size() == 0) return null
            val id = UUID.randomUUID().toString()
            bodies[id] = buffer.toByteArray()
            return ProxyBodyRef(id, buffer.size().toLong())
        }
        override fun close() = buffer.reset()
    }

    override fun record(
        exchange: HttpExchange,
        client: String,
        requestBody: ProxyBodyRef?,
        responseBody: ProxyBodyRef?,
    ) {
        rows += Row(exchange, client, requestBody, responseBody)
        recorded.countDown()
    }

    fun awaitOne(): Row {
        assertTrue(recorded.await(5, TimeUnit.SECONDS), "no exchange was recorded")
        return rows.first()
    }

    fun body(ref: ProxyBodyRef?): String = String(bodies[assertNotNull(ref).id] ?: ByteArray(0))
}
