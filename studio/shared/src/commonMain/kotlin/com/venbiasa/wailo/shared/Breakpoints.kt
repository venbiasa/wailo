package com.venbiasa.wailo.shared

import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import kotlin.random.Random

/**
 * A breakpoint rule as the desktop authors it: match a request, and pause it before it is sent
 * ([onRequest]) and/or before its response is delivered ([onResponse]). The UI/host-facing definition,
 * decoupled from the wire type — the host compiles it into the protocol `BreakpointRule` before pushing
 * (mirroring Map Local's `MapLocalRuleDef` -> `MapLocalRule`), so `shared` never touches the wire types
 * for rule authoring.
 *
 * [urlPattern] is a wildcard match against the full request URL (`*` matches any run of characters).
 * [method] restricts the rule to that single HTTP method; blank means any. A rule with neither phase
 * enabled never fires, so a new rule defaults [onResponse] on: tampering with what the app is about to
 * receive is the common case, while holding the outbound request is the deliberate opt-in.
 */
data class BreakpointRuleDef(
    override val id: String,
    override val enabled: Boolean = true,
    val urlPattern: String = "",
    val method: String = "",
    val onRequest: Boolean = false,
    val onResponse: Boolean = true,
) : LayoutRule<BreakpointRuleDef> {
    override fun withEnabled(enabled: Boolean): BreakpointRuleDef = copy(enabled = enabled)

    companion object {
        /** A stable, unique id for a freshly authored rule (no java.* so commonMain stays portable). */
        fun newId(): String = "bp-" + Random.nextLong().toULong().toString(16).padStart(16, '0')
    }
}

/**
 * A request/response a device has paused at a breakpoint, as the desktop editor consumes it. Mirrors
 * `engine.PausedExchange` by value (the engine row is remapped at the `desktopApp` boundary, since
 * `shared` must not depend on `engine`). [phase] says which side is held; [request] is always present
 * and [response] is set only for the RESPONSE phase. [correlationId] is the stable key the editor uses
 * and the host passes back to resume/abort this exact hold.
 */
data class PausedFlow(
    val correlationId: String,
    val deviceName: String,
    val appId: String,
    val platform: String,
    val phase: BreakpointPhase,
    val request: HttpRequest?,
    val response: HttpResponse?,
)
