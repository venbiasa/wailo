package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.MapLocalRule

/**
 * Holds the Map Local *match-metadata* the desktop has pushed, and answers "does this request match a
 * rule?" (ADR-0019). No response bodies live here — a match only yields the rule id, and the body is
 * fetched from the desktop on demand ([WailoBodyFetcher]). The desktop owns the rules; the device only
 * ever receives full snapshots ([replace]) and drops them on disconnect, so there is no merge logic and
 * the desktop stays the single source of truth.
 *
 * Process-global (like the iOS `WailoRuleStore.shared`): the active [WailoClient] publishes immutable
 * snapshots and the [WailoInterceptor] reads them from arbitrary OkHttp threads. Snapshot ownership
 * keeps a retiring client from clearing rules installed by its replacement.
 */
object WailoRuleStore {

    private data class Snapshot(
        val owner: Any?,
        val rules: List<MapLocalRule>,
    )

    private val lock = Any()

    @Volatile
    private var snapshot = Snapshot(owner = null, rules = emptyList())

    /** Replace the whole rule set with the latest snapshot from the desktop. */
    fun replace(rules: List<MapLocalRule>) {
        synchronized(lock) {
            snapshot = Snapshot(owner = null, rules = rules)
        }
    }

    internal fun replace(rules: List<MapLocalRule>, owner: Any) {
        synchronized(lock) {
            snapshot = Snapshot(owner = owner, rules = rules)
        }
    }

    internal fun reset(owner: Any) {
        synchronized(lock) {
            if (snapshot.owner === owner) snapshot = Snapshot(owner = null, rules = emptyList())
        }
    }

    /** The first enabled rule whose method filter and URL wildcard both match, or null if none do. */
    fun match(url: String, method: String): MapLocalRule? =
        snapshot.rules.firstOrNull { rule ->
            rule.enabled &&
                methodMatches(rule.methods, method) &&
                urlWildcardMatches(rule.url_pattern, url)
        }
}
