package com.venbiasa.wailo.host

import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SeedSpendTest {

    private fun seed(
        id: String,
        url: String,
        method: String = "",
        status: Int = 200,
        delayMillis: Int = 0,
        body: ByteArray = """{"ok":true}""".toByteArray(),
    ) = HostSeed(
        id = id,
        urlPattern = url,
        method = method,
        statusCode = status,
        delayMillis = delayMillis,
        body = body,
    )

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
        val result = spendSeedOn(engine, queue, hold(phase = BreakpointPhase.BREAKPOINT_PHASE_REQUEST))
        assertNull(result)
    }

    /**
     * An empty body is a body. The daemon holds a seed's bytes (ADR-0085), so "nothing authored" is a
     * choice it can serve rather than a file that failed to load and has to be left for a human.
     */
    @Test
    fun anEmptyBodyStillAnswersTheHold() = runBlocking {
        var served: HttpResponse? = null
        val queue = listOf(seed("a", "https://x/poll", body = ByteArray(0)))
        val result = spendSeedOn(queue, hold()) { _, response, _ ->
            served = response
            true
        }
        assertEquals(emptyList(), result)
        assertEquals(0, assertNotNull(served).body.size)
    }

    @Test
    fun staleHoldDoesNotSpendSeed() = runBlocking {
        val engine = WailoEngine(port = 0)
        val queue = listOf(seed("a", "https://x/poll"), seed("b", "https://x/other"))
        assertNull(spendSeedOn(engine, queue, hold()))
    }

    @Test
    fun urlPatternWildcardMatches() {
        assertTrue(urlPatternMatches("https://x/*", "https://x/poll"))
        assertNull(listOf(seed("a", "https://x/other")).firstMatch("https://x/poll", "GET"))
    }

    @Test
    fun servedResponseCarriesAuthoredHeadersAndTheRealContentLength() = runBlocking {
        val seed = HostSeed(
            id = "s",
            urlPattern = "https://x/*",
            statusCode = 201,
            delayMillis = 625,
            headers = listOf(
                Header(name = "Content-Type", value_ = "application/json"),
                // Authored by hand and now wrong for the bytes actually sent.
                Header(name = "Content-Length", value_ = "9999"),
            ),
            body = """{"ok":true}""".toByteArray(),
        )
        var served: HttpResponse? = null
        var delayMillis = 0
        val spent = spendSeedOn(listOf(seed), hold()) { _, response, delay ->
            served = response
            delayMillis = delay
            true
        }
        assertTrue(spent!!.isEmpty())
        val response = assertNotNull(served)
        assertEquals(201, response.code)
        assertEquals("application/json", response.headers.single { it.name == "Content-Type" }.value_)
        assertEquals("11", response.headers.single { it.name == "Content-Length" }.value_)
        assertEquals("""{"ok":true}""", response.body.utf8())
        assertEquals(625, delayMillis)
    }
}
