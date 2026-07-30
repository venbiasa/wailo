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
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Moving the capture server between ports at runtime, behind the studio's port setting. Two properties
 * carry the feature: a rebind is a *move* rather than a rebuild, so everything the engine has captured
 * survives it; and a port the engine can't take leaves the previous one serving instead of dropping the
 * app off the network entirely.
 */
class WailoEnginePortTest {

    private val firstPort = 18901
    private val secondPort = 18902
    private val engine = WailoEngine(port = firstPort)
    private val client = HttpClient(CIO) { install(WebSockets) }

    @AfterTest
    fun tearDown() {
        client.close()
        engine.stop()
    }

    @Test
    fun rebindMovesTheServerAndKeepsCapturedTraffic() = runBlocking {
        assertTrue(engine.start())
        stream(port = firstPort)
        assertEquals(1, awaitRows(1).size)

        assertTrue(engine.rebind(secondPort))
        assertEquals(secondPort, engine.port)
        assertTrue(engine.listening)

        // The whole reason the engine rebinds in place rather than being rebuilt on the new port.
        assertEquals(1, engine.exchanges.value.size)

        stream(port = secondPort)
        assertEquals(2, awaitRows(2).size)
    }

    @Test
    fun rebindToAnOccupiedPortKeepsTheCurrentOne() = runBlocking {
        assertTrue(engine.start())

        ServerSocket(secondPort).use {
            assertFalse(engine.rebind(secondPort))
        }
        assertEquals(firstPort, engine.port)
        assertTrue(engine.listening, "a refused port must leave the previous one serving")

        // Not just nominally up — still reachable where devices were already pointed.
        stream(port = firstPort)
        assertEquals(1, awaitRows(1).size)
    }

    @Test
    fun rebindOutsideThePortRangeIsRefused() = runBlocking {
        assertTrue(engine.start())

        // 0 is the trap the range exists for: the OS would bind it happily, to a port nobody was told.
        assertFalse(engine.rebind(0))
        assertFalse(engine.rebind(70_000))
        assertEquals(firstPort, engine.port)
        assertTrue(engine.listening)
    }

    @Test
    fun startOnAnOccupiedPortReportsFailureInsteadOfThrowing() {
        ServerSocket(firstPort).use {
            assertFalse(engine.start())
            assertFalse(engine.listening)
        }
    }

    // One exchange from a device connecting on [port], held open long enough for the server to drain it.
    private suspend fun stream(port: Int) {
        client.webSocket(host = "localhost", port = port, path = "/") {
            send(Frame.Binary(true, Envelope(hello = HELLO).encode()))
            send(Frame.Binary(true, Envelope(exchange = EXCHANGE).encode()))
            delay(500)
        }
    }

    private fun awaitRows(count: Int): List<CapturedExchange> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val current = engine.exchanges.value
            if (current.size >= count) return current
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
