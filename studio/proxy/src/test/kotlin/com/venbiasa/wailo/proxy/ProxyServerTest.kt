package com.venbiasa.wailo.proxy

import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
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
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun expectContinueDoesNotDeadlockTheClientAndIsHandledAtThisHop() {
        val origin = rawOrigin { input, output ->
            val request = buildList {
                while (true) {
                    val line = input.bufferedReadLine() ?: break
                    if (line.isBlank()) break
                    add(line)
                }
            }
            assertTrue(request.none { it.startsWith("Expect:", ignoreCase = true) })
            val length = request.first { it.startsWith("Content-Length:", true) }
                .substringAfter(':')
                .trim()
                .toInt()
            val body = String(input.readNBytes(length))
            output.respond("received $body")
        }
        val sink = RecordingSink()
        val proxy = proxy(sink)

        Socket().use { client ->
            client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.port), 2_000)
            client.soTimeout = 5_000
            client.getOutputStream().apply {
                write(
                    (
                        "POST http://127.0.0.1:${origin.port}/continue HTTP/1.1\r\n" +
                            "Host: proxy-test\r\nContent-Length: 7\r\nExpect: 100-continue\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                flush()
            }
            assertEquals("100", readHead(client.getInputStream())?.second)
            client.getOutputStream().apply {
                write("payload".toByteArray())
                flush()
            }
            assertTrue(String(client.getInputStream().readBytes()).contains("received payload"))
        }
        assertEquals("payload", sink.body(sink.awaitOne().requestBody))
    }

    @Test
    fun informationalResponsesPassThroughBeforeTheCapturedFinalResponse() {
        val origin = origin { _, output ->
            output.write("HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload\r\n\r\n".toByteArray())
            output.respond("final")
        }
        val sink = RecordingSink()
        val proxy = proxy(sink)

        val response = proxy.request("GET http://127.0.0.1:${origin.port}/hints HTTP/1.1")

        assertTrue(response.startsWith("HTTP/1.1 103 Early Hints"), response)
        assertTrue(response.contains("HTTP/1.1 200 OK"), response)
        assertEquals(200, sink.awaitOne().exchange.response?.code)
    }

    @Test
    fun anUpgradeBecomesABidirectionalTunnelAfterItsHandshake() {
        val origin = rawOrigin { input, output ->
            val request = buildList {
                while (true) {
                    val line = input.bufferedReadLine() ?: break
                    if (line.isBlank()) break
                    add(line)
                }
            }
            assertTrue(request.any { it.equals("Connection: Upgrade", ignoreCase = true) })
            assertTrue(request.any { it.equals("Upgrade: websocket", ignoreCase = true) })
            output.write(
                "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n"
                    .toByteArray(),
            )
            output.flush()
            output.write(input.readNBytes(4))
            output.flush()
        }
        val sink = RecordingSink()
        val proxy = proxy(sink)

        Socket().use { client ->
            client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.port), 2_000)
            client.soTimeout = 5_000
            client.getOutputStream().apply {
                write(
                    (
                        "GET http://127.0.0.1:${origin.port}/socket HTTP/1.1\r\n" +
                            "Host: proxy-test\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n"
                        ).toByteArray(),
                )
                flush()
            }
            assertEquals("101", readHead(client.getInputStream())?.second)
            client.getOutputStream().apply {
                write("ping".toByteArray())
                flush()
            }
            assertEquals("ping", String(client.getInputStream().readNBytes(4)))
        }

        val row = sink.awaitOne()
        assertEquals(101, row.exchange.response?.code)
        assertTrue(row.exchange.response?.headers.orEmpty().any { it.name.equals("Upgrade", true) })
    }

    @Test
    fun closingTheProxyTerminatesAnActiveEventStream() {
        val origin = rawOrigin { input, output ->
            while (input.bufferedReadLine()?.isNotBlank() == true) Unit
            output.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                        "Transfer-Encoding: chunked\r\n\r\n4\r\ntick\r\n"
                    ).toByteArray(),
            )
            output.flush()
            input.read()
        }
        val proxy = proxy(RecordingSink())
        val client = Socket()
        client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.port), 2_000)
        client.soTimeout = 5_000
        client.getOutputStream().apply {
            write(
                (
                    "GET http://127.0.0.1:${origin.port}/events HTTP/1.1\r\n" +
                        "Host: proxy-test\r\n\r\n"
                    ).toByteArray(),
            )
            flush()
        }

        assertEquals("200", readHead(client.getInputStream())?.second)
        assertEquals("4\r\ntick\r\n", String(client.getInputStream().readNBytes(9)))

        proxy.close()
        assertEquals(-1, client.getInputStream().read())
        client.close()
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
    fun aCodingWeCannotDecodeKeepsItsHeaderSoTheDumpIsExplained() {
        // Not real brotli — nothing here decodes it, which is the whole point. What matters is that the
        // record does not claim these bytes are plaintext, because the header is the only thing that
        // tells a reader why the body looks like garbage.
        val payload = ByteArray(64) { (it * 7).toByte() }
        val origin = origin { _, out ->
            out.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: br\r\n" +
                        "Content-Length: ${payload.size}\r\n\r\n"
                    ).toByteArray(),
            )
            out.write(payload)
            out.flush()
        }
        val sink = RecordingSink()
        val proxy = proxy(sink)

        val raw = proxy.requestBytes("GET http://127.0.0.1:${origin.port}/br HTTP/1.1")

        assertTrue(raw.toList().windowed(payload.size).any { it == payload.toList() }, "raw bytes must pass through")
        val row = sink.awaitOne()
        val headers = row.exchange.response?.headers.orEmpty()
        assertEquals("br", headers.first { it.name.equals("Content-Encoding", true) }.value_)
        assertContentEquals(payload, sink.bodyBytes(row.responseBody))
    }

    @Test
    fun aResponseHoldIsDeclinedForACodingItCouldNotShow() {
        // A hold that fired here would offer compressed bytes as the body and then have to strip the
        // coding header to deliver them, so it is declined and the exchange relays through instead.
        val payload = ByteArray(48) { (it * 5 + 1).toByte() }
        val origin = origin { _, out ->
            out.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Encoding: br\r\n" +
                        "Content-Length: ${payload.size}\r\n\r\n"
                    ).toByteArray(),
            )
            out.write(payload)
            out.flush()
        }
        var held = false
        val sink = RecordingSink()
        val proxy = proxy(
            sink,
            rules(
                intercepts = { Interception(holdResponse = true) },
                onResponse = { response ->
                    held = true
                    ResponseVerdict.Proceed(response.copy(body = "rewritten".encodeUtf8()))
                },
            ),
        )

        val raw = proxy.requestBytes("GET http://127.0.0.1:${origin.port}/br HTTP/1.1")

        assertFalse(held, "the hold must not fire on a body it cannot decode")
        assertTrue(
            raw.toList().windowed(payload.size).any { it == payload.toList() },
            "the client must get the origin's bytes",
        )
        val headers = sink.awaitOne().exchange.response?.headers.orEmpty()
        assertEquals("br", headers.first { it.name.equals("Content-Encoding", true) }.value_)
    }

    @Test
    fun aGzippedRequestBodyIsHeldDecodedAndForwardedThatWay() {
        // The hold decodes on the way in, so the headers it forwards must not keep saying gzip — the
        // origin would inflate plaintext. The record has to agree for the same reason.
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write("original".toByteArray()) }
        }.toByteArray()
        var seenBody: String? = null
        var upstreamCoding: String? = null
        var upstreamBody: String? = null
        val origin = rawOrigin { input, out ->
            val lines = buildList {
                while (true) {
                    val line = input.bufferedReadLine() ?: break
                    if (line.isBlank()) break
                    add(line)
                }
            }
            upstreamCoding = lines.firstOrNull { it.startsWith("Content-Encoding:", ignoreCase = true) }
            val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            upstreamBody = String(input.readNBytes(length))
            out.respond("ok")
        }
        val sink = RecordingSink()
        val proxy = proxy(
            sink,
            rules(
                intercepts = { Interception(holdRequest = true) },
                onRequest = { request ->
                    seenBody = request.body.utf8()
                    RequestVerdict.Proceed()
                },
            ),
        )

        proxy.request(
            "POST http://127.0.0.1:${origin.port}/submit HTTP/1.1",
            headers = listOf("Content-Encoding: gzip", "Content-Length: ${compressed.size}"),
            bodyBytes = compressed,
        )

        assertEquals("original", seenBody)
        assertEquals("original", upstreamBody)
        assertNull(upstreamCoding, "a decoded body must not be forwarded as gzip")
        val headers = sink.awaitOne().exchange.request?.headers.orEmpty()
        assertNull(headers.firstOrNull { it.name.equals("Content-Encoding", true) })
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
    fun aChainedRequestGoesThroughTheProxyThisMachineAlreadyHad() {
        val origin = origin { request, out -> out.respond(request.first().trim()) }
        // A second relay standing in for the corporate proxy Wailo took over from. Taking it over must
        // not remove the only route to the network (ADR-0075).
        val corporate = proxy(RecordingSink())
        val sink = RecordingSink()
        val wailo = proxy(sink, chain = ProxyChain { ProxyUpstream("127.0.0.1", corporate.port) })

        val response = wailo.request("GET http://127.0.0.1:${origin.port}/through HTTP/1.1")

        // The origin echoes the request line it received: origin form, because the last hop was the
        // upstream dialling it — which is what proves the request went through the upstream, not around.
        assertTrue(response.contains("GET /through HTTP/1.1"), response)
        assertEquals(1, corporate.exchangeCount, "the upstream must have carried it")
        assertEquals("http://127.0.0.1:${origin.port}/through", sink.awaitOne().exchange.request?.url)
    }

    @Test
    fun aChainedTunnelIsCarriedByTheUpstreamsOwnConnect() {
        val origin = rawOrigin { input, output ->
            output.write(input.readNBytes(5))
            output.flush()
        }
        val corporate = proxy(RecordingSink())
        val sink = RecordingSink()
        val wailo = proxy(sink, chain = ProxyChain { ProxyUpstream("127.0.0.1", corporate.port) })

        Socket().use { client ->
            client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), wailo.port), 2_000)
            client.soTimeout = 5_000
            val out = client.getOutputStream()
            out.write("CONNECT 127.0.0.1:${origin.port} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            out.flush()
            val input = client.getInputStream()
            assertEquals("200", readHead(input)?.second)
            out.write("bytes".toByteArray())
            out.flush()
            assertEquals("bytes", String(input.readNBytes(5)))
        }
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

    @Test
    fun aRuleCanAnswerWithoutReachingTheOrigin() {
        val reached = CountDownLatch(1)
        val origin = origin { _, out ->
            reached.countDown()
            out.respond("from origin")
        }
        val sink = RecordingSink()
        val proxy = proxy(
            sink,
            rules(
                onRequest = {
                    RequestVerdict.Respond(
                        HttpResponse(code = 201, body = "mocked".encodeUtf8(), body_size = 6),
                    )
                },
            ),
        )

        val response = proxy.request("GET http://127.0.0.1:${origin.port}/thing HTTP/1.1")

        assertTrue(response.startsWith("HTTP/1.1 201"), response)
        assertTrue(response.endsWith("mocked"), response)
        assertTrue(!reached.await(300, TimeUnit.MILLISECONDS), "a mocked request must not reach the origin")
        val row = sink.awaitOne()
        assertEquals(201, row.exchange.response?.code)
        assertEquals("mocked", sink.body(row.responseBody))
    }

    @Test
    fun aHeldRequestIsOfferedWholeAndItsEditReachesTheOrigin() {
        val origin = origin { request, out ->
            val length = request.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
            out.respond("got $length")
        }
        var seen: String? = null
        val proxy = proxy(
            RecordingSink(),
            rules(
                intercepts = { Interception(holdRequest = true) },
                onRequest = { request ->
                    seen = request.body.utf8()
                    RequestVerdict.Proceed(request.copy(body = "edited body!".encodeUtf8()))
                },
            ),
        )

        val response = proxy.request(
            "POST http://127.0.0.1:${origin.port}/submit HTTP/1.1",
            headers = listOf("Content-Length: 8"),
            body = "original",
        )

        assertEquals("original", seen)
        assertTrue(response.contains("got 12"), response)
    }

    @Test
    fun aHeldResponseIsOfferedDecodedAndItsEditReachesTheClient() {
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write("secret payload".toByteArray()) }
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
        var seen: String? = null
        val sink = RecordingSink()
        val proxy = proxy(
            sink,
            rules(
                intercepts = { Interception(holdResponse = true) },
                onResponse = { response ->
                    seen = response.body.utf8()
                    ResponseVerdict.Proceed(response.copy(code = 418, body = "rewritten".encodeUtf8()))
                },
            ),
        )

        val response = proxy.request("GET http://127.0.0.1:${origin.port}/gz HTTP/1.1")

        assertEquals("secret payload", seen)
        assertTrue(response.startsWith("HTTP/1.1 418"), response)
        assertTrue(response.endsWith("rewritten"), response)
        assertEquals("rewritten", sink.body(sink.awaitOne().responseBody))
    }

    @Test
    fun anAbortedRequestFailsTheClientVisibly() {
        val origin = origin { _, out -> out.respond("never") }
        val proxy = proxy(RecordingSink(), rules(onRequest = { RequestVerdict.Abort }))

        val response = proxy.request("GET http://127.0.0.1:${origin.port}/x HTTP/1.1")

        assertTrue(response.startsWith("HTTP/1.1 502"), response)
        assertTrue(response.contains("aborted at a Wailo breakpoint"), response)
    }

    @Test
    fun aFilteredExchangeIsStillRelayedButNotRecorded() {
        val origin = origin { _, out -> out.respond("served anyway") }
        val sink = RecordingSink()
        val proxy = proxy(sink, rules(intercepts = { Interception(record = false) }))

        val response = proxy.request("GET http://127.0.0.1:${origin.port}/quiet HTTP/1.1")

        assertTrue(response.contains("served anyway"), response)
        assertTrue(sink.rowCount == 0, "a filtered exchange must not be recorded")
    }

    private fun proxy(
        sink: ProxyCaptureSink,
        rules: ProxyRules = ProxyRules.None,
        chain: ProxyChain = ProxyChain.Direct,
    ): ProxyServer = ProxyServer.start(0, sink, rules, chain = chain).also { closeables += it }

    private fun rules(
        intercepts: (String) -> Interception = { Interception() },
        onRequest: (HttpRequest) -> RequestVerdict = { RequestVerdict.Proceed() },
        onResponse: (HttpResponse) -> ResponseVerdict = { ResponseVerdict.Proceed() },
    ): ProxyRules = object : ProxyRules {
        override fun intercepts(method: String, url: String) = intercepts(url)
        override fun onRequest(client: String, request: HttpRequest) = onRequest(request)
        override fun onResponse(client: String, request: HttpRequest, response: HttpResponse) = onResponse(response)
    }

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
    bodyBytes: ByteArray? = null,
): String = String(requestBytes(startLine, headers, body, bodyBytes))

private fun ProxyServer.requestBytes(
    startLine: String,
    headers: List<String> = emptyList(),
    body: String? = null,
    bodyBytes: ByteArray? = null,
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
    (bodyBytes ?: body?.toByteArray())?.let { out.write(it) }
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

    val rowCount: Int get() = rows.size

    fun awaitOne(): Row {
        assertTrue(recorded.await(5, TimeUnit.SECONDS), "no exchange was recorded")
        return rows.first()
    }

    fun body(ref: ProxyBodyRef?): String = String(bodyBytes(ref))

    fun bodyBytes(ref: ProxyBodyRef?): ByteArray = bodies[assertNotNull(ref).id] ?: ByteArray(0)
}
