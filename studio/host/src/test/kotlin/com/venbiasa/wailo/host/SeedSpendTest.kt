package com.venbiasa.wailo.host

import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString

class SeedSpendTest {

    private fun seed(id: String, url: String, method: String = "", status: Int = 200) =
        HostSeed(id = id, urlPattern = url, method = method, statusCode = status)

    private fun hold(
        correlationId: String = "c1",
        phase: BreakpointPhase = BreakpointPhase.BREAKPOINT_PHASE_RESPONSE,
        url: String = "https://x/poll",
        method: String = "GET",
    ) = PausedExchange(
        correlationId = correlationId,
        deviceName = "phone",
        appId = "app",
        platform = "android",
        phase = phase,
        request = HttpRequest(method = method, url = url),
        response = HttpResponse(code = 200),
    )

    @Test
    fun spendingLeavesTheRestInOrder() {
        val queue = listOf(seed("a", "https://x/a"), seed("b", "https://x/b"), seed("c", "https://x/c"))
        assertEquals(listOf("a", "c"), queue.consume(queue[1]).map { it.id })
    }

    @Test
    fun firstMatchIsTopDown() {
        val queue = listOf(
            seed("first", "https://x/poll", status = 202),
            seed("second", "https://x/poll", status = 200),
        )
        assertEquals("first", queue.firstMatch("https://x/poll", "GET")?.id)
        assertEquals("second", queue.consume(queue.first()).firstMatch("https://x/poll", "GET")?.id)
    }

    @Test
    fun requestPhaseHoldIsNotAnswered() = runBlocking {
        val engine = WailoEngine(port = 0)
        val queue = listOf(seed("a", "https://x/poll"))
        val result = spendSeedOn(engine, queue, hold(phase = BreakpointPhase.BREAKPOINT_PHASE_REQUEST)) {
            HttpResponse(code = 200, body = ByteArray(0).toByteString())
        }
        assertNull(result)
    }

    @Test
    fun missingBodyLeavesQueueAndHoldUntouched() = runBlocking {
        val engine = WailoEngine(port = 0)
        val queue = listOf(seed("a", "https://x/poll"))
        val result = spendSeedOn(engine, queue, hold()) { null }
        assertNull(result)
    }

    @Test
    fun staleHoldDoesNotSpendSeedAfterProviderServes() = runBlocking {
        val engine = WailoEngine(port = 0)
        val queue = listOf(seed("a", "https://x/poll"), seed("b", "https://x/other"))
        val spent = spendSeedOn(engine, queue, hold()) {
            HttpResponse(code = 202, body = "{}".toByteArray().toByteString())
        }
        assertNull(spent)
    }

    @Test
    fun urlPatternWildcardMatches() {
        assertTrue(urlPatternMatches("https://x/*", "https://x/poll"))
        assertNull(listOf(seed("a", "https://x/other")).firstMatch("https://x/poll", "GET"))
    }

    @Test
    fun hostSeedHeadersRoundTrip() {
        val seed = HostSeed(
            id = "s",
            urlPattern = "https://x/*",
            headers = listOf(Header(name = "Content-Type", value_ = "application/json")),
        )
        assertEquals("application/json", seed.headers.single().value_)
    }
}
