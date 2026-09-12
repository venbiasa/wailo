package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.CaptureFilter
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.protocol.MapLocalRule
import com.venbiasa.wailo.protocol.ScriptPhase
import com.venbiasa.wailo.protocol.ScriptRule
import com.venbiasa.wailo.protocol.ScriptTransformResult
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import com.venbiasa.wailo.protocol.BreakpointRule
import okio.Buffer
import okio.BufferedSink
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
        WailoScriptStore.replace(emptyList())
        WailoCaptureFilterStore.reset()
        WailoControlChannel.bodyFetcher = null
        WailoControlChannel.breakpointGate = null
        WailoControlChannel.scriptTransformer = null
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
    fun requestScriptsFeedMapLocalAndResponseScriptsTransformTheFixture() {
        WailoScriptStore.replace(
            listOf(
                ScriptRule(
                    id = "script",
                    enabled = true,
                    url_pattern = "https://api.example.com/*",
                    on_request = true,
                    on_response = true,
                ),
            ),
        )
        WailoRuleStore.replace(
            listOf(
                MapLocalRule(
                    id = "mapped",
                    enabled = true,
                    url_pattern = "https://api.example.com/mapped",
                ),
            ),
        )
        WailoControlChannel.bodyFetcher = FakeFetcher(
            WailoMappedResponse(
                code = 200,
                headers = listOf(Header("Content-Type", "application/json")),
                body = """{"value":1}""".encodeUtf8(),
            ),
        )
        WailoControlChannel.scriptTransformer = FakeScriptTransformer { phase, request, response ->
            when (phase) {
                ScriptPhase.SCRIPT_PHASE_REQUEST -> ScriptTransformResult(
                    request = request.copy(url = "https://api.example.com/mapped"),
                )
                ScriptPhase.SCRIPT_PHASE_RESPONSE -> ScriptTransformResult(
                    request = request,
                    response = response?.copy(body = """{"value":2}""".encodeUtf8(), body_size = 11),
                    response_body_replaced = true,
                )
                else -> null
            }
        }
        val chain = FakeChain(request("https://api.example.com/original")) { networkResponse(it) }

        val response = interceptor.intercept(chain)

        assertNull(chain.proceededWith)
        assertEquals("""{"value":2}""", response.body!!.string())
        assertTrue(sink.exchanges.single().edited)
        assertEquals("https://api.example.com/mapped", sink.exchanges.single().request?.url)
    }

    @Test
    fun requestScriptCanEditMetadataWithoutConsumingAOneShotBody() {
        WailoScriptStore.replace(
            listOf(
                ScriptRule(
                    id = "metadata",
                    enabled = true,
                    url_pattern = "*",
                    on_request = true,
                ),
            ),
        )
        var replayable: Boolean? = null
        WailoControlChannel.scriptTransformer = object : WailoScriptTransformer {
            override fun transform(
                phase: ScriptPhase,
                request: HttpRequest,
                response: HttpResponse?,
                requestBodyReplayable: Boolean,
            ): ScriptTransformResult {
                replayable = requestBodyReplayable
                return ScriptTransformResult(
                    request = request.copy(url = "https://api.example.com/changed"),
                )
            }
        }
        val streaming = object : RequestBody() {
            override fun contentType() = "text/plain".toMediaTypeOrNull()
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("streamed")
            }
        }
        var sentBody = ""
        val original = Request.Builder()
            .url("https://api.example.com/original")
            .post(streaming)
            .build()
        val chain = FakeChain(original) { sent ->
            val buffer = Buffer()
            sent.body?.writeTo(buffer)
            sentBody = buffer.readUtf8()
            networkResponse(sent)
        }

        interceptor.intercept(chain).close()

        assertEquals(false, replayable)
        assertEquals("https://api.example.com/changed", chain.proceededWith?.url.toString())
        assertEquals("streamed", sentBody)
        assertTrue(sink.exchanges.single().edited)
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
    fun breakpointRequestEditThatMatchesMapLocalIsSourcedFromMapLocal() {
        // Precedence v3 (ADR-0033, subsuming ADR-0032): the original URL matches no Map Local rule, so it
        // reaches the request breakpoint, which rewrites the URL to one a Map Local rule *does* match. The
        // response is then sourced from Map Local for the edited request instead of hitting the network.
        WailoRuleStore.replace(listOf(MapLocalRule(id = "r1", enabled = true, url_pattern = "https://api.example.com/mapped*")))
        WailoBreakpointStore.replace(listOf(bpRule("b1", "https://api.example.com/original*", onRequest = true)))
        WailoControlChannel.bodyFetcher = FakeFetcher(
            WailoMappedResponse(code = 200, headers = listOf(Header("Content-Type", "application/json")), body = "{\"mapped\":true}".encodeUtf8()),
        )
        WailoControlChannel.breakpointGate = FakeGate(
            requestDecision = WailoRequestDecision.Proceed(
                HttpRequest(method = "GET", url = "https://api.example.com/mapped/x"),
            ),
        )
        val chain = FakeChain(request("https://api.example.com/original")) { networkResponse(it) }

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals("{\"mapped\":true}", response.body!!.string())
        assertNull("an edited request that matches Map Local must not hit the network", chain.proceededWith)
        assertTrue(sink.exchanges.single().edited)
    }

    @Test
    fun breakpointRequestPhaseTakesPrecedenceAndMapLocalSourcesResponse() {
        // Precedence v3 (ADR-0033): a URL matches both a Map Local rule and a request-phase breakpoint. The
        // breakpoint takes precedence (the request pauses), then Map Local sources the response instead of
        // the network. With no response phase, the mocked value is delivered directly, flagged edited.
        WailoRuleStore.replace(listOf(MapLocalRule(id = "r1", enabled = true, url_pattern = "https://both.example.com/*")))
        WailoBreakpointStore.replace(listOf(bpRule("b1", "https://both.example.com/*", onRequest = true)))
        WailoControlChannel.bodyFetcher = FakeFetcher(
            WailoMappedResponse(code = 200, headers = listOf(Header("Content-Type", "application/json")), body = "{\"mapped\":true}".encodeUtf8()),
        )
        val gate = FakeGate(requestDecision = WailoRequestDecision.Proceed(null))
        WailoControlChannel.breakpointGate = gate
        val chain = FakeChain(request("https://both.example.com/users")) { networkResponse(it, body = "real") }

        val response = interceptor.intercept(chain)

        assertTrue("the request phase must pause (breakpoint precedence)", gate.requestPaused)
        assertEquals("{\"mapped\":true}", response.body!!.string())
        assertNull("Map Local must source the response, not the network", chain.proceededWith)
        assertTrue(sink.exchanges.single().edited)
    }

    @Test
    fun breakpointResponsePhaseShowsMapLocalValueAsTheResponse() {
        // Precedence v3 (ADR-0033), the crux: a URL matches both a Map Local rule and a response-phase
        // breakpoint. The breakpoint owns the exchange and the Map Local value *becomes* the response it
        // pauses on — no network call. Resuming unchanged delivers that mocked response, flagged edited.
        WailoRuleStore.replace(listOf(MapLocalRule(id = "r1", enabled = true, url_pattern = "https://both.example.com/*")))
        WailoBreakpointStore.replace(listOf(bpRule("b1", "https://both.example.com/*", onResponse = true)))
        WailoControlChannel.bodyFetcher = FakeFetcher(
            WailoMappedResponse(code = 200, headers = listOf(Header("Content-Type", "application/json")), body = "{\"mapped\":true}".encodeUtf8()),
        )
        val gate = FakeGate(responseDecision = WailoResponseDecision.Proceed(null))
        WailoControlChannel.breakpointGate = gate
        val chain = FakeChain(request("https://both.example.com/users")) { networkResponse(it, body = "real") }

        val response = interceptor.intercept(chain)

        assertTrue("the response phase must pause on the Map Local value", gate.responsePaused)
        assertEquals("{\"mapped\":true}", gate.pausedResponse?.body?.utf8())
        assertEquals("{\"mapped\":true}", response.body!!.string())
        assertNull("Map Local must source the response, not the network", chain.proceededWith)
        assertTrue(sink.exchanges.single().edited)
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
        var requestPaused = false
            private set
        var responsePaused = false
            private set
        var pausedResponse: HttpResponse? = null
            private set

        override fun pauseRequest(ruleId: String, request: HttpRequest): WailoRequestDecision {
            requestPaused = true
            return requestDecision
        }

        override fun pauseResponse(ruleId: String, request: HttpRequest, response: HttpResponse): WailoResponseDecision {
            responsePaused = true
            pausedResponse = response
            return responseDecision
        }
    }

    private class FakeScriptTransformer(
        private val transform: (
            ScriptPhase,
            HttpRequest,
            HttpResponse?,
        ) -> ScriptTransformResult?,
    ) : WailoScriptTransformer {
        override fun transform(
            phase: ScriptPhase,
            request: HttpRequest,
            response: HttpResponse?,
            requestBodyReplayable: Boolean,
        ): ScriptTransformResult? = transform(phase, request, response)
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
