package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.CaptureFilter

/**
 * Holds the capture filter the desktop has pushed (ADR-0029): two independent host-pattern lists — an
 * allowlist and a blocklist, each with its own on/off switch — that decide which exchanges the device
 * captures and streams. The gate is whole-exchange: a host that passes is captured in full, one that
 * doesn't is dropped at the source (there is no metadata-only capture).
 *
 * The default (and the state after every disconnect, via [reset]) is "both lists off" = capture
 * everything, matching the desktop's default and keeping the pre-push / no-desktop path permissive so
 * a local aid like [LogcatSink] still sees traffic. The engine re-pushes the current filter on every
 * connect, so a real allowlist/blocklist reasserts itself within one frame of reconnecting. Mirrors the
 * iOS `WailoCaptureFilterStore`.
 */
object WailoCaptureFilterStore {

    private data class Snapshot(
        val owner: Any?,
        val filter: CaptureFilter,
    )

    private val lock = Any()

    @Volatile
    private var snapshot = Snapshot(owner = null, filter = CaptureFilter())

    /** Replace the filter with the latest snapshot from the desktop. */
    fun replace(filter: CaptureFilter) {
        synchronized(lock) {
            snapshot = Snapshot(owner = null, filter = filter)
        }
    }

    internal fun replace(filter: CaptureFilter, owner: Any) {
        synchronized(lock) {
            snapshot = Snapshot(owner = owner, filter = filter)
        }
    }

    /** Forget the pushed filter on disconnect: fall back to capture-everything until the desktop re-pushes. */
    fun reset() {
        synchronized(lock) {
            snapshot = Snapshot(owner = null, filter = CaptureFilter())
        }
    }

    internal fun reset(owner: Any) {
        synchronized(lock) {
            if (snapshot.owner === owner) snapshot = Snapshot(owner = null, filter = CaptureFilter())
        }
    }

    /**
     * Whether [host]'s exchange should be captured and streamed. An enabled allowlist requires a match;
     * an enabled blocklist rejects a match; with both on a host must be allowed and not blocked; with
     * both off (the default) everything is captured. An enabled list with no patterns matches nothing,
     * so an enabled-but-empty allowlist captures nothing and an enabled-but-empty blocklist blocks
     * nothing. A null/empty host only fails an enabled allowlist (it can't match a real pattern).
     */
    fun shouldCapture(host: String?): Boolean {
        val filter = snapshot.filter
        val target = host ?: ""
        if (filter.allowlist_enabled && filter.allow_patterns.none { hostWildcardMatches(it, target) }) {
            return false
        }
        if (filter.blocklist_enabled && filter.block_patterns.any { hostWildcardMatches(it, target) }) {
            return false
        }
        return true
    }
}
