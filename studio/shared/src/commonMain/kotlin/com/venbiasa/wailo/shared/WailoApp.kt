package com.venbiasa.wailo.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.venbiasa.wailo.shared.theme.TextScale
import com.venbiasa.wailo.shared.theme.WailoTheme
import com.venbiasa.wailo.shared.ui.WailoViewer

/**
 * Root of the desktop inspector: a live, tailing request list over a Proxyman-style detail panel.
 *
 * Stateless over its inputs — the host ([com.venbiasa.wailo.desktop]) owns the engine and maps
 * captured rows into [entries]. [zoneOffsetMillis] converts each exchange's epoch timestamp to the
 * host's local wall clock (kept out of commonMain, which has no `java.time`). [textScale] is the
 * host-owned text-size multiplier (Cmd +/-); it rides on `fontScale` so only `sp` text resizes.
 * [darkTheme] and [onToggleDarkTheme] are likewise host-owned so the choice can persist across launches.
 * [listenAddress] is where the capture server accepts device connections; [capturing] reflects whether
 * traffic is being recorded, and [onToggleCapture]/[onClear] drive the top bar (the engine lives in the
 * host, not here).
 */
@Composable
fun WailoApp(
    entries: List<FlowEntry>,
    zoneOffsetMillis: Int = 0,
    darkTheme: Boolean = isSystemInDarkTheme(),
    onToggleDarkTheme: () -> Unit = {},
    textScale: Float = TextScale.Default,
    listenAddress: String = "",
    capturing: Boolean = true,
    onToggleCapture: () -> Unit = {},
    onClear: () -> Unit = {},
) {
    WailoTheme(darkTheme = darkTheme) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, density.fontScale * textScale),
        ) {
            WailoViewer(
                entries = entries,
                zoneOffsetMillis = zoneOffsetMillis,
                darkTheme = darkTheme,
                onToggleDarkTheme = onToggleDarkTheme,
                listenAddress = listenAddress,
                capturing = capturing,
                onToggleCapture = onToggleCapture,
                onClear = onClear,
            )
        }
    }
}
