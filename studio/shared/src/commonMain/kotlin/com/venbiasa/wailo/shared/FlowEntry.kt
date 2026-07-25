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
 * Every captured exchange carries its full request/response bodies: the device gates whether the whole
 * exchange is captured at all (the CaptureFilter, ADR-0029), never whether its body is included, so there
 * is no metadata-only flag here.
 */
data class FlowEntry(
    val deviceName: String,
    val appId: String,
    val platform: String,
    val exchange: HttpExchange,
    val edited: Boolean = false,
) {
    val id: String get() = exchange.id
}
