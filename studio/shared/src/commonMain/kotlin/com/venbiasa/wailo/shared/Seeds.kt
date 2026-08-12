package com.venbiasa.wailo.shared

import kotlin.random.Random

/**
 * A canned response the breakpoint window answers a held exchange with (ADR-0041). Authored exactly like
 * a Map Local rule — match on a URL wildcard + optional method, answer with a status, headers, and a body
 * the host keeps as an app-managed file keyed by [id] — minus the name: a seed is identified by what it
 * matches, and the window lists it by its `METHOD → pattern`.
 *
 * Unlike Map Local, a seed never reaches a device. It is consumed on the desktop, where a match resolves
 * the hold by sending the response back as the breakpoint's decision. Order is priority *and* sequence:
 * the armed queue is walked top-down, the first match wins, and it is then spent — so two seeds for the
 * same URL answer two successive requests differently. The spend itself lives in `host` (ADR-0055) so
 * CLI/MCP use the same matcher; this type is the UI-facing twin of `HostSeed`.
 */
data class SeedRuleDef(
    override val id: String,
    override val enabled: Boolean = true,
    val urlPattern: String = "",
    val method: String = "",
    val statusCode: Int = 200,
    val headers: List<ResponseHeader> = emptyList(),
) : LayoutRule<SeedRuleDef> {
    override fun withEnabled(enabled: Boolean): SeedRuleDef = copy(enabled = enabled)

    companion object {
        /** A stable, unique id for a freshly authored seed (no java.* so commonMain stays portable). */
        fun newId(): String = "seed-" + Random.nextLong().toULong().toString(16).padStart(16, '0')
    }
}

/**
 * The first seed in this (already ordered) list that answers [url]/[method], or null if none does.
 * Callers pass the armed queue, so list order is both the match priority and the sequence in which
 * repeated requests are answered.
 */
fun List<SeedRuleDef>.firstMatch(url: String, method: String): SeedRuleDef? = firstOrNull { seed ->
    seedMethodMatches(seed.method, method) && seedUrlMatches(seed.urlPattern, url)
}

/**
 * The queue with [seed] spent: removed by id, everything else left in order. A seed answers exactly one
 * hold, which is what lets an ordered queue script a sequence — two seeds for the same URL answer two
 * successive requests, in order. Spending is separate from [firstMatch] because the host only commits to
 * it once it has actually read the seed's body: a seed whose body file has gone missing must leave both
 * the hold and the queue untouched.
 */
fun List<SeedRuleDef>.consume(seed: SeedRuleDef): List<SeedRuleDef> = filterNot { it.id == seed.id }

/**
 * Whole-URL wildcard match, case-sensitive: `*` matches any run of characters, everything else is
 * literal. Deliberately the same scheme the devices use (`sdk-android`'s `WailoMatching.kt`, mirrored per
 * store in `sdk-ios`) — a seed is routinely copied from a Map Local rule, and the two would be a trap if
 * the same pattern matched on the device but not here. This is the first time matching runs on the
 * desktop at all: Map Local and breakpoints only ever push patterns and let the device decide.
 */
internal fun seedUrlMatches(pattern: String, url: String): Boolean {
    // An empty pattern can't be saved, but a decoded-from-prefs seed could still carry one; treat it as
    // inert rather than as a wildcard that swallows every hold.
    if (pattern.isEmpty()) return false
    return Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) }).matches(url)
}

/** Method filter: a blank pattern means "any method"; otherwise a case-insensitive comparison. */
internal fun seedMethodMatches(pattern: String, method: String): Boolean =
    pattern.isBlank() || pattern.equals(method, ignoreCase = true)
