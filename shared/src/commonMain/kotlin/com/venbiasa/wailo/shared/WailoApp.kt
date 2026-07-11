package com.venbiasa.wailo.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.venbiasa.wailo.shared.theme.WailoTheme
import com.venbiasa.wailo.shared.ui.WailoViewer

/**
 * Root of the desktop inspector: a live, tailing request list over a Proxyman-style detail panel.
 *
 * Stateless over its inputs — the host ([com.venbiasa.wailo.desktop]) owns the engine and maps
 * captured rows into [entries]. [zoneOffsetMillis] converts each exchange's epoch timestamp to the
 * host's local wall clock (kept out of commonMain, which has no `java.time`).
 */
@Composable
fun WailoApp(
    entries: List<FlowEntry>,
    zoneOffsetMillis: Int = 0,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    WailoTheme(darkTheme = darkTheme) {
        WailoViewer(entries = entries, zoneOffsetMillis = zoneOffsetMillis)
    }
}
