package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.MapLocalRule

/**
 * Holds the Map Local *match-metadata* the desktop has pushed, and answers "does this request match a
 * rule?" (ADR-0019). No response bodies live here — a match only yields the rule id, and the body is
 * fetched from the desktop on demand ([WailoBodyFetcher]). The desktop owns the rules; the device only
 * ever receives full snapshots ([replace]) and drops them on disconnect, so there is no merge logic and
 * the desktop stays the single source of truth.
 *
 * Process-global (like the iOS `WailoRuleStore.shared`): the [WailoClient] receive loop is the single
 * writer and the [WailoInterceptor] reads from arbitrary OkHttp threads, decoupled by publishing an
 * immutable snapshot through a `@Volatile` reference.
 */
object WailoRuleStore {

    @Volatile
    private var rules: List<MapLocalRule> = emptyList()

    /** Replace the whole rule set with the latest snapshot from the desktop. */
    fun replace(rules: List<MapLocalRule>) {
        this.rules = rules
    }

    /** The first enabled rule whose method filter and URL wildcard both match, or null if none do. */
    fun match(url: String, method: String): MapLocalRule? =
        rules.firstOrNull { rule ->
            rule.enabled &&
                methodMatches(rule.methods, method) &&
                urlWildcardMatches(rule.url_pattern, url)
        }
}
