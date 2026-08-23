package com.venbiasa.wailo.shared

import kotlin.random.Random

/**
 * A Map Local rule as the desktop authors it: match a request, answer it with a canned response. This
 * is the UI-facing definition and holds no bytes — the body travels beside it, because the daemon is
 * where it lives (ADR-0085), so `shared` never touches the filesystem or the wire types.
 *
 * [name] is a human label for the rule (what the list shows and how the author identifies it); it
 * defaults to "Untitled" for a fresh rule and must be non-blank to save. It is not part of matching.
 * [urlPattern] is a wildcard match against the full request URL (`*` matches any run of characters).
 * [method] restricts the rule to that single HTTP method; blank means any. [statusCode] and [headers]
 * shape the synthesized response; [headers] carries Content-Type (there is no separate field for it) —
 * if none is set the daemon infers Content-Type from the body, and it always sets Content-Length from
 * the served bytes, so a hand-entered length is ignored.
 *
 * [inline] and [filePath] are what remains of the original design, where a rule could serve the user's
 * own file re-read per request. Serving moved to the daemon, which made every rule a snapshot, so an
 * authored rule is always inline now; the pair survives only so an archive exported before that still
 * round-trips (see `RuleArchive`).
 */
data class MapLocalRuleDef(
    override val id: String,
    val name: String = "Untitled",
    override val enabled: Boolean = true,
    val urlPattern: String = "",
    val method: String = "",
    val filePath: String = "",
    val statusCode: Int = 200,
    val headers: List<ResponseHeader> = emptyList(),
    val inline: Boolean = false,
) : LayoutRule<MapLocalRuleDef> {
    override fun withEnabled(enabled: Boolean): MapLocalRuleDef = copy(enabled = enabled)

    companion object {
        /** A stable, unique id for a freshly authored rule (no java.* so commonMain stays portable). */
        fun newId(): String = "rule-" + Random.nextLong().toULong().toString(16).padStart(16, '0')
    }
}
