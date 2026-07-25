package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.protocol.BreakpointRule
import com.venbiasa.wailo.shared.BreakpointLayoutCodec
import com.venbiasa.wailo.shared.BreakpointNode
import com.venbiasa.wailo.shared.rulesForMatch
import com.venbiasa.wailo.shared.settings.createKeyValueStore

/**
 * Persists the breakpoint layout (groups + rules + order) across launches, mirroring [MapLocalStore].
 * Host-owned because the layout lives at the desktop window and the active rules are pushed to devices by
 * the engine; `shared` only renders it and calls back with a new layout, staying stateless (ADR-0026/0027).
 * The layout rides in prefs as one string via the portable [BreakpointLayoutCodec], which also migrates
 * the pre-groups flat format so older prefs load unchanged.
 */
object BreakpointStore {
    private const val KEY = "breakpointRules"
    // The feature master (ADR-0030); defaults on so an install with no saved value keeps arming rules
    // exactly as before the switch existed. Gates only what the host pushes, never the saved layout.
    private const val ENABLED_KEY = "breakpointsEnabled"
    private val store = createKeyValueStore("desktop")

    fun load(): List<BreakpointNode> = BreakpointLayoutCodec.decode(store.getString(KEY, ""))

    fun save(nodes: List<BreakpointNode>) = store.putString(KEY, BreakpointLayoutCodec.encode(nodes))

    fun loadEnabled(): Boolean = store.getBoolean(ENABLED_KEY, true)

    fun saveEnabled(enabled: Boolean) = store.putBoolean(ENABLED_KEY, enabled)
}

/**
 * Compile the layout into the wire [BreakpointRule]s the engine pushes to devices: the *active* rules
 * (group-on AND rule-on) in top-to-bottom priority order (ADR-0026). Group-off / rule-off rules are
 * dropped here rather than shipped with a flag, so the device only ever holds rules that can fire —
 * matching how Map Local compiles its match-set.
 */
fun compileBreakpointRules(nodes: List<BreakpointNode>): List<BreakpointRule> =
    nodes.rulesForMatch().map { def ->
        BreakpointRule(
            id = def.id,
            enabled = true,
            url_pattern = def.urlPattern,
            methods = if (def.method.isBlank()) emptyList() else listOf(def.method),
            on_request = def.onRequest,
            on_response = def.onResponse,
        )
    }
