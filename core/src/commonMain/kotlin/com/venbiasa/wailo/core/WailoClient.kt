package com.venbiasa.wailo.core

import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpExchange
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Streams captured exchanges to the desktop over a WebSocket. Runs off the
 * caller's thread: [onExchange] only enqueues, and a background loop drains the
 * buffer, reconnecting whenever the desktop isn't up yet. Overflow drops the
 * oldest so a slow/absent desktop never blocks or OOMs the host app.
 */
class WailoClient(
    private val hello: Hello,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    bufferCapacity: Int = DEFAULT_BUFFER,
) : CaptureSink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outbox = Channel<HttpExchange>(bufferCapacity, BufferOverflow.DROP_OLDEST)
    private val client = HttpClient(CIO) { install(WebSockets) }

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

    private suspend fun connectLoop() {
        while (scope.isActive) {
            try {
                client.webSocket(host = host, port = port, path = PATH) {
                    send(Frame.Binary(true, Envelope(hello = hello).encode()))
                    for (exchange in outbox) {
                        send(Frame.Binary(true, Envelope(exchange = exchange).encode()))
                    }
                }
            } catch (_: Exception) {
                // Desktop unreachable; back off and retry. Buffered exchanges wait.
            }
            if (scope.isActive) delay(RECONNECT_DELAY_MS)
        }
    }

    companion object {
        const val DEFAULT_HOST: String = "localhost"
        const val DEFAULT_PORT: Int = 8899
        private const val DEFAULT_BUFFER: Int = 512
        private const val RECONNECT_DELAY_MS: Long = 2000L
        private const val PATH: String = "/"
    }
}
