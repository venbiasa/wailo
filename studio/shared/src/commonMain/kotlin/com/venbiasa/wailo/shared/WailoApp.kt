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
 * Root of the desktop inspector: a live, tailing request list over a request/response detail panel.
 *
 * Stateless over its inputs — the host ([com.venbiasa.wailo.desktop]) owns the engine and maps
 * captured rows into [entries]. [zoneOffsetMillis] converts each exchange's epoch timestamp to the
 * host's local wall clock (kept out of commonMain, which has no `java.time`). [textScale] is the
 * host-owned text-size multiplier (Cmd +/-); it rides on `fontScale` so only `sp` text resizes.
 * [darkTheme] and [onToggleDarkTheme] are likewise host-owned so the choice can persist across launches.
 * [listenAddress] is where the capture server accepts device connections; [capturing] reflects whether
 * traffic is being recorded, and [onToggleCapture]/[onClear] drive the top bar (the engine lives in the
 * host, not here). [bookmarks] are the persisted, host-owned bookmarked hosts; [onAddBookmark]/
 * [onRemoveBookmark] let the viewer mutate that set (the host owns its persistence, ADR-0013).
 * [mapLocalRules] are the host-owned, persisted Map Local rules the right-side tool panel renders
 * (ADR-0021); [onUpsertRule]/[onRemoveRule] mutate them, and [onLoadMapLocalBody]/[onSaveMapLocalBody]
 * read/persist a rule's authored body as bytes (the host owns all file IO). [onPickMapLocalFile] opens
 * the host's file picker for a body file (JSON/text or image). The panel's open state and any row-seeded
 * draft are the viewer's own transient state.
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
    bookmarks: List<String> = emptyList(),
    onAddBookmark: (String) -> Unit = {},
    onRemoveBookmark: (String) -> Unit = {},
    mapLocalRules: List<MapLocalRuleDef> = emptyList(),
    onUpsertRule: (MapLocalRuleDef) -> Unit = {},
    onRemoveRule: (String) -> Unit = {},
    onLoadMapLocalBody: suspend (MapLocalRuleDef) -> ByteArray = { ByteArray(0) },
    onSaveMapLocalBody: suspend (MapLocalRuleDef, ByteArray) -> Unit = { _, _ -> },
    onPickMapLocalFile: suspend () -> PickedFile? = { null },
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
                bookmarks = bookmarks,
                onAddBookmark = onAddBookmark,
                onRemoveBookmark = onRemoveBookmark,
                mapLocalRules = mapLocalRules,
                onUpsertRule = onUpsertRule,
                onRemoveRule = onRemoveRule,
                onLoadMapLocalBody = onLoadMapLocalBody,
                onSaveMapLocalBody = onSaveMapLocalBody,
                onPickMapLocalFile = onPickMapLocalFile,
            )
        }
    }
}
