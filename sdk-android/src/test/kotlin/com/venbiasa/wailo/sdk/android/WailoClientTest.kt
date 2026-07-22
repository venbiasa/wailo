package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpExchange
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** The real WailoClient opens with a Hello and then streams live exchanges to a throwaway server.
 * Uses ktor-server purely as a test fixture — the SDK never depends on engine (invariant #3). */
class WailoClientTest {

    @Test
    fun sendsHelloThenLiveExchange() = runBlocking {
        val received = Channel<Envelope>(capacity = 16)
        val port = 18988
        val server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    for (frame in incoming) {
                        if (frame is Frame.Binary) received.trySend(Envelope.ADAPTER.decode(frame.readBytes()))
                    }
                }
            }
        }.also { it.start(wait = false) }

        val client = WailoClient(
            hello = Hello(device_name = "test", app_id = "com.test", platform = "android"),
            host = "localhost",
            port = port,
        ).also { it.start() }

        try {
            // Hello lands on connect and proves the tap is live: the client marks the connection live
            // before sending Hello, so once the server has seen Hello, onExchange won't be dropped.
            val hello = withTimeout(5_000) { received.receive() }
            assertEquals("com.test", hello.hello?.app_id)

            // Captured while connected → delivered right after the Hello.
            client.onExchange(HttpExchange(id = "e1", started_at_epoch_ms = 1, duration_ms = 2))
            val exchange = withTimeout(5_000) { received.receive() }
            assertEquals("e1", exchange.exchange?.id)
        } finally {
            client.stop()
            server.stop(0, 0)
        }
    }

    /** A live tap keeps no backlog: an exchange captured while the desktop is down is dropped, never
     * buffered for replay — but the connect loop still recovers the link, so an exchange captured
     * after the reconnect is delivered. */
    @Test
    fun dropsWhileDownButStreamsAfterReconnect() = runBlocking {
        val received = Channel<Envelope>(capacity = 16)
        val port = 18989

        val client = WailoClient(
            hello = Hello(device_name = "test", app_id = "com.test", platform = "android"),
            host = "localhost",
            port = port,
        ).also { it.start() }
        // Captured while nothing is listening → dropped, not buffered.
        client.onExchange(HttpExchange(id = "early", started_at_epoch_ms = 1, duration_ms = 2))

        // Let the client fail at least one connect attempt before the server comes up.
        delay(500)
        val server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    for (frame in incoming) {
                        if (frame is Frame.Binary) received.trySend(Envelope.ADAPTER.decode(frame.readBytes()))
                    }
                }
            }
        }.also { it.start(wait = false) }

        try {
            // The connect loop recovers the link; Hello proves it is live again.
            val hello = withTimeout(10_000) { received.receive() }
            assertEquals("com.test", hello.hello?.app_id)

            // Captured after the reconnect → delivered. "early" was dropped, so the only exchange the
            // server ever sees is this one.
            client.onExchange(HttpExchange(id = "live", started_at_epoch_ms = 3, duration_ms = 4))
            val exchange = withTimeout(10_000) { received.receive() }
            assertEquals("live", exchange.exchange?.id)
        } finally {
            client.stop()
            server.stop(0, 0)
        }
    }
}
