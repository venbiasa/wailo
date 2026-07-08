package com.venbiasa.wailo.engine

import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Framed Envelopes from a real WebSocket client are decoded by the engine and published on its
 * StateFlow — the server half of the M2 wire path (client half lives in sdk-android). */
class WailoEngineLoopbackTest {

    private val port = 18899
    private val engine = WailoEngine(port = port)
    private val client = HttpClient(CIO) { install(WebSockets) }

    @AfterTest
    fun tearDown() {
        client.close()
        engine.stop()
    }

    @Test
    fun framedExchangeReachesTheStateFlow() = runBlocking {
        engine.start()
        client.webSocket(host = "localhost", port = port, path = "/") {
            send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
            send(Frame.Binary(true, Envelope(exchange = EXCHANGE).encode()))
            // Keep the socket open briefly so the server drains both frames before close.
            delay(500)
        }

        val received = awaitFirstRow(timeoutMs = 5_000)
        assertEquals(1, received.size)
        val row = received.first()
        assertEquals("com.venbiasa.test", row.appId)
        assertEquals("pixel-test", row.deviceName)
        assertEquals("https://example.com/ping", row.exchange.request?.url)
        assertEquals(200, row.exchange.response?.code)
    }

    private fun awaitFirstRow(timeoutMs: Long): List<CapturedExchange> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = engine.exchanges.value
            if (current.isNotEmpty()) return current
            Thread.sleep(50)
        }
        return engine.exchanges.value
    }

    private companion object {
        val HELLO = Hello(device_name = "pixel-test", app_id = "com.venbiasa.test", platform = "jvm")
        val EXCHANGE = HttpExchange(
            id = "e1",
            started_at_epoch_ms = 1_000,
            duration_ms = 7,
            request = HttpRequest(method = "GET", url = "https://example.com/ping"),
            response = HttpResponse(code = 200, message = "OK"),
        )
    }
}
