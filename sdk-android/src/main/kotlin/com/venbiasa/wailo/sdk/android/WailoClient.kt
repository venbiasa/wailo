package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpExchange
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Streams captured exchanges to the desktop over a WebSocket as a *live tap*: only traffic captured
 * while a connection is up is sent. Runs off the caller's thread — [onExchange] never blocks: it hands
 * the exchange to the current connection's channel, or drops it when the desktop is disconnected.
 * Nothing is retained across a disconnect, so a reconnect never replays a backlog of stale traffic
 * (the desktop keeps no capture across its own restart either — ADR-0025). A background loop keeps a
 * connection alive, reconnecting with a fixed backoff whenever the desktop isn't up; a WebSocket ping
 * keepalive detects a silently dropped idle link so a stale connection self-heals for future traffic.
 * Overflow on a live-but-slow link drops the oldest so a slow desktop never blocks or OOMs the host app.
 */
class WailoClient(
    private val hello: Hello,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val bufferCapacity: Int = DEFAULT_BUFFER,
) : CaptureSink, AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The live connection's channel, or null while disconnected. Swapped in on connect and cleared on
    // disconnect, so an exchange captured while down is dropped (a live tap) rather than buffered into a
    // backlog that would replay on reconnect.
    @Volatile
    private var live: Channel<HttpExchange>? = null

    private val client = HttpClient(CIO) {
        // Actively probe the link so a silently dropped idle connection is noticed (the session
        // closes on pong timeout) and the loop reconnects, instead of sitting half-open until the
        // next send.
        install(WebSockets) { pingIntervalMillis = PING_INTERVAL_MS }
    }

    fun start() {
        scope.launch { connectLoop() }
    }

    override fun onExchange(exchange: HttpExchange) {
        // Live tap: deliver only when a connection is up; drop otherwise. A lost link loses its
        // in-flight traffic by design rather than hoarding a backlog to replay on reconnect.
        live?.trySend(exchange)
    }

    fun stop() {
        scope.cancel()
        client.close()
    }

    /** Lets [WailoRuntime.install] tear this client down when it is replaced by another sink. */
    override fun close() = stop()

    private suspend fun connectLoop() {
        while (scope.isActive) {
            try {
                client.webSocket(host = host, port = port, path = PATH) {
                    // A fresh channel per connection: exchanges are accepted only while this connection
                    // is the live one, and it is discarded on disconnect so nothing is replayed.
                    val channel = Channel<HttpExchange>(bufferCapacity, BufferOverflow.DROP_OLDEST)
                    live = channel
                    try {
                        // Send Hello first, then start draining, so Hello is always the opening frame.
                        send(Frame.Binary(true, Envelope(hello = hello).encode()))
                        val drainer = launch {
                            for (exchange in channel) {
                                try {
                                    send(Frame.Binary(true, Envelope(exchange = exchange).encode()))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    // Link died mid-send: drop this exchange (no buffering by design)
                                    // and stop draining — the closing socket ends the loop below and
                                    // drives the reconnect.
                                    break
                                }
                            }
                        }
                        try {
                            // Consume inbound frames only to observe the connection: the desktop has no
                            // device-bound protocol yet, so frames are ignored, but this returns the
                            // moment the socket closes for any reason (desktop close, dropped link, or a
                            // ping/pong timeout on a dead idle link) — which is what lets an otherwise
                            // idle connection reconnect.
                            incoming.consumeEach { }
                        } finally {
                            drainer.cancel()
                        }
                    } finally {
                        // Stop accepting into this connection's channel before discarding it, so an
                        // exchange captured after the drop is dropped rather than silently queued.
                        live = null
                        channel.close()
                    }
                }
            } catch (_: Exception) {
                // Desktop unreachable or link dropped; back off and retry. No traffic is retained —
                // only exchanges captured while the next connection is live will be sent.
            }
            if (scope.isActive) delay(RECONNECT_DELAY_MS)
        }
    }

    companion object {
        const val DEFAULT_HOST: String = "localhost"
        const val DEFAULT_PORT: Int = 8899
        private const val DEFAULT_BUFFER: Int = 512
        private const val RECONNECT_DELAY_MS: Long = 2000L
        private const val PING_INTERVAL_MS: Long = 20_000L
        private const val PATH: String = "/"
    }
}
