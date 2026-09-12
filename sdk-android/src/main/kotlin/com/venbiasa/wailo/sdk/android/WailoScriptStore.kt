package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.ScriptPhase
import com.venbiasa.wailo.protocol.ScriptRule

/** Match metadata for daemon-owned Scripts. Source and execution never enter the host app. */
object WailoScriptStore {
    private data class Snapshot(
        val owner: Any?,
        val rules: List<ScriptRule>,
    )

    private val lock = Any()

    @Volatile
    private var snapshot = Snapshot(owner = null, rules = emptyList())

    fun replace(rules: List<ScriptRule>) {
        synchronized(lock) {
            snapshot = Snapshot(owner = null, rules = rules)
        }
    }

    internal fun replace(rules: List<ScriptRule>, owner: Any) {
        synchronized(lock) {
            snapshot = Snapshot(owner = owner, rules = rules)
        }
    }

    internal fun reset(owner: Any) {
        synchronized(lock) {
            if (snapshot.owner === owner) snapshot = Snapshot(owner = null, rules = emptyList())
        }
    }

    fun matches(url: String, method: String, phase: ScriptPhase): Boolean =
        snapshot.rules.any { rule ->
            rule.enabled &&
                when (phase) {
                    ScriptPhase.SCRIPT_PHASE_REQUEST -> rule.on_request
                    ScriptPhase.SCRIPT_PHASE_RESPONSE -> rule.on_response
                    else -> false
                } &&
                methodMatches(rule.methods, method) &&
                urlWildcardMatches(rule.url_pattern, url)
        }
}
