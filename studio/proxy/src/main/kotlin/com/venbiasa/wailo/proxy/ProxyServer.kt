package com.venbiasa.wailo.proxy

import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * The bundled HTTP proxy: a listener a client points at, which relays each request to its origin and
 * hands what it saw to a [ProxyCaptureSink] (ADR-0070).
 *
 * One virtual thread per connection, blocking IO throughout. A proxy is almost entirely parked on a
 * socket, which is exactly the shape virtual threads exist for, and blocking reads keep the framing
 * code readable — the alternative is a state machine over callbacks for no gain the user can see.
 *
 * Nothing is buffered whole unless a [ProxyRules] hold says otherwise. Each body is teed as it is
 * relayed, so the client and the origin see the original bytes at their original pace while a copy goes
 * to the spool; a response larger than memory is not a special case (ADR-0069).
 *
 * `CONNECT` is an opaque tunnel — this class never terminates TLS (ADR-0071). A tunnel is still
 * recorded, as a locked row, so the user can tell "not decrypted" from "not captured".
 */
class ProxyServer private constructor(
    private val socket: ServerSocket,
    private val sink: ProxyCaptureSink,
    private val rules: ProxyRules,
    private val tls: ProxyTls,
    private val chain: ProxyChain,
    private val setup: ProxySetup,
) : Closeable {
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val closed = AtomicBoolean()
    private val liveConnections = AtomicInteger()
    private val relayed = AtomicLong()
    private val liveSockets = ConcurrentHashMap.newKeySet<Socket>()

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
                liveSockets += client
                try {
                    serve(client)
                } finally {
                    liveSockets -= client
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
                    connect(client, head, input, output, peer)
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
     * Answer a `CONNECT`: decrypt it if the daemon unlocked this host, otherwise tunnel it opaquely.
     *
     * Locked is the default and the failure mode — anything that goes wrong presenting a leaf leaves the
     * connection encrypted end to end rather than broken (ADR-0071).
     */
    private fun connect(client: Socket, head: HttpHead, clientIn: InputStream, clientOut: OutputStream, peer: String) {
        val authority = head.second
        val host = authority.substringBeforeLast(':', authority)
        val port = authority.substringAfterLast(':', "443").toIntOrNull() ?: 443
        val context = runCatching { tls.unlock(host) }.getOrNull()
        if (context == null) {
            tunnel(host, port, head, clientIn, clientOut, peer)
            return
        }
        clientOut.write(TUNNEL_ESTABLISHED)
        clientOut.flush()
        val secured = try {
            // Whatever the buffer already pulled off the socket has to be handed to the TLS layer, or a
            // client that pipelined its ClientHello behind the CONNECT would hang on a lost handshake.
            val pending = clientIn.available()
            val consumed = if (pending > 0) ByteArrayInputStream(clientIn.readNBytes(pending)) else null
            context.socketFactory.createSocket(client, consumed, false) as SSLSocket
        } catch (_: IOException) {
            return
        }
        secured.useClientMode = false
        secured.soTimeout = 0
        val inner = BufferedInputStream(secured.inputStream, RELAY_BUFFER_BYTES)
        val innerOut = BufferedOutputStream(secured.outputStream, RELAY_BUFFER_BYTES)
        val tunnelTo = ProxyTarget(host, port, "/", "https://$authority", secure = true)
        try {
            while (!closed.get()) {
                val request = readHead(inner) ?: return
                if (!relay(request, inner, innerOut, peer, tunnelTo)) return
            }
        } catch (_: IOException) {
            // Same as plain HTTP: a client hanging up mid-message is traffic, not a fault.
        } finally {
            runCatching { secured.close() }
        }
    }

    /**
     * Relay one plain-HTTP exchange. Returns whether the client connection can carry another request,
     * which is false whenever the response was framed by its own end — the client has to see the close
     * to know the body finished.
     */
    private fun relay(
        head: HttpHead,
        clientIn: InputStream,
        clientOut: OutputStream,
        peer: String,
        tunnel: ProxyTarget? = null,
    ): Boolean {
        // Inside a decrypted tunnel the request-target is origin-form and the authority is the tunnel's,
        // which is the one thing that differs from a plain proxied request.
        val target = tunnel?.within(head.second) ?: absoluteTarget(head.second)
        if (target == null) {
            // Someone typed this address into a browser. Not recorded: a page Wailo serves about itself is
            // not traffic the user came here to inspect.
            val page = if (head.first.equals("GET", ignoreCase = true)) {
                runCatching { setup.page(head.second) }.getOrNull()
            } else {
                null
            }
            if (page != null) writeResponse(clientOut, page) else respondDirectly(clientOut, 400, "Bad Request", DIRECT_REQUEST_HELP)
            return false
        }
        if (head.first.equals("GET", ignoreCase = true)) {
            val page = runCatching { setup.page(target.url) }.getOrNull()
            if (page != null) {
                writeResponse(clientOut, page)
                return false
            }
        }
        val startedAt = System.currentTimeMillis()
        val upgradeRequested = head.requestsUpgrade()
        val forwardedHeaders = head.headers.withoutHopByHop(preserveUpgrade = upgradeRequested).withHost(target)
        val intent = runCatching { rules.intercepts(head.first, target.url) }.getOrDefault(Interception())
        val requestFrame = requestFraming(head)
        val expectsContinue = head.expectsContinue() && requestFrame.kind != FramingKind.None
        if (expectsContinue) {
            clientOut.write(CONTINUE_RESPONSE)
            clientOut.flush()
        }
        // A hold exists to show a body and take an edit back, so it decodes what it reads — and declines
        // outright for a coding it cannot undo, rather than offering compressed bytes as the body. The
        // entity then stays in the socket for the streaming relay, which records it as it arrived.
        val requestCoding = head.header("Content-Encoding")
        val scriptRequestBody = intent.transformRequest &&
            requestCoding == null &&
            (requestFrame.kind == FramingKind.None ||
                (requestFrame.kind == FramingKind.Fixed && requestFrame.length <= MAX_SCRIPT_BODY_BYTES))
        val held = if (
            (intent.holdRequest && canDecodeForCapture(requestCoding)) ||
            scriptRequestBody
        ) {
            hold(clientIn, requestFrame, requestCoding)
        } else {
            null
        }

        var request = HttpRequest(
            method = head.first,
            url = target.url,
            // A held body was decoded, so the coding header no longer describes it. Dropping it here also
            // corrects what is forwarded: `sendRequest` sends these headers with the bytes the hold left.
            headers = if (held != null) forwardedHeaders.withoutContentEncoding() else forwardedHeaders,
            body = held?.bytes?.toByteString() ?: ByteString.EMPTY,
            body_size = held?.bytes?.size?.toLong() ?: requestFrame.length,
            // A body that outgrew the hold ceiling is offered as a fact, not an editing surface: the
            // daemon reads this and declines to stop an exchange it could only half show.
            body_truncated = held?.complete == false ||
                (intent.transformRequest && requestFrame.kind != FramingKind.None && held == null),
        )

        var requestBodyReplaced = held != null && requestCoding != null
        when (val verdict = runCatching { rules.onRequest(peer, request) }.getOrDefault(RequestVerdict.Proceed())) {
            is RequestVerdict.Abort -> {
                val consumed = consumeRequest(held, clientIn, requestFrame, intent, requestCoding)
                request = request.recordedAfter(consumed)
                respondDirectly(clientOut, 502, "Bad Gateway", ABORTED_MESSAGE)
                record(
                    intent,
                    exchange(request, startedAt, null, consumed.bytes, consumed.spoiled, 0, false)
                        .copy(error = "aborted in Wailo"),
                    peer,
                    consumed.ref,
                    null,
                )
                return false
            }

            is RequestVerdict.Respond -> {
                val consumed = consumeRequest(held, clientIn, requestFrame, intent, requestCoding)
                request = request.recordedAfter(consumed)
                val answered = writeResponse(clientOut, verdict.response)
                record(
                    intent,
                    exchange(request, startedAt, answered, consumed.bytes, consumed.spoiled, answered.body_size, false),
                    peer,
                    consumed.ref,
                    spool(intent, verdict.response.body.toByteArray()),
                )
                // A body too large to have been read whole left the stream mid-entity, so this connection
                // is no longer on a message boundary and cannot carry another request.
                return !consumed.spoiled && !head.wantsClose()
            }

            is RequestVerdict.Proceed -> {
                verdict.edited?.let { request = it }
                requestBodyReplaced = requestBodyReplaced || verdict.bodyReplaced
            }
        }

        val upstream = try {
            openUpstream(target)
        } catch (failure: IOException) {
            respondDirectly(clientOut, 502, "Bad Gateway", "Wailo could not reach ${target.host}:${target.port}.")
            record(
                intent,
                exchange(request, startedAt, null, 0, false, 0, false)
                    .copy(error = failure.message ?: "upstream connection failed"),
                peer,
                null,
                null,
            )
            return false
        }

        liveSockets += upstream.socket
        upstream.socket.use {
            try {
                val upstreamIn = BufferedInputStream(it.getInputStream(), RELAY_BUFFER_BYTES)
                val upstreamOut = BufferedOutputStream(it.getOutputStream(), RELAY_BUFFER_BYTES)
                val route = target.takeUnless { _ -> upstream.absoluteForm } ?: target.asAbsoluteForm()
                val outboundRequest = if (expectsContinue) {
                    request.copy(headers = request.headers.withoutContinueExpectation())
                } else {
                    request
                }
                val sent = sendRequest(
                    upstreamOut,
                    head,
                    route,
                    outboundRequest,
                    held,
                    requestFrame,
                    clientIn,
                    intent,
                    requestBodyReplaced,
                )
                upstreamOut.flush()
                // The head upstream is already written, so this only corrects the record — the client's
                // own framing and coding reached the origin untouched.
                request = request.recordedAfter(sent)

                var responseHead = readHead(upstreamIn)
                    ?: throw IOException("Upstream closed before sending a response")
                while (responseHead.isInformational() && !responseHead.switchesProtocol()) {
                    if (!(expectsContinue && responseHead.second.toIntOrNull() == 100)) {
                        clientOut.write(responseHead.raw)
                        clientOut.flush()
                    }
                    responseHead = readHead(upstreamIn)
                        ?: throw IOException("Upstream closed before sending a final response")
                }
                if (responseHead.switchesProtocol()) {
                    clientOut.write(responseHead.raw)
                    clientOut.flush()
                    record(
                        intent,
                        exchange(
                            request = request,
                            startedAt = startedAt,
                            response = responseHead.asResponse(0, spoiled = false, decoded = false),
                            requestBytes = sent.bytes,
                            requestSpoiled = sent.spoiled,
                            responseBytes = 0,
                            responseSpoiled = false,
                        ),
                        peer,
                        sent.ref,
                        null,
                    )
                    upstream.socket.soTimeout = 0
                    bridgeUpgrade(clientIn, clientOut, upstream.socket, upstreamIn, upstreamOut)
                    return false
                }
                val framing = responseFraming(head, responseHead)
                // A response that says up front it is past the hold ceiling is relayed as usual, rather than
                // read to the ceiling only to give up there. A coding this cannot undo is declined for the
                // reason a request hold is: the pane would show compressed bytes, and delivering them after
                // the hold means stripping the header that explained them (`framedFor`) or re-compressing
                // an edit to match it. Relayed instead, it is recorded honestly and reaches the client
                // byte-exact.
                val holdable = (framing.kind != FramingKind.Fixed || framing.length <= MAX_HELD_BODY_BYTES) &&
                    canDecodeForCapture(responseHead.header("Content-Encoding"))
                val scriptHoldable = intent.transformResponse &&
                    responseHead.header("Content-Encoding") == null &&
                    !responseHead.isEventStream() &&
                    (framing.kind == FramingKind.None ||
                        (framing.kind == FramingKind.Fixed && framing.length <= MAX_SCRIPT_BODY_BYTES))
                if ((intent.holdResponse && holdable) || scriptHoldable) {
                    return holdResponse(
                        head,
                        request,
                        responseHead,
                        framing,
                        upstreamIn,
                        clientOut,
                        peer,
                        startedAt,
                        sent,
                        intent,
                    )
                }

                if (
                    framing.kind == FramingKind.Chunked ||
                    framing.kind == FramingKind.UntilClose ||
                    responseHead.isEventStream()
                ) {
                    upstream.socket.soTimeout = 0
                }
                var responseTemplate: HttpResponse? = null
                if (intent.transformResponse) {
                    val unavailable = responseHead.asResponse(
                        bytes = framing.length,
                        spoiled = framing.kind != FramingKind.None,
                        decoded = false,
                    )
                    when (
                        val verdict = runCatching { rules.onResponse(peer, request, unavailable) }
                            .getOrDefault(ResponseVerdict.Proceed())
                    ) {
                        ResponseVerdict.Abort -> {
                            respondDirectly(clientOut, 502, "Bad Gateway", ABORTED_MESSAGE)
                            return false
                        }
                        is ResponseVerdict.Proceed -> {
                            responseTemplate = verdict.edited?.let { edited ->
                                edited.copy(
                                    body = ByteString.EMPTY,
                                    headers = edited.headers.preserveFramingFrom(unavailable.headers),
                                )
                            } ?: unavailable
                        }
                    }
                }
                clientOut.write(responseTemplate?.headBytes(responseHead.third) ?: responseHead.raw)
                clientOut.flush()
                val response = relayBody(
                    source = upstreamIn,
                    destination = clientOut,
                    framing = framing,
                    record = intent.record,
                    contentEncoding = responseHead.header("Content-Encoding"),
                )
                clientOut.flush()

                record(
                    intent,
                    exchange(
                        request = request,
                        startedAt = startedAt,
                        response = responseTemplate?.copy(
                            body_size = response.bytes,
                            body_truncated = response.spoiled,
                        ) ?: responseHead.asResponse(response.bytes, response.spoiled, response.decoded),
                        requestBytes = sent.bytes,
                        requestSpoiled = sent.spoiled,
                        responseBytes = response.bytes,
                        responseSpoiled = response.spoiled,
                    ),
                    peer,
                    sent.ref,
                    response.ref,
                )
                return framing.kind != FramingKind.UntilClose && !head.wantsClose() && !responseHead.wantsClose()
            } finally {
                liveSockets -= upstream.socket
            }
        }
    }

    /**
     * Park a whole response in front of the client so a human (or a seed) can rewrite it before it is
     * delivered, then send whatever came back (ADR-0067).
     *
     * The body is decoded first and forwarded decoded, with the framing headers recomputed. An editor
     * cannot usefully show gzip, and re-compressing an edited body only to have the client inflate it
     * again would be work done to hide the fact that the exchange was stopped.
     *
     * Only reached for a coding the capture can actually undo — the hold gate declines the rest — which is
     * what makes the decoded framing claimed below true rather than merely asserted.
     */
    private fun holdResponse(
        head: HttpHead,
        request: HttpRequest,
        responseHead: HttpHead,
        framing: Framing,
        upstreamIn: InputStream,
        clientOut: OutputStream,
        peer: String,
        startedAt: Long,
        sent: Relayed,
        intent: Interception,
    ): Boolean {
        val encoding = responseHead.header("Content-Encoding")
        val body = hold(upstreamIn, framing, encoding)
        if (!body.complete) {
            return releaseOversized(head, request, responseHead, body, upstreamIn, clientOut, peer, startedAt, sent, intent)
        }
        val original = responseHead.asResponse(body.bytes.size.toLong(), spoiled = false, decoded = encoding != null)
            .copy(body = body.bytes.toByteString())
        val verdict = runCatching { rules.onResponse(peer, request, original) }
            .getOrDefault(ResponseVerdict.Proceed())
        if (verdict is ResponseVerdict.Abort) {
            respondDirectly(clientOut, 502, "Bad Gateway", ABORTED_MESSAGE)
            record(intent, exchange(request, startedAt, original, sent.bytes, sent.spoiled, body.bytes.size.toLong(), false)
                .copy(error = "aborted in Wailo"), peer, sent.ref, null)
            return false
        }
        val chosen = (verdict as ResponseVerdict.Proceed).edited ?: original
        val delivered = writeResponse(clientOut, chosen)
        record(
            intent,
            exchange(request, startedAt, delivered, sent.bytes, sent.spoiled, delivered.body_size, false),
            peer,
            sent.ref,
            spool(intent, chosen.body.toByteArray()),
        )
        return !head.wantsClose() && !responseHead.wantsClose()
    }

    /**
     * Deliver a response that turned out to be too large to hold, having already read part of it.
     *
     * Re-framed as chunked because the part in hand has been decoded and the rest has not been measured,
     * so neither the origin's `Content-Length` nor its `Transfer-Encoding` describes what is now being
     * sent. Truncating to what was buffered would silently corrupt the response, and failing it would
     * break a page to enforce a breakpoint that had already declined to fire.
     */
    private fun releaseOversized(
        head: HttpHead,
        request: HttpRequest,
        responseHead: HttpHead,
        buffered: Held,
        upstreamIn: InputStream,
        clientOut: OutputStream,
        peer: String,
        startedAt: Long,
        sent: Relayed,
        intent: Interception,
    ): Boolean {
        clientOut.write(
            buildString {
                append("HTTP/1.1 ${responseHead.second} ${responseHead.third}\r\n")
                responseHead.headers.withoutHopByHop().chunkedFraming()
                    .forEach { append("${it.name}: ${it.value_}\r\n") }
                append("\r\n")
            }.toByteArray(Charsets.ISO_8859_1),
        )
        val capture = if (intent.record && sink.isRecording()) sink.openBody() else null
        val written = runCatching { writeChunked(clientOut, buffered.bytes, buffered.rest, capture) }
        val total = written.getOrDefault(buffered.bytes.size.toLong())
        record(
            intent,
            exchange(
                request,
                startedAt,
                responseHead.asResponse(total, written.isFailure, decoded = true),
                sent.bytes,
                sent.spoiled,
                total,
                written.isFailure,
            ),
            peer,
            sent.ref,
            runCatching { capture?.commit() }.getOrNull(),
        )
        return false
    }

    /**
     * Write the request head and body upstream, using whatever the hold left behind.
     *
     * An edited body is sent with a recomputed `Content-Length` and no `Transfer-Encoding`: it is a new
     * entity, and the framing the client chose describes bytes that are no longer being sent.
     */
    private fun sendRequest(
        upstreamOut: OutputStream,
        head: HttpHead,
        target: ProxyTarget,
        request: HttpRequest,
        held: Held?,
        framing: Framing,
        clientIn: InputStream,
        intent: Interception,
        bodyReplaced: Boolean,
    ): Relayed {
        // Only a hold can have produced an edit, so with nothing held the body is still in the socket and
        // streaming it is both correct and the only way to leave the connection on a message boundary.
        if (held == null) {
            upstreamOut.write(requestHead(head, target, request.headers))
            upstreamOut.flush()
            return relayBody(
                source = clientIn,
                destination = upstreamOut,
                framing = framing,
                record = intent.record,
                contentEncoding = head.header("Content-Encoding"),
            )
        }
        val bytes = if (bodyReplaced) request.body.toByteArray() else held.bytes
        if (held.complete) {
            val headers = if (bodyReplaced) {
                request.headers.framedFor(bytes.size.toLong(), complete = true)
            } else {
                request.headers
            }
            upstreamOut.write(requestHead(head, target, headers))
            upstreamOut.write(bytes)
            return Relayed(spool(intent, bytes), bytes.size.toLong(), spoiled = false, decoded = false)
        }
        // Everything past the ceiling still belongs to the origin, and the stream is mid-entity — so the
        // rest is finished from the reader the hold left behind, re-framed because its length is unknown.
        upstreamOut.write(requestHead(head, target, request.headers.chunkedFraming()))
        val capture = if (intent.record && sink.isRecording()) sink.openBody() else null
        val written = runCatching { writeChunked(upstreamOut, bytes, held.rest, capture) }
        return Relayed(
            ref = runCatching { capture?.commit() }.getOrNull(),
            bytes = written.getOrDefault(bytes.size.toLong()),
            spoiled = written.isFailure,
            decoded = false,
        )
    }

    /**
     * Write [first] and then the remainder of [rest] as one chunked body, mirroring both into [capture].
     * Flushed per chunk, since the far side is waiting on bytes rather than on a buffer filling.
     */
    private fun writeChunked(
        output: OutputStream,
        first: ByteArray,
        rest: InputStream?,
        capture: ProxyBodySink?,
    ): Long {
        var total = 0L
        val emit = { chunk: ByteArray, length: Int ->
            if (length > 0) {
                output.write("${length.toString(16)}\r\n".toByteArray(Charsets.ISO_8859_1))
                output.write(chunk, 0, length)
                output.write(CRLF)
                output.flush()
                capture?.write(chunk, 0, length)
                total += length
            }
        }
        try {
            emit(first, first.size)
            val scratch = ByteArray(RELAY_BUFFER_BYTES)
            while (rest != null) {
                val read = rest.read(scratch)
                if (read < 0) break
                emit(scratch, read)
            }
        } finally {
            // The terminator goes out even on a failed read: without it the far side waits for a body
            // that has already stopped coming.
            runCatching {
                output.write(LAST_CHUNK)
                output.flush()
            }
        }
        return total
    }

    /**
     * The connection to the origin: TLS inside a decrypted tunnel, plain otherwise. Validation is the
     * JDK's default, so Wailo refuses an origin a browser would have refused — decrypting a user's own
     * traffic must not also quietly stop checking who is on the other end.
     */
    private fun openUpstream(target: ProxyTarget): Upstream {
        val via = chain.route(target.host)
        // A plain request to a proxy is written in absolute form and sent to the proxy's own socket, so
        // there is nothing to tunnel — only the request line changes.
        if (via != null && !target.secure) return Upstream(dial(via.host, via.port), absoluteForm = true)
        val socket = if (via == null) dial(target.host, target.port) else tunnelThrough(via, target.host, target.port)
        if (!target.secure) return Upstream(socket, absoluteForm = false)
        return runCatching {
            tls.upstream().socketFactory
                .createSocket(socket, target.host, target.port, true)
                .also { (it as SSLSocket).startHandshake() }
        }.getOrElse {
            runCatching { socket.close() }
            throw IOException("TLS handshake with ${target.host} failed: ${it.message}", it)
        }.let { Upstream(it, absoluteForm = false) }
    }

    private fun dial(host: String, port: Int): Socket = Socket().apply {
        tcpNoDelay = true
        connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        soTimeout = READ_TIMEOUT_MS
    }

    /** A raw path to `host:port` carried by [via]'s own `CONNECT`, for TLS Wailo will not be reading. */
    private fun tunnelThrough(via: ProxyUpstream, host: String, port: Int): Socket {
        val socket = dial(via.host, via.port)
        return try {
            socket.getOutputStream().apply {
                write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val answer = readHead(socket.getInputStream()) ?: throw EOFException("no answer")
            val code = answer.second.toIntOrNull()
            if (code != 200) throw IOException("refused CONNECT to $host:$port with $code")
            socket
        } catch (failure: IOException) {
            runCatching { socket.close() }
            throw IOException("upstream proxy ${via.host}:${via.port} ${failure.message}", failure)
        }
    }

    /** A route to the origin, and whether it needs the request line an HTTP proxy expects. */
    private class Upstream(val socket: Socket, val absoluteForm: Boolean)

    /**
     * Open an opaque `CONNECT` tunnel. The row is recorded the moment the tunnel is established rather
     * than when it closes: a tunnel can stay open for the length of a session, and a row that appeared
     * only afterwards would leave the user watching an empty list while their traffic flowed.
     */
    private fun tunnel(
        host: String,
        port: Int,
        head: HttpHead,
        clientIn: InputStream,
        clientOut: OutputStream,
        peer: String,
    ) {
        val startedAt = System.currentTimeMillis()
        val intent = runCatching { rules.intercepts("CONNECT", "https://$host:$port") }
            .getOrDefault(Interception())
        val upstream = try {
            chain.route(host)?.let { tunnelThrough(it, host, port) } ?: dial(host, port)
        } catch (failure: IOException) {
            respondDirectly(clientOut, 502, "Bad Gateway", "Wailo could not reach $host:$port.")
            record(
                intent,
                tunnelExchange(host, port, head, startedAt, established = false)
                    .copy(error = failure.message ?: "tunnel connection failed"),
                peer,
                null,
                null,
            )
            return
        }
        liveSockets += upstream
        upstream.use {
            try {
                upstream.soTimeout = 0
                clientOut.write(TUNNEL_ESTABLISHED)
                clientOut.flush()
                record(intent, tunnelExchange(host, port, head, startedAt, established = true), peer, null, null)
                val upstreamOut = it.getOutputStream()
                executor.execute {
                    runCatching { pump(clientIn, upstreamOut) }
                    // The origin may still owe a response to bytes already sent.
                    runCatching { it.shutdownOutput() }
                }
                runCatching { pump(it.getInputStream(), clientOut) }
                // Closing the client socket releases the unjoined outbound pump if its read is still parked.
            } finally {
                liveSockets -= upstream
            }
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

    private fun bridgeUpgrade(
        clientIn: InputStream,
        clientOut: OutputStream,
        upstream: Socket,
        upstreamIn: InputStream,
        upstreamOut: OutputStream,
    ) {
        executor.execute {
            runCatching { pump(clientIn, upstreamOut) }
            runCatching { upstream.shutdownOutput() }
        }
        runCatching { pump(upstreamIn, clientOut) }
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

    /**
     * The request as it should be recorded now that its body has been captured.
     *
     * Capture decodes what it can, so a decoded body has outlived its `Content-Encoding`. Dropping the
     * header is what lets a reader trust the pair: a surviving coding means the stored bytes really are
     * still under it, which is how a viewer tells a body it cannot read from one it garbled.
     */
    private fun HttpRequest.recordedAfter(captured: Relayed): HttpRequest =
        if (captured.decoded) copy(headers = headers.withoutContentEncoding()) else this

    /**
     * An entity read into memory for a hold. [complete] is false once it outgrew [MAX_HELD_BODY_BYTES],
     * in which case [rest] is the same stream, positioned after [bytes] — the only way to finish sending
     * a body whose framing has already been consumed this far.
     */
    private class Held(val bytes: ByteArray, val complete: Boolean, val rest: InputStream? = null)

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

    private fun HttpHead.isInformational(): Boolean =
        second.toIntOrNull()?.let { it in 100..199 } == true

    private fun HttpHead.switchesProtocol(): Boolean = second.toIntOrNull() == 101

    private fun HttpHead.isEventStream(): Boolean =
        header("Content-Type")?.substringBefore(';')?.trim()?.equals("text/event-stream", ignoreCase = true) == true

    private fun entityOf(source: InputStream, framing: Framing): InputStream = when (framing.kind) {
        FramingKind.Chunked -> ChunkedInputStream(source)
        FramingKind.Fixed -> FixedLengthInputStream(source, framing.length)
        else -> source
    }

    private fun hold(source: InputStream, framing: Framing, contentEncoding: String? = null): Held {
        if (framing.kind == FramingKind.None) return Held(ByteArray(0), complete = true)
        val decoder = decodedForCapture(entityOf(source, framing), contentEncoding)
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(RELAY_BUFFER_BYTES)
        while (collected.size() <= MAX_HELD_BODY_BYTES) {
            val read = try {
                decoder.read(buffer)
            } catch (_: IOException) {
                return Held(collected.toByteArray(), complete = false)
            }
            if (read < 0) return Held(collected.toByteArray(), complete = true)
            collected.write(buffer, 0, read)
        }
        return Held(collected.toByteArray(), complete = false, rest = decoder)
    }

    /**
     * Take the request body of an exchange being answered here rather than forwarded. It is still read
     * and still captured — the origin is what is being skipped, not the request — which both records
     * what a Map Local rule was asked and leaves the connection on a message boundary.
     */
    private fun consumeRequest(
        held: Held?,
        source: InputStream,
        framing: Framing,
        intent: Interception,
        contentEncoding: String?,
    ): Relayed {
        if (held == null) return relayBody(source, null, framing, intent.record, contentEncoding)
        return Relayed(spool(intent, held.bytes), held.bytes.size.toLong(), !held.complete, decoded = false)
    }

    /** A null [destination] drains the entity instead of forwarding it, which is what answering here needs. */
    private fun relayBody(
        source: InputStream,
        destination: OutputStream?,
        framing: Framing,
        record: Boolean,
        contentEncoding: String? = null,
    ): Relayed {
        if (framing.kind == FramingKind.None) return Relayed(null, 0, spoiled = false, decoded = false)
        val entity = entityOf(EchoInputStream(source, destination), framing)
        val capture = if (record && sink.isRecording()) sink.openBody() else null
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

    private fun spool(intent: Interception, bytes: ByteArray?): ProxyBodyRef? {
        if (bytes == null || bytes.isEmpty() || !intent.record || !sink.isRecording()) return null
        val body = sink.openBody()
        return try {
            body.write(bytes, 0, bytes.size)
            body.commit()
        } catch (_: Exception) {
            body.close()
            null
        }
    }

    private fun record(
        intent: Interception,
        exchange: HttpExchange,
        client: String,
        requestBody: ProxyBodyRef?,
        responseBody: ProxyBodyRef?,
    ) {
        relayed.incrementAndGet()
        // A filtered-out exchange is still relayed and still interceptable; the filter decides what is
        // kept, not what is allowed through (ADR-0067).
        if (!intent.record) return
        runCatching { sink.record(exchange, client, requestBody, responseBody) }
    }

    private fun exchange(
        request: HttpRequest,
        startedAt: Long,
        response: HttpResponse?,
        requestBytes: Long,
        requestSpoiled: Boolean,
        responseBytes: Long,
        responseSpoiled: Boolean,
    ) = HttpExchange(
        id = UUID.randomUUID().toString(),
        started_at_epoch_ms = startedAt,
        duration_ms = System.currentTimeMillis() - startedAt,
        request = request.copy(
            body = ByteString.EMPTY,
            body_size = requestBytes,
            body_truncated = requestSpoiled,
        ),
        response = response?.copy(
            body = ByteString.EMPTY,
            body_size = responseBytes,
            body_truncated = responseSpoiled,
        ),
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

    /** Serialize a response Wailo is answering with itself, and return it as it went out. */
    private fun writeResponse(output: OutputStream, response: HttpResponse): HttpResponse {
        val bytes = response.body.toByteArray()
        val headers = response.headers.framedFor(bytes.size.toLong(), complete = true)
        val head = buildString {
            append("HTTP/1.1 ${response.code} ${response.message.ifBlank { reasonFor(response.code) }}\r\n")
            headers.forEach { append("${it.name}: ${it.value_}\r\n") }
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
        return response.copy(headers = headers, body_size = bytes.size.toLong())
    }

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
        liveSockets.toList().forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000

        /**
         * Long enough to sit through a slow endpoint but not forever: a client that hangs on a dead
         * origin cannot tell the difference between that and a broken proxy.
         */
        private const val READ_TIMEOUT_MS = 120_000

        /**
         * The largest body a breakpoint will hold in memory. Past it the exchange is relayed unheld
         * rather than failed: a rule that cannot stop this one request is a rule that did nothing,
         * which is recoverable, while refusing the request breaks a page to enforce a debugging aid.
         */
        private const val MAX_HELD_BODY_BYTES = 32 * 1024 * 1024
        private const val MAX_SCRIPT_BODY_BYTES = 8L * 1024L * 1024L

        private const val TUNNEL_LOCKED_MESSAGE = "Connection Established (encrypted, not decrypted)"

        private val TUNNEL_ESTABLISHED =
            "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        private val CONTINUE_RESPONSE =
            "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)

        private val LAST_CHUNK = "0\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        private const val DIRECT_REQUEST_HELP =
            "This is the Wailo proxy port. Set it as your HTTP proxy rather than browsing to it."

        private const val ABORTED_MESSAGE = "This request was aborted at a Wailo breakpoint."

        /**
         * Loopback unless [lan] is set, which is an open relay for anything that can reach this machine
         * and so is always a separate, explicit decision by the caller (ADR-0074).
         */
        fun start(
            port: Int,
            sink: ProxyCaptureSink,
            rules: ProxyRules = ProxyRules.None,
            tls: ProxyTls = ProxyTls.Locked,
            lan: Boolean = false,
            chain: ProxyChain = ProxyChain.Direct,
            setup: ProxySetup = ProxySetup.None,
        ): ProxyServer {
            val socket = ServerSocket()
            socket.reuseAddress = true
            val bindTo = if (lan) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress()
            socket.bind(InetSocketAddress(bindTo, port))
            return ProxyServer(socket, sink, rules, tls, chain, setup).also(ProxyServer::start)
        }
    }
}

internal class ProxyTarget(
    val host: String,
    val port: Int,
    val pathAndQuery: String,
    val url: String,
    val secure: Boolean = false,
) {
    /**
     * The same target with the whole URL as its request-target, which is what an HTTP proxy expects and
     * an origin does not. The `Host` header is unchanged, so the origin still sees what it should.
     */
    fun asAbsoluteForm() = ProxyTarget(host, port, url, url, secure)

    /** The same authority, re-pointed at an origin-form request-target read inside a decrypted tunnel. */
    fun within(requestTarget: String): ProxyTarget {
        val path = requestTarget.ifEmpty { "/" }
        val authority = if (port == 443) host else "$host:$port"
        return ProxyTarget(host, port, path, "https://$authority$path", secure = true)
    }
}

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
    if (target.port == target.defaultPort) target.host else "${target.host}:${target.port}"

private val ProxyTarget.defaultPort: Int get() = if (secure) 443 else 80

/**
 * Restate the framing for a body this proxy is sending itself. The original `Content-Length` describes
 * bytes that no longer exist once a body has been edited or decoded, and `Transfer-Encoding` describes a
 * framing that is not being used, so both have to go before either can mislead the far side.
 */
private fun List<Header>.framedFor(size: Long, complete: Boolean): List<Header> {
    val kept = filterNot {
        it.name.equals("Content-Length", ignoreCase = true) ||
            it.name.equals("Transfer-Encoding", ignoreCase = true) ||
            it.name.equals("Content-Encoding", ignoreCase = true)
    }
    return if (complete) kept + Header("Content-Length", size.toString()) else kept
}

/** Framing for a body whose length is not known ahead of time, which is what re-chunking one means. */
private fun List<Header>.chunkedFraming(): List<Header> =
    framedFor(0, complete = false) + Header("Transfer-Encoding", "chunked")

private fun List<Header>.preserveFramingFrom(original: List<Header>): List<Header> {
    val framing = setOf("content-length", "transfer-encoding", "content-encoding")
    return filterNot { it.name.lowercase() in framing } + original.filter { it.name.lowercase() in framing }
}

private fun HttpResponse.headBytes(fallbackMessage: String): ByteArray = buildString {
    append("HTTP/1.1 $code ${message.ifBlank { fallbackMessage }}\r\n")
    headers.forEach { append("${it.name}: ${it.value_}\r\n") }
    append("\r\n")
}.toByteArray(Charsets.ISO_8859_1)

/** The response as it should be recorded, with headers that match the bytes actually captured. */
private fun HttpHead.asResponse(bytes: Long, spoiled: Boolean, decoded: Boolean) = HttpResponse(
    code = second.toIntOrNull() ?: 0,
    message = third,
    headers = if (decoded) {
        headers.withoutHopByHop().framedFor(bytes, complete = true)
    } else {
        headers.withoutHopByHop(preserveUpgrade = second.toIntOrNull() == 101)
    },
    body = ByteString.EMPTY,
    body_size = bytes,
    body_truncated = spoiled,
)

private fun reasonFor(code: Int): String = when (code) {
    200 -> "OK"
    201 -> "Created"
    204 -> "No Content"
    301 -> "Moved Permanently"
    302 -> "Found"
    304 -> "Not Modified"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    500 -> "Internal Server Error"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    else -> "Wailo"
}

private fun InputStream.drain() {
    val scratch = ByteArray(RELAY_BUFFER_BYTES)
    while (read(scratch) >= 0) Unit
}
