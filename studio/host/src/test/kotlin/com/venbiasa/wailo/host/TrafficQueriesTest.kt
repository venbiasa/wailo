package com.venbiasa.wailo.host

import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class TrafficQueriesTest {

    @Test
    fun findByIdAndUrlFilters() {
        val exchanges = MutableStateFlow(
            listOf(
                row("1", "GET", "https://api.example.com/a", 200),
                row("2", "POST", "https://api.example.com/b", 500),
                row("3", "GET", "https://other.example.com/a", 200, appId = "other"),
            ),
        )
        val q = TrafficQueries(exchanges, MutableStateFlow(emptyList()))
        assertEquals("1", q.findExchangeById("1")?.exchange?.id)
        assertEquals(listOf("2"), q.findExchangesByUrl(statusCode = 500).map { it.exchange.id })
        assertEquals(listOf("1", "2"), q.findExchangesByUrl(urlContains = "api.example").map { it.exchange.id })
        assertEquals(
            listOf("1"),
            q.findExchangesByUrl(urlPattern = "https://api.example.com/a", method = "GET").map { it.exchange.id },
        )
        assertEquals(listOf("3"), q.findExchangesByUrl(appId = "other").map { it.exchange.id })
    }

    @Test
    fun summarizeIsEmptyFriendly() {
        val exchanges = MutableStateFlow<List<CapturedExchange>>(emptyList())
        val holds = MutableStateFlow<List<PausedExchange>>(emptyList())
        val q = TrafficQueries(exchanges, holds)
        assertEquals("(no exchanges)", q.summarizeExchanges())
        assertEquals("(no holds)", q.summarizeHolds())
        exchanges.value = listOf(row("1", "GET", "https://x/y", 204))
        assertTrue(q.summarizeExchanges().contains("https://x/y"))
    }

    @Test
    fun waitForExchangeSeesExistingAndArriving() = runBlocking {
        val exchanges = MutableStateFlow(listOf(row("1", "GET", "https://x/ready", 200)))
        val q = TrafficQueries(exchanges, MutableStateFlow(emptyList()))
        assertNotNull(q.waitForExchange(timeout = 200.milliseconds, urlContains = "ready"))

        val pending = async {
            q.waitForExchange(timeout = 2_000.milliseconds, urlContains = "later")
        }
        delay(50)
        exchanges.value = exchanges.value + row("2", "GET", "https://x/later", 200)
        assertEquals("2", pending.await()?.exchange?.id)
    }

    @Test
    fun waitForExchangeTimesOut() = runBlocking {
        val q = TrafficQueries(MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))
        assertNull(q.waitForExchange(timeout = 50.milliseconds, urlContains = "never"))
    }

    @Test
    fun waitForHold() = runBlocking {
        val holds = MutableStateFlow(
            listOf(
                PausedExchange(
                    correlationId = "h1",
                    deviceName = "d",
                    appId = "a",
                    platform = "android",
                    phase = BreakpointPhase.BREAKPOINT_PHASE_RESPONSE,
                    request = HttpRequest(method = "GET", url = "https://x/held"),
                    response = HttpResponse(code = 200),
                ),
            ),
        )
        val q = TrafficQueries(MutableStateFlow(emptyList()), holds)
        assertEquals("h1", q.waitForHold(timeout = 200.milliseconds)?.correlationId)
        assertTrue(q.summarizeHolds().contains("https://x/held"))
    }

    private fun row(
        id: String,
        method: String,
        url: String,
        code: Int,
        appId: String = "app",
    ) = CapturedExchange(
        deviceName = "phone",
        appId = appId,
        platform = "android",
        exchange = HttpExchange(
            id = id,
            request = HttpRequest(method = method, url = url),
            response = HttpResponse(code = code),
        ),
    )
}
