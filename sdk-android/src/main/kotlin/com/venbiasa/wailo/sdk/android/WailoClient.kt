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
 * Streams captured exchanges to the desktop over a WebSocket. Runs off the caller's thread:
 * [onExchange] only enqueues, and a background loop drains the buffer, reconnecting whenever the
 * desktop isn't up yet. A WebSocket ping keepalive detects a silently dropped idle link so the loop
 * reconnects instead of the socket sitting half-open until the next send. Overflow drops the oldest
 * so a slow/absent desktop never blocks or OOMs the host app.
 */
class WailoClient(
    private val hello: Hello,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    bufferCapacity: Int = DEFAULT_BUFFER,
) : CaptureSink, AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outbox = Channel<HttpExchange>(bufferCapacity, BufferOverflow.DROP_OLDEST)
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
        outbox.trySend(exchange)
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
                    send(Frame.Binary(true, Envelope(hello = hello).encode()))
                    val drainer = launch {
                        for (exchange in outbox) {
                            try {
                                send(Frame.Binary(true, Envelope(exchange = exchange).encode()))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Link died mid-send: requeue so the next connection delivers it
                                // instead of dropping it, then stop draining — the closing socket
                                // ends the loop below and drives the reconnect.
                                outbox.trySend(exchange)
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
                }
            } catch (_: Exception) {
                // Desktop unreachable or link dropped; back off and retry. Buffered exchanges wait.
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
