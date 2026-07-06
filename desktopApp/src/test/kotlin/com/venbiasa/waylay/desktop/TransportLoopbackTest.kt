package com.venbiasa.waylay.desktop

import com.venbiasa.waylay.core.WaylayClient
import com.venbiasa.waylay.engine.CapturedExchange
import com.venbiasa.waylay.engine.WaylayEngine
import com.venbiasa.waylay.protocol.Hello
import com.venbiasa.waylay.protocol.HttpExchange
import com.venbiasa.waylay.protocol.HttpRequest
import com.venbiasa.waylay.protocol.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real device client to real server over a localhost socket: the M2 wire path. */
class TransportLoopbackTest {

    private val port = 18899
    private val engine = WaylayEngine(port = port)
    private var client: WaylayClient? = null

    @AfterTest
    fun tearDown() {
        client?.stop()
        engine.stop()
    }

    @Test
    fun exchangeReachesEngineAcrossTheSocket() {
        engine.start()
        val client = WaylayClient(
            hello = Hello(device_name = "pixel-test", app_id = "com.venbiasa.test", platform = "jvm"),
            host = "localhost",
            port = port,
        ).also { this.client = it; it.start() }

        client.onExchange(
            HttpExchange(
                id = "e1",
                started_at_epoch_ms = 1_000,
                duration_ms = 7,
                request = HttpRequest(method = "GET", url = "https://example.com/ping"),
                response = HttpResponse(code = 200, message = "OK"),
            ),
        )

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
}
