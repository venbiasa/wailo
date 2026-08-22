package com.venbiasa.wailo.shared

import com.venbiasa.wailo.protocol.HttpExchange

/**
 * One captured request/response as the desktop viewer consumes it: the session identity plus the
 * protobuf [HttpExchange].
 *
 * This mirrors `engine.CapturedExchange` by value on purpose. `shared` is a sibling of `engine`
 * (both are consumed by `desktopApp`) and must not depend on it, so the engine row is remapped here
 * at the `desktopApp` boundary. [id] is the exchange's UUID and is stable across engine emissions,
 * which is what keeps the list's `key`-based recomposition cheap.
 *
 * [edited] backs the list's Edited column — whether an interception/edit rule
 * altered this exchange. Editing isn't implemented yet, so it defaults false everywhere today; the
 * host will set it once request/response rewriting lands, without further UI changes.
 *
 * Every captured exchange has its full request/response bodies — the device gates whether the whole
 * exchange is captured at all (the CaptureFilter, ADR-0029), never whether its body is included — but
 * they are not *here*. [exchange] carries metadata with empty `body` fields, and the bytes are fetched
 * through [BodyLoader] against [requestBody]/[responseBody] when something actually displays them
 * (ADR-0069). A null handle means the body was empty.
 */
data class FlowEntry(
    val deviceName: String,
    val appId: String,
    val platform: String,
    val exchange: HttpExchange,
    val edited: Boolean = false,
    val requestBody: BodyHandle? = null,
    val responseBody: BodyHandle? = null,
    /**
     * Whether this row came through the bundled proxy rather than an instrumented app (ADR-0070). SDK
     * and proxy traffic share one timeline, so the row has to say which it is — an app's request and a
     * browser's are otherwise indistinguishable in a list of URLs.
     */
    val viaProxy: Boolean = false,
) {
    val id: String get() = exchange.id
}
