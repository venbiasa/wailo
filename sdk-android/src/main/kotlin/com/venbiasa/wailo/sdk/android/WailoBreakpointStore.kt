package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.BreakpointRule

/**
 * Holds the breakpoint rules the desktop has pushed, and answers "should this request pause, and at
 * which phase(s)?" (ADR-0027). Mirrors [WailoRuleStore]: the desktop owns the rules, only ever sends
 * full snapshots ([replace]), and the set is dropped on disconnect so the desktop stays the single
 * source of truth. Process-global, `@Volatile`-published for the same reason.
 */
object WailoBreakpointStore {

    /** The first matching rule's id and which phases it pauses on. */
    data class Match(
        val ruleId: String,
        val onRequest: Boolean,
        val onResponse: Boolean,
    )

    @Volatile
    private var rules: List<BreakpointRule> = emptyList()

    /** Replace the whole rule set with the latest snapshot from the desktop. */
    fun replace(rules: List<BreakpointRule>) {
        this.rules = rules
    }

    /**
     * The first enabled rule that can pause at least one phase and whose method filter and URL wildcard
     * both match, or null if none do.
     */
    fun match(url: String, method: String): Match? {
        val rule = rules.firstOrNull { rule ->
            rule.enabled &&
                (rule.on_request || rule.on_response) &&
                methodMatches(rule.methods, method) &&
                urlWildcardMatches(rule.url_pattern, url)
        } ?: return null
        return Match(ruleId = rule.id, onRequest = rule.on_request, onResponse = rule.on_response)
    }
}
