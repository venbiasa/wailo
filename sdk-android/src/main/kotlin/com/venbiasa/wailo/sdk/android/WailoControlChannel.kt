package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import okio.ByteString

/**
 * The response a matched Map Local rule resolves to, fetched from the desktop on demand (ADR-0019).
 * Bodies are never cached on the device — this exists only for the life of the one request it answers.
 */
data class WailoMappedResponse(
    val code: Int,
    val headers: List<Header>,
    val body: ByteString,
)

/**
 * Resolves a matched rule to its response by asking the desktop (the authority) for the bytes. Unlike
 * the iOS callback form, this is **blocking**: an OkHttp interceptor runs on a background thread and
 * must return a `Response`, so the fetch blocks that thread (with a timeout) rather than continuing
 * asynchronously. Returns null when the fetch can't complete (not connected, timeout, rule gone), and
 * the interceptor then falls open to the real network. [WailoClient] is the implementation.
 */
interface WailoBodyFetcher {
    fun fetchBody(ruleId: String, url: String, method: String): WailoMappedResponse?
}

/** The desktop's decision for a request held at a breakpoint. */
sealed interface WailoRequestDecision {
    /** Send [edited] instead of the original, or the original unchanged when null (also the fail-open case). */
    data class Proceed(val edited: HttpRequest?) : WailoRequestDecision

    /** Fail the app's call. */
    data object Abort : WailoRequestDecision
}

/** The desktop's decision for a response held at a breakpoint. */
sealed interface WailoResponseDecision {
    /** Deliver [edited] instead of the original, or the original unchanged when null (also the fail-open case). */
    data class Proceed(val edited: HttpResponse?) : WailoResponseDecision

    /** Fail the app's call. */
    data object Abort : WailoResponseDecision
}

/**
 * Pauses a matched request/response and awaits the desktop's decision (ADR-0027). Blocking, like
 * [WailoBodyFetcher], but with **no timeout** — a human is deciding, so the hold lasts until a
 * `BreakpointDecision` arrives or the link drops (a disconnect fails open, `Proceed(null)`), which is
 * why a desktop that never answers can't hang the app's call forever. [WailoClient] is the implementation.
 */
interface WailoBreakpointGate {
    fun pauseRequest(ruleId: String, request: HttpRequest): WailoRequestDecision

    fun pauseResponse(ruleId: String, request: HttpRequest, response: HttpResponse): WailoResponseDecision
}

/**
 * Process-global handles to the desktop control channel, set by [WailoClient.start] and cleared on
 * stop. The [WailoInterceptor] is constructed per OkHttp client (and often wraps a composite sink that
 * hides the transport), so — exactly like the iOS `WailoURLProtocol` statics — it reaches the connected
 * client through these globals rather than a constructor dependency. Null means "no desktop authority":
 * Map Local falls open to the network and breakpoints never fire (the stores are empty then anyway,
 * since only a connected desktop pushes rules).
 */
object WailoControlChannel {

    @Volatile
    var bodyFetcher: WailoBodyFetcher? = null

    @Volatile
    var breakpointGate: WailoBreakpointGate? = null
}
