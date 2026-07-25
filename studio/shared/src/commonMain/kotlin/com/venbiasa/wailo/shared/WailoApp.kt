package com.venbiasa.wailo.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.shared.theme.TextScale
import com.venbiasa.wailo.shared.theme.WailoTheme
import com.venbiasa.wailo.shared.ui.ToolPanelLayout
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
 * [captureFilter] is the host-owned, persisted capture filter (the allow/block host lists + each list's
 * on/off switch) the host pushes to devices, which gate whole exchanges at the source (ADR-0029);
 * [onCaptureFilterChange] hands back a new filter for any change (add/remove a host, flip a list).
 * [mapLocalNodes] are the host-owned, persisted Map Local layout (groups + rules, in priority order) the
 * right-side tool panel renders (ADR-0021/0026); [onMapLocalLayoutChange] hands back a new layout for any
 * structural change, and [onLoadMapLocalBody]/[onSaveMapLocalBody] read/persist a rule's authored body as
 * bytes (the host owns all file IO). [onPickMapLocalFile] opens the host's file picker for a body file
 * (JSON/text or image). [breakpointNodes] are the host-owned, persisted breakpoints layout (groups +
 * rules, in priority order) the same tool panel renders (ADR-0026/0027); [onBreakpointLayoutChange] hands
 * back a new layout for any structural change, [pausedFlows] are the requests/responses devices are
 * currently holding at a breakpoint, and [onResumeBreakpoint]/[onAbortBreakpoint] resolve one by
 * correlation id. The panel's open state and any row-seeded draft are the viewer's own transient state.
 * [toolPanelWidthRatio] is the host-owned, persisted width of that docked panel expressed as a
 * fraction of the window (so it scales with the window rather than pinning to a fixed dp);
 * [onToolPanelWidthRatioChange] hands back a new fraction as the user drags the panel's resize handle.
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
    captureFilter: CaptureFilterState = CaptureFilterState(),
    onCaptureFilterChange: (CaptureFilterState) -> Unit = {},
    mapLocalNodes: List<MapLocalNode> = emptyList(),
    onMapLocalLayoutChange: (List<MapLocalNode>) -> Unit = {},
    onLoadMapLocalBody: suspend (MapLocalRuleDef) -> ByteArray = { ByteArray(0) },
    onSaveMapLocalBody: suspend (MapLocalRuleDef, ByteArray) -> Unit = { _, _ -> },
    onPickMapLocalFile: suspend () -> PickedFile? = { null },
    breakpointNodes: List<BreakpointNode> = emptyList(),
    onBreakpointLayoutChange: (List<BreakpointNode>) -> Unit = {},
    pausedFlows: List<PausedFlow> = emptyList(),
    onResumeBreakpoint: (String, HttpRequest?, HttpResponse?) -> Unit = { _, _, _ -> },
    onAbortBreakpoint: (String) -> Unit = {},
    toolPanelWidthRatio: Float = ToolPanelLayout.DefaultWidthRatio,
    onToolPanelWidthRatioChange: (Float) -> Unit = {},
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
                captureFilter = captureFilter,
                onCaptureFilterChange = onCaptureFilterChange,
                mapLocalNodes = mapLocalNodes,
                onMapLocalLayoutChange = onMapLocalLayoutChange,
                onLoadMapLocalBody = onLoadMapLocalBody,
                onSaveMapLocalBody = onSaveMapLocalBody,
                onPickMapLocalFile = onPickMapLocalFile,
                breakpointNodes = breakpointNodes,
                onBreakpointLayoutChange = onBreakpointLayoutChange,
                pausedFlows = pausedFlows,
                onResumeBreakpoint = onResumeBreakpoint,
                onAbortBreakpoint = onAbortBreakpoint,
                toolPanelWidthRatio = toolPanelWidthRatio,
                onToolPanelWidthRatioChange = onToolPanelWidthRatioChange,
            )
        }
    }
}
