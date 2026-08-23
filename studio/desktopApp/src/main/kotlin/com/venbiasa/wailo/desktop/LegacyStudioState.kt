package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.settings.createKeyValueStore
import java.io.File

/**
 * Removes the state Studio used to keep beside the daemon's (ADR-0085).
 *
 * Everything named here now has exactly one home — the daemon's state directory — and this window reads
 * it back rather than remembering it. Left in place the leftovers are worse than untidy: a preferences
 * file is per-user while the daemon's state is per-`WAILO_HOME`, so a stale local copy is precisely the
 * thing that used to resurrect deleted rules against a fresh daemon.
 *
 * Runs once per launch and is cheap when there is nothing to do, so no "already swept" flag: a flag would
 * be one more orphan, and an install that skipped a version still gets cleaned.
 */
internal fun sweepLegacyStudioState() {
    val store = createKeyValueStore("desktop")
    LegacyKeys.forEach(store::remove)
    // The authored bodies these keys pointed at. The daemon holds each rule's bytes inline, so this is a
    // copy, not the original — but it is still the user's data, so it goes only with the layout that
    // referenced it.
    listOf("maplocal-bodies", "seed-bodies").forEach { name ->
        runCatching { File(appDataDir(), name).deleteRecursively() }
    }
}

private val LegacyKeys = listOf(
    // Settings the daemon persists and every frontend applies (ADR-0058).
    "listenPort",
    "usbDevicePort",
    "maxRetained",
    "requirePairing",
    // Authored rules, now daemon-owned down to the grouping (ADR-0081) and the bodies (ADR-0085).
    "mapLocalRules",
    "mapLocalEnabled",
    "seedRules",
    "seedsEnabled",
    "breakpointRules",
    "breakpointsEnabled",
    "captureFilterEnabled",
    "captureAllowEnabled",
    "captureAllowHosts",
    "captureAllowlistHosts",
    "captureBlockEnabled",
    "captureBlockHosts",
    // Bookmarked hosts, moved so a headless session can see them too (ADR-0084).
    "bookmarkedHosts",
)
