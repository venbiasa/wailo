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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** The real WailoClient opens with a Hello and then drains buffered exchanges to a throwaway server.
 * Uses ktor-server purely as a test fixture — the SDK never depends on engine (invariant #3). */
class WailoClientTest {

    @Test
    fun sendsHelloThenBufferedExchange() = runBlocking {
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
        // Enqueued before the socket is up; must arrive after the Hello once connected.
        client.onExchange(HttpExchange(id = "e1", started_at_epoch_ms = 1, duration_ms = 2))

        try {
            val hello = withTimeout(5_000) { received.receive() }
            val exchange = withTimeout(5_000) { received.receive() }
            assertEquals("com.test", hello.hello?.app_id)
            assertEquals("e1", exchange.exchange?.id)
        } finally {
            client.stop()
            server.stop(0, 0)
        }
    }
}
