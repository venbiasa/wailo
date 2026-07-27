package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.CaptureFilter
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.protocol.MapLocalRule
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import com.venbiasa.wailo.protocol.BreakpointRule
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The interceptor's device-side behaviors, driven with a fake [Interceptor.Chain] and fake control
 * channel so no real network or WebSocket is involved: capture-filter drop, Map Local serve + fail-open,
 * and breakpoint request/response edit/abort (ADR-0019/0027/0029).
 */
class WailoInterceptorTest {

    private val sink = RecordingSink()
    private val interceptor = WailoInterceptor(sink, maxBodyBytes = 256L * 1024L)

    @After
    fun tearDown() {
        WailoRuleStore.replace(emptyList())
        WailoBreakpointStore.replace(emptyList())
        WailoCaptureFilterStore.reset()
        WailoControlChannel.bodyFetcher = null
        WailoControlChannel.breakpointGate = null
    }

    @Test
    fun filteredHostIsNotCapturedButStillProceeds() {
        WailoCaptureFilterStore.replace(CaptureFilter(allowlist_enabled = true, allow_patterns = listOf("allowed.com")))
        val chain = FakeChain(request("https://blocked.com/x")) { networkResponse(it) }

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertTrue("filtered host must not be captured", sink.exchanges.isEmpty())
        assertEquals("https://blocked.com/x", chain.proceededWith?.url.toString())
    }

    @Test
    fun allowedHostIsCaptured() {
        WailoCaptureFilterStore.replace(CaptureFilter(allowlist_enabled = true, allow_patterns = listOf("api.example.com")))
        val chain = FakeChain(request("https://api.example.com/users")) { networkResponse(it) }

        interceptor.intercept(chain).body!!.close()

        assertEquals(1, sink.exchanges.size)
        assertFalse(sink.exchanges.single().edited)
    }

    @Test
    fun mapLocalServesDesktopBodyWithoutHittingNetwork() {
        WailoRuleStore.replace(listOf(MapLocalRule(id = "r1", enabled = true, url_pattern = "https://api.example.com/*")))
        WailoControlChannel.bodyFetcher = FakeFetcher(
            WailoMappedResponse(code = 201, headers = listOf(Header("Content-Type", "application/json")), body = "{\"ok\":true}".encodeUtf8()),
        )
        val chain = FakeChain(request("https://api.example.com/users")) { networkResponse(it) }

        val response = interceptor.intercept(chain)

        assertEquals(201, response.code)
        assertEquals("{\"ok\":true}", response.body!!.string())
        assertNull("network must not be hit on a Map Local match", chain.proceededWith)
        assertEquals(1, sink.exchanges.size)
        assertTrue(sink.exchanges.single().edited)
    }

    @Test
    fun mapLocalFailOpenProceedsToNetwork() {
        WailoRuleStore.replace(listOf(MapLocalRule(id = "r1", enabled = true, url_pattern = "*")))
        WailoControlChannel.bodyFetcher = FakeFetcher(null) // desktop can't resolve -> fall open
        val chain = FakeChain(request("https://api.example.com/users")) { networkResponse(it, body = "real") }

        val response = interceptor.intercept(chain)

        assertEquals("real", response.body!!.string())
        assertEquals("https://api.example.com/users", chain.proceededWith?.url.toString())
        assertFalse(sink.exchanges.single().edited)
    }

    @Test
    fun breakpointRequestAbortFailsTheCall() {
        WailoBreakpointStore.replace(listOf(bpRule("b1", "*", onRequest = true)))
        WailoControlChannel.breakpointGate = FakeGate(requestDecision = WailoRequestDecision.Abort)
        val chain = FakeChain(request("https://api.example.com/users")) { networkResponse(it) }

        assertThrows(IOException::class.java) { interceptor.intercept(chain) }

        assertNull("aborted request must not reach the network", chain.proceededWith)
        val exchange = sink.exchanges.single()
        assertTrue(exchange.edited)
        assertTrue(exchange.error.isNotEmpty())
    }

    @Test
    fun breakpointRequestEditIsSentInstead() {
        WailoBreakpointStore.replace(listOf(bpRule("b1", "*", onRequest = true)))
        WailoControlChannel.breakpointGate = FakeGate(
            requestDecision = WailoRequestDecision.Proceed(
                HttpRequest(
                    method = "POST",
                    url = "https://api.example.com/edited",
                    headers = listOf(Header("X-Edited", "1")),
                    body = "edited-body".encodeUtf8(),
                ),
            ),
        )
        val chain = FakeChain(request("https://api.example.com/users")) { networkResponse(it) }

        interceptor.intercept(chain).body!!.close()

        val sent = chain.proceededWith!!
        assertEquals("POST", sent.method)
        assertEquals("https://api.example.com/edited", sent.url.toString())
        assertEquals("1", sent.header("X-Edited"))
        val buffer = Buffer()
        sent.body!!.writeTo(buffer)
        assertEquals("edited-body", buffer.readUtf8())
    }

    @Test
    fun breakpointResponseEditIsDelivered() {
        WailoBreakpointStore.replace(listOf(bpRule("b1", "*", onResponse = true)))
        WailoControlChannel.breakpointGate = FakeGate(
            responseDecision = WailoResponseDecision.Proceed(
                HttpResponse(
                    code = 503,
                    message = "Nope",
                    headers = listOf(Header("Content-Type", "text/plain")),
                    body = "edited-response".encodeUtf8(),
                ),
            ),
        )
        val chain = FakeChain(request("https://api.example.com/users")) { networkResponse(it, code = 200, body = "real") }

        val response = interceptor.intercept(chain)

        assertEquals(503, response.code)
        assertEquals("edited-response", response.body!!.string())
        assertTrue(sink.exchanges.single().edited)
    }

    @Test
    fun breakpointResponseResumedUnchangedDeliversOriginal() {
        WailoBreakpointStore.replace(listOf(bpRule("b1", "*", onResponse = true)))
        WailoControlChannel.breakpointGate = FakeGate(responseDecision = WailoResponseDecision.Proceed(null))
        val chain = FakeChain(request("https://api.example.com/users")) { networkResponse(it, code = 200, body = "real") }

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals("real", response.body!!.string())
        assertFalse(sink.exchanges.single().edited)
    }

    // --- fixtures ---

    private fun request(url: String) = Request.Builder().url(url).build()

    private fun networkResponse(request: Request, code: Int = 200, body: String = "real"): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .headers(Headers.headersOf("Content-Type", "text/plain"))
            .body(body.toResponseBody("text/plain".toMediaTypeOrNull()))
            .build()

    private fun bpRule(id: String, pattern: String, onRequest: Boolean = false, onResponse: Boolean = false) =
        BreakpointRule(id = id, enabled = true, url_pattern = pattern, on_request = onRequest, on_response = onResponse)

    private class RecordingSink : CaptureSink {
        val exchanges = mutableListOf<HttpExchange>()
        override fun onExchange(exchange: HttpExchange) {
            exchanges.add(exchange)
        }
    }

    private class FakeFetcher(private val mapped: WailoMappedResponse?) : WailoBodyFetcher {
        override fun fetchBody(ruleId: String, url: String, method: String): WailoMappedResponse? = mapped
    }

    private class FakeGate(
        private val requestDecision: WailoRequestDecision = WailoRequestDecision.Proceed(null),
        private val responseDecision: WailoResponseDecision = WailoResponseDecision.Proceed(null),
    ) : WailoBreakpointGate {
        override fun pauseRequest(ruleId: String, request: HttpRequest): WailoRequestDecision = requestDecision
        override fun pauseResponse(ruleId: String, request: HttpRequest, response: HttpResponse): WailoResponseDecision =
            responseDecision
    }

    private class FakeChain(
        private val request: Request,
        private val onProceed: (Request) -> Response,
    ) : Interceptor.Chain {
        var proceededWith: Request? = null
            private set

        override fun request(): Request = request
        override fun proceed(request: Request): Response {
            proceededWith = request
            return onProceed(request)
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
