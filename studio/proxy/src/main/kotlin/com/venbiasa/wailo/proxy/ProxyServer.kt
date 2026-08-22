package com.venbiasa.wailo.proxy

import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okio.ByteString

/**
 * The bundled HTTP proxy: a listener a client points at, which relays each request to its origin and
 * hands what it saw to a [ProxyCaptureSink] (ADR-0070).
 *
 * One virtual thread per connection, blocking IO throughout. A proxy is almost entirely parked on a
 * socket, which is exactly the shape virtual threads exist for, and blocking reads keep the framing
 * code readable — the alternative is a state machine over callbacks for no gain the user can see.
 *
 * Nothing is buffered whole. Each body is teed as it is relayed, so the client and the origin see the
 * original bytes at their original pace while a copy goes to the spool; a response larger than memory
 * is not a special case (ADR-0069).
 *
 * `CONNECT` is an opaque tunnel — this class never terminates TLS (ADR-0071). A tunnel is still
 * recorded, as a locked row, so the user can tell "not decrypted" from "not captured".
 */
class ProxyServer private constructor(
    private val socket: ServerSocket,
    private val sink: ProxyCaptureSink,
) : Closeable {
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val closed = AtomicBoolean()
    private val liveConnections = AtomicInteger()
    private val relayed = AtomicLong()

    val port: Int get() = socket.localPort

    /** Client connections open right now, which is what the panel shows as "connected clients". */
    val connections: Int get() = liveConnections.get()

    /** Exchanges relayed since this listener started, tunnels included. */
    val exchangeCount: Long get() = relayed.get()

    private fun start() {
        executor.execute(::acceptLoop)
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                return
            } catch (_: IOException) {
                return
            }
            executor.execute {
                liveConnections.incrementAndGet()
                try {
                    serve(client)
                } finally {
                    liveConnections.decrementAndGet()
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun serve(client: Socket) {
        client.tcpNoDelay = true
        val input = BufferedInputStream(client.getInputStream(), RELAY_BUFFER_BYTES)
        val output = BufferedOutputStream(client.getOutputStream(), RELAY_BUFFER_BYTES)
        val peer = (client.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "unknown"
        try {
            while (!closed.get()) {
                val head = readHead(input) ?: return
                val reusable = if (head.first.equals("CONNECT", ignoreCase = true)) {
                    tunnel(head, input, output, peer)
                    false
                } else {
                    relay(head, input, output, peer)
                }
                if (!reusable) return
            }
        } catch (_: IOException) {
            // A client that walks away mid-message is ordinary traffic, not a fault worth surfacing.
        }
    }

    /**
     * Relay one plain-HTTP exchange. Returns whether the client connection can carry another request,
     * which is false whenever the response was framed by its own end — the client has to see the close
     * to know the body finished.
     */
    private fun relay(head: HttpHead, clientIn: InputStream, clientOut: OutputStream, peer: String): Boolean {
        val target = absoluteTarget(head.second)
        if (target == null) {
            respondDirectly(clientOut, 400, "Bad Request", DIRECT_REQUEST_HELP)
            return false
        }
        val startedAt = System.currentTimeMillis()
        val forwardedHeaders = head.headers.withoutHopByHop().withHost(target)
        val upstream = try {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
                soTimeout = READ_TIMEOUT_MS
            }
        } catch (failure: IOException) {
            respondDirectly(clientOut, 502, "Bad Gateway", "Wailo could not reach ${target.host}:${target.port}.")
            record(
                exchange(head, target.url, forwardedHeaders, startedAt, null, 0, false, 0, false)
                    .copy(error = failure.message ?: "upstream connection failed"),
                peer,
                null,
                null,
            )
            return false
        }

        upstream.use {
            val upstreamIn = BufferedInputStream(it.getInputStream(), RELAY_BUFFER_BYTES)
            val upstreamOut = BufferedOutputStream(it.getOutputStream(), RELAY_BUFFER_BYTES)
            upstreamOut.write(requestHead(head, target, forwardedHeaders))
            upstreamOut.flush()
            val request = relayBody(
                source = clientIn,
                destination = upstreamOut,
                framing = requestFraming(head),
                contentEncoding = head.header("Content-Encoding"),
            )
            upstreamOut.flush()

            val responseHead = readHead(upstreamIn)
                ?: throw IOException("Upstream closed before sending a response")
            clientOut.write(responseHead.raw)
            clientOut.flush()
            val framing = responseFraming(head, responseHead)
            val response = relayBody(
                source = upstreamIn,
                destination = clientOut,
                framing = framing,
                contentEncoding = responseHead.header("Content-Encoding"),
            )
            clientOut.flush()

            record(
                exchange(
                    head = head,
                    url = target.url,
                    requestHeaders = forwardedHeaders,
                    startedAt = startedAt,
                    responseHead = responseHead,
                    requestBytes = request.bytes,
                    requestSpoiled = request.spoiled,
                    responseBytes = response.bytes,
                    responseSpoiled = response.spoiled,
                    responseDecoded = response.decoded,
                ),
                peer,
                request.ref,
                response.ref,
            )
            return framing.kind != FramingKind.UntilClose && !head.wantsClose() && !responseHead.wantsClose()
        }
    }

    /**
     * Open an opaque `CONNECT` tunnel. The row is recorded the moment the tunnel is established rather
     * than when it closes: a tunnel can stay open for the length of a session, and a row that appeared
     * only afterwards would leave the user watching an empty list while their traffic flowed.
     */
    private fun tunnel(head: HttpHead, clientIn: InputStream, clientOut: OutputStream, peer: String) {
        val authority = head.second
        val host = authority.substringBeforeLast(':', authority)
        val port = authority.substringAfterLast(':', "443").toIntOrNull() ?: 443
        val startedAt = System.currentTimeMillis()
        val upstream = try {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
        } catch (failure: IOException) {
            respondDirectly(clientOut, 502, "Bad Gateway", "Wailo could not reach $host:$port.")
            record(
                tunnelExchange(host, port, head, startedAt, established = false)
                    .copy(error = failure.message ?: "tunnel connection failed"),
                peer,
                null,
                null,
            )
            return
        }
        upstream.use {
            clientOut.write(TUNNEL_ESTABLISHED)
            clientOut.flush()
            record(tunnelExchange(host, port, head, startedAt, established = true), peer, null, null)
            val upstreamOut = it.getOutputStream()
            executor.execute {
                runCatching { pump(clientIn, upstreamOut) }
                // Half-close rather than close: the origin still owes a response to what was already sent.
                runCatching { it.shutdownOutput() }
            }
            runCatching { pump(it.getInputStream(), clientOut) }
            // Returning ends the tunnel. The outbound pump is deliberately not joined — it is parked on a
            // read from a client that may never speak again, and `serve` closing that socket is what frees
            // it. Waiting here would hang the connection thread for as long as the client stayed silent.
        }
    }

    /**
     * Copy until end of stream, flushing every chunk.
     *
     * The flush is the point. A tunnel carries records that are a reply to something, so bytes left in a
     * buffer waiting for it to fill are a handshake that never completes.
     */
    private fun pump(source: InputStream, destination: OutputStream) {
        val buffer = ByteArray(RELAY_BUFFER_BYTES)
        while (true) {
            val read = source.read(buffer)
            if (read < 0) return
            destination.write(buffer, 0, read)
            destination.flush()
        }
    }

    private enum class FramingKind { None, Chunked, Fixed, UntilClose }

    /**
     * How a body's end is known, and — for `Content-Length` — where. The length travels with the kind so
     * a caller cannot forward a fixed-length body having forgotten to say how long it is.
     */
    private class Framing(val kind: FramingKind, val length: Long = 0)

    private class Relayed(
        val ref: ProxyBodyRef?,
        val bytes: Long,
        val spoiled: Boolean,
        val decoded: Boolean,
    )

    private fun requestFraming(head: HttpHead): Framing = when {
        head.isChunked() -> Framing(FramingKind.Chunked)
        (head.contentLength() ?: 0L) > 0L -> Framing(FramingKind.Fixed, head.contentLength()!!)
        else -> Framing(FramingKind.None)
    }

    private fun responseFraming(request: HttpHead, response: HttpHead): Framing {
        val code = response.second.toIntOrNull() ?: 0
        val bodyless = request.first.equals("HEAD", ignoreCase = true) ||
            code == 204 || code == 304 || code in 100..199
        val length = response.contentLength()
        return when {
            bodyless -> Framing(FramingKind.None)
            response.isChunked() -> Framing(FramingKind.Chunked)
            length != null -> Framing(FramingKind.Fixed, length)
            else -> Framing(FramingKind.UntilClose)
        }
    }

    private fun relayBody(
        source: InputStream,
        destination: OutputStream,
        framing: Framing,
        contentEncoding: String? = null,
    ): Relayed {
        if (framing.kind == FramingKind.None) return Relayed(null, 0, spoiled = false, decoded = false)
        val echo = EchoInputStream(source, destination)
        val entity = when (framing.kind) {
            FramingKind.Chunked -> ChunkedInputStream(echo)
            FramingKind.Fixed -> FixedLengthInputStream(echo, framing.length)
            else -> echo
        }
        val capture = if (sink.isRecording()) sink.openBody() else null
        val decoder = if (capture == null) entity else decodedForCapture(entity, contentEncoding)
        var copied = 0L
        var spoiled = false
        try {
            val buffer = ByteArray(RELAY_BUFFER_BYTES)
            while (true) {
                val read = decoder.read(buffer)
                if (read < 0) break
                capture?.write(buffer, 0, read)
                copied += read
            }
        } catch (_: IOException) {
            // The entity is still owed to the far side even when the *inspection* copy fails, which a
            // corrupt Content-Encoding is enough to cause. Whatever was decoded is a partial body.
            spoiled = true
        }
        // Anything the decoder did not pull through still has to reach the client, and draining the tee
        // is what sends it.
        runCatching { entity.drain() }
        if (capture == null) return Relayed(null, copied, spoiled, decoded = false)
        return try {
            val ref = if (spoiled && copied == 0L) null else capture.commit()
            Relayed(ref, copied, spoiled, decoded = decoder !== entity)
        } catch (_: Exception) {
            capture.close()
            Relayed(null, copied, spoiled = true, decoded = false)
        }
    }

    private fun record(
        exchange: HttpExchange,
        client: String,
        requestBody: ProxyBodyRef?,
        responseBody: ProxyBodyRef?,
    ) {
        relayed.incrementAndGet()
        runCatching { sink.record(exchange, client, requestBody, responseBody) }
    }

    private fun exchange(
        head: HttpHead,
        url: String,
        requestHeaders: List<Header>,
        startedAt: Long,
        responseHead: HttpHead?,
        requestBytes: Long,
        requestSpoiled: Boolean,
        responseBytes: Long,
        responseSpoiled: Boolean,
        responseDecoded: Boolean = false,
    ) = HttpExchange(
        id = UUID.randomUUID().toString(),
        started_at_epoch_ms = startedAt,
        duration_ms = System.currentTimeMillis() - startedAt,
        request = HttpRequest(
            method = head.first,
            url = url,
            headers = requestHeaders,
            body = ByteString.EMPTY,
            body_size = requestBytes,
            body_truncated = requestSpoiled,
        ),
        response = responseHead?.let {
            HttpResponse(
                code = it.second.toIntOrNull() ?: 0,
                message = it.third,
                headers = it.headers.withoutHopByHop().asCaptured(responseDecoded, responseBytes),
                body = ByteString.EMPTY,
                body_size = responseBytes,
                body_truncated = responseSpoiled,
            )
        },
    )

    private fun tunnelExchange(
        host: String,
        port: Int,
        head: HttpHead,
        startedAt: Long,
        established: Boolean,
    ) = HttpExchange(
        id = UUID.randomUUID().toString(),
        started_at_epoch_ms = startedAt,
        duration_ms = System.currentTimeMillis() - startedAt,
        request = HttpRequest(
            method = "CONNECT",
            url = "https://$host:$port",
            headers = head.headers.withoutHopByHop(),
            body = ByteString.EMPTY,
            body_size = 0,
        ),
        response = if (!established) {
            null
        } else {
            HttpResponse(
                code = 200,
                message = TUNNEL_LOCKED_MESSAGE,
                body = ByteString.EMPTY,
                body_size = 0,
            )
        },
    )

    private fun respondDirectly(output: OutputStream, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray()
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.write(bytes)
            output.flush()
        }
    }

    private fun requestHead(head: HttpHead, target: ProxyTarget, headers: List<Header>): ByteArray = buildString {
        append("${head.first} ${target.pathAndQuery} ${head.third}\r\n")
        headers.forEach { append("${it.name}: ${it.value_}\r\n") }
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        executor.shutdownNow()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000

        /**
         * Long enough to sit through a slow endpoint but not forever: a client that hangs on a dead
         * origin cannot tell the difference between that and a broken proxy.
         */
        private const val READ_TIMEOUT_MS = 120_000

        private const val TUNNEL_LOCKED_MESSAGE = "Connection Established (encrypted, not decrypted)"

        private val TUNNEL_ESTABLISHED =
            "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        private const val DIRECT_REQUEST_HELP =
            "This is the Wailo proxy port. Set it as your HTTP proxy rather than browsing to it."

        /** Loopback only for now: exposing a proxy to the LAN is an explicit, separate decision. */
        fun start(port: Int, sink: ProxyCaptureSink): ProxyServer {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            return ProxyServer(socket, sink).also(ProxyServer::start)
        }
    }
}

internal class ProxyTarget(val host: String, val port: Int, val pathAndQuery: String, val url: String)

/**
 * A proxied request carries the whole URL in its start line; an origin-form target means something
 * connected to this port expecting an ordinary web server.
 */
internal fun absoluteTarget(target: String): ProxyTarget? {
    val uri = runCatching { URI(target) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    if (uri.scheme?.equals("http", ignoreCase = true) != true) return null
    val port = if (uri.port > 0) uri.port else 80
    val path = buildString {
        append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
        uri.rawQuery?.let { append("?$it") }
    }
    return ProxyTarget(host, port, path, target)
}

/**
 * Replace, rather than keep, whatever `Host` the client sent: with an absolute-form target the authority
 * in the URL is the authority, and RFC 9112 has the proxy overwrite the header to match it. Forwarding a
 * mismatched one sends a virtual-host origin to the wrong site.
 */
private fun List<Header>.withHost(target: ProxyTarget): List<Header> =
    listOf(Header("Host", hostHeader(target))) + filterNot { it.name.equals("Host", ignoreCase = true) }

private fun hostHeader(target: ProxyTarget): String =
    if (target.port == 80) target.host else "${target.host}:${target.port}"

/**
 * The headers to record beside a body we decoded for inspection. Leaving `Content-Encoding` on a body
 * that is no longer encoded — next to a `Content-Length` describing the compressed bytes — would make
 * the recorded exchange describe something that never existed.
 */
private fun List<Header>.asCaptured(decoded: Boolean, capturedBytes: Long): List<Header> {
    if (!decoded) return this
    return filterNot {
        it.name.equals("Content-Encoding", ignoreCase = true) ||
            it.name.equals("Content-Length", ignoreCase = true)
    } + Header("Content-Length", capturedBytes.toString())
}

private fun InputStream.drain() {
    val scratch = ByteArray(RELAY_BUFFER_BYTES)
    while (read(scratch) >= 0) Unit
}
