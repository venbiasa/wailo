package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.shared.BreakpointNode
import com.venbiasa.wailo.shared.CaptureFilterState
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.MapLocalHeader
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.PausedFlow
import com.venbiasa.wailo.shared.PickedFile
import com.venbiasa.wailo.shared.format.requestHost
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_breakpoint
import com.venbiasa.wailo.shared.resources.ic_dark_mode
import com.venbiasa.wailo.shared.resources.ic_delete
import com.venbiasa.wailo.shared.resources.ic_light_mode
import com.venbiasa.wailo.shared.resources.ic_lock
import com.venbiasa.wailo.shared.resources.ic_pause
import com.venbiasa.wailo.shared.resources.ic_play_arrow
import com.venbiasa.wailo.shared.resources.ic_rule
import com.venbiasa.wailo.shared.theme.LocalWailoColors
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

@Composable
internal fun WailoViewer(
    entries: List<FlowEntry>,
    zoneOffsetMillis: Int,
    darkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    listenAddress: String,
    capturing: Boolean,
    onToggleCapture: () -> Unit,
    onClear: () -> Unit,
    bookmarks: List<String>,
    onAddBookmark: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    captureFilter: CaptureFilterState,
    onCaptureFilterChange: (CaptureFilterState) -> Unit,
    mapLocalNodes: List<MapLocalNode>,
    onMapLocalLayoutChange: (List<MapLocalNode>) -> Unit,
    onLoadMapLocalBody: suspend (MapLocalRuleDef) -> ByteArray,
    onSaveMapLocalBody: suspend (MapLocalRuleDef, ByteArray) -> Unit,
    onPickMapLocalFile: suspend () -> PickedFile?,
    breakpointNodes: List<BreakpointNode>,
    onBreakpointLayoutChange: (List<BreakpointNode>) -> Unit,
    pausedFlows: List<PausedFlow>,
    onResumeBreakpoint: (String, HttpRequest?, HttpResponse?) -> Unit,
    onAbortBreakpoint: (String) -> Unit,
    toolPanelWidthRatio: Float,
    onToolPanelWidthRatioChange: (Float) -> Unit,
) {
    // Transient view state (not persisted): nothing is selected when the app opens.
    var selectedId by remember { mutableStateOf<String?>(null) }
    // Resolve the open detail against the full list, not the filtered one, so switching bookmarks
    // never closes a detail panel whose row is currently filtered out.
    val selected = remember(entries, selectedId) { entries.firstOrNull { it.id == selectedId } }

    // The active host filter is transient view state (like the selection above), not persisted: the
    // app opens showing all traffic. `null` means "All".
    var activeHost by remember { mutableStateOf<String?>(null) }
    // Drop the filter if its host stops being a bookmark (removed via a row or chip menu).
    LaunchedEffect(bookmarks) {
        if (activeHost != null && activeHost !in bookmarks) activeHost = null
    }
    val visibleEntries = remember(entries, activeHost) {
        val host = activeHost
        if (host == null) entries else entries.filter { requestHost(it.exchange.request?.url ?: "") == host }
    }

    // Map Local tool panel: its open/close, the current draft, and the body seed are transient view
    // state (like the selection/filter above). The host owns the rules and their persistence — the
    // panel only renders them (ADR-0013/0021).
    var mapLocalOpen by remember { mutableStateOf(false) }
    var mapLocalDraft by remember { mutableStateOf<MapLocalRuleDef?>(null) }
    var mapLocalBodySeed by remember { mutableStateOf<ByteArray?>(null) }
    // The capture-filter panel shares the single docked tool slot with Map Local — opening one closes
    // the other — so the layout never reasons about two side panels at once, and both size themselves from
    // the one host-owned [toolPanelWidthRatio]: a width dragged for either panel persists across restarts
    // and carries over to the other. It's a fraction of the window, not a fixed dp, so the panel scales
    // with the window (clamped to keep both panel and content usable, see ToolPanelLayout).
    var captureOpen by remember { mutableStateOf(false) }
    // The breakpoints panel shares the same single docked tool slot as Map Local and the capture
    // filter — opening any one closes the others.
    var breakpointsOpen by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val totalWidth = maxWidth
            Row(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val minDetail = 180.dp
                    val maxDetail = (maxHeight - 160.dp).coerceAtLeast(minDetail)
                    var detailHeight by remember { mutableStateOf(360.dp) }

                    Column(Modifier.fillMaxSize()) {
                        TopBar(
                            listenAddress = listenAddress,
                            capturing = capturing,
                            onToggleCapture = onToggleCapture,
                            onClear = onClear,
                        )
                        RowDivider()
                        // The bookmark bar only exists once there is something to show, so an empty setup
                        // costs no vertical space and reads exactly like the pre-bookmark viewer.
                        if (bookmarks.isNotEmpty()) {
                            BookmarkBar(
                                bookmarks = bookmarks,
                                activeHost = activeHost,
                                onSelect = { activeHost = it },
                                onRemove = onRemoveBookmark,
                            )
                            RowDivider()
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            TrafficList(
                                entries = visibleEntries,
                                selectedId = selectedId,
                                onSelect = { selectedId = it },
                                zoneOffsetMillis = zoneOffsetMillis,
                                bookmarks = bookmarks,
                                onAddBookmark = onAddBookmark,
                                onRemoveBookmark = onRemoveBookmark,
                                allowHosts = captureFilter.allowHosts,
                                blockHosts = captureFilter.blockHosts,
                                // Row toggles add/remove the exact host in a list. They don't flip the
                                // list's switch — arming stays a deliberate act in the capture-filter
                                // panel (a stray click can't silently filter all traffic).
                                onToggleAllowHost = { host ->
                                    onCaptureFilterChange(
                                        if (host in captureFilter.allowHosts) captureFilter.removeAllow(host)
                                        else captureFilter.addAllow(host),
                                    )
                                },
                                onToggleBlockHost = { host ->
                                    onCaptureFilterChange(
                                        if (host in captureFilter.blockHosts) captureFilter.removeBlock(host)
                                        else captureFilter.addBlock(host),
                                    )
                                },
                                // A row's "Map Local…" seeds a fresh draft (exact URL + method + the
                                // captured body's bytes — JSON or image) and opens the tool panel — no
                                // separate window (ADR-0021).
                                onMapLocalFromUrl = { url, method, responseHeaders, seed ->
                                    mapLocalDraft = MapLocalRuleDef(
                                        id = MapLocalRuleDef.newId(),
                                        urlPattern = url,
                                        method = method,
                                        inline = true,
                                        // Start the mock close to the observed response: its real headers,
                                        // minus the ones that describe the live transfer rather than the
                                        // payload (see seededHeaders).
                                        headers = seededHeaders(responseHeaders),
                                    )
                                    mapLocalBodySeed = seed
                                    mapLocalOpen = true
                                },
                            )
                        }
                        if (selected != null) {
                            DragHandle { deltaPx ->
                                val deltaDp = with(density) { deltaPx.toDp() }
                                detailHeight = (detailHeight - deltaDp).coerceIn(minDetail, maxDetail)
                            }
                            DetailPanel(
                                entry = selected,
                                modifier = Modifier.fillMaxWidth().height(detailHeight.coerceIn(minDetail, maxDetail)),
                                onClose = { selectedId = null },
                            )
                        }
                    }
                }
                // The docked tool panel — Map Local or the capture filter, never both — sits to the
                // right, resizable, between the content and the tool rail (the Android Studio tool-window
                // model). One [toolPanelWidthRatio] backs the single slot, so a width dragged for one panel
                // is exactly the width the other opens at, and it persists across restarts.
                if (mapLocalOpen || captureOpen || breakpointsOpen) {
                    val panelWidth = ToolPanelLayout.widthFor(toolPanelWidthRatio, totalWidth)
                    PanelResizeHandle { deltaPx ->
                        // Drag the handle left (negative delta) to widen; convert the new width back to a
                        // window fraction so it scales with the window and the host can persist it.
                        val dragged = panelWidth - with(density) { deltaPx.toDp() }
                        onToolPanelWidthRatioChange(ToolPanelLayout.ratioFor(dragged, totalWidth))
                    }
                    Box(Modifier.width(panelWidth).fillMaxHeight()) {
                        when {
                            mapLocalOpen -> MapLocalManager(
                                nodes = mapLocalNodes,
                                initialDraft = mapLocalDraft,
                                initialBodySeed = mapLocalBodySeed,
                                onLayoutChange = onMapLocalLayoutChange,
                                onClose = { mapLocalOpen = false },
                                onLoadBody = onLoadMapLocalBody,
                                onSaveBody = onSaveMapLocalBody,
                                onPickFile = onPickMapLocalFile,
                            )
                            captureOpen -> CaptureFilterManager(
                                filter = captureFilter,
                                onFilterChange = onCaptureFilterChange,
                                onClose = { captureOpen = false },
                            )
                            else -> BreakpointManager(
                                nodes = breakpointNodes,
                                onLayoutChange = onBreakpointLayoutChange,
                                onClose = { breakpointsOpen = false },
                            )
                        }
                    }
                }
                ColumnDivider()
                ToolRail(
                    darkTheme = darkTheme,
                    onToggleDarkTheme = onToggleDarkTheme,
                    mapLocalOpen = mapLocalOpen,
                    onToggleMapLocal = {
                        if (mapLocalOpen) {
                            mapLocalOpen = false
                        } else {
                            // Opening from the rail lands on the rule list, not a seeded draft.
                            mapLocalDraft = null
                            mapLocalBodySeed = null
                            mapLocalOpen = true
                            captureOpen = false
                            breakpointsOpen = false
                        }
                    },
                    captureOpen = captureOpen,
                    onToggleCapture = {
                        captureOpen = !captureOpen
                        if (captureOpen) {
                            mapLocalOpen = false
                            breakpointsOpen = false
                        }
                    },
                    breakpointsOpen = breakpointsOpen,
                    onToggleBreakpoints = {
                        breakpointsOpen = !breakpointsOpen
                        if (breakpointsOpen) {
                            mapLocalOpen = false
                            captureOpen = false
                        }
                    },
                )
            }
            // The paused-traffic editor floats above everything while a device is holding a request or
            // response at a breakpoint (ADR-0027). One hold is edited at a time; resolving it reveals the
            // next. It is not gated by the docked panel — a hold can arrive whether or not the rules
            // panel is open.
            pausedFlows.firstOrNull()?.let { paused ->
                BreakpointEditor(
                    paused = paused,
                    onResume = onResumeBreakpoint,
                    onAbort = onAbortBreakpoint,
                )
            }
        }
    }
}

private val ToolRailWidth = 48.dp

// Response headers not carried into a rule seeded from a row: they describe the live transfer, not the
// payload, so they'd be wrong (or break the mock) once the desktop serves its own bytes. The host
// recomputes Content-Length (ADR-0019); Transfer-Encoding/Connection are hop-by-hop; and a leftover
// Content-Encoding (e.g. gzip) would tell the client to decode our already-decoded body.
private val NonSeededHeaderNames = setOf(
    "content-length",
    "content-encoding",
    "transfer-encoding",
    "connection",
)

// Builds a seeded rule's headers from the captured response: keep the real headers so the mock starts
// close to what was observed, drop the transfer-only ones, and guarantee a Content-Type (Map Local is
// JSON-focused, ADR-0021) when the response carried none.
private fun seededHeaders(responseHeaders: List<Header>): List<MapLocalHeader> {
    val kept = responseHeaders
        .filterNot { it.name.lowercase() in NonSeededHeaderNames }
        .map { MapLocalHeader(it.name, it.value_) }
    return if (kept.any { it.name.equals("Content-Type", ignoreCase = true) }) {
        kept
    } else {
        kept + MapLocalHeader("Content-Type", "application/json")
    }
}

/**
 * The right tool rail: an icon-only strip (Android Studio's right tool bar). The dark/light toggle
 * leads as a global app action; below a divider come the side-panel tools — today just Map Local,
 * whose button stays highlighted while its panel is open.
 */
@Composable
private fun ToolRail(
    darkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    mapLocalOpen: Boolean,
    onToggleMapLocal: () -> Unit,
    captureOpen: Boolean,
    onToggleCapture: () -> Unit,
    breakpointsOpen: Boolean,
    onToggleBreakpoints: () -> Unit,
) {
    Column(
        Modifier.fillMaxHeight()
            .width(ToolRailWidth)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Icon reflects the target mode (moon = switch to dark, sun = switch to light), the common
        // toggle convention so a glance tells you what clicking will do. It's an app-wide action, not a
        // tool panel, so it never carries the selected highlight and sits above the tool divider.
        ToolRailButton(
            icon = if (darkTheme) Res.drawable.ic_light_mode else Res.drawable.ic_dark_mode,
            contentDescription = if (darkTheme) "Switch to light mode" else "Switch to dark mode",
            selected = false,
            onClick = onToggleDarkTheme,
        )
        RailDivider()
        ToolRailButton(
            icon = Res.drawable.ic_lock,
            contentDescription = "Capture Filter",
            selected = captureOpen,
            onClick = onToggleCapture,
        )
        ToolRailButton(
            icon = Res.drawable.ic_rule,
            contentDescription = "Map Local",
            selected = mapLocalOpen,
            onClick = onToggleMapLocal,
        )
        ToolRailButton(
            icon = Res.drawable.ic_breakpoint,
            contentDescription = "Breakpoints",
            selected = breakpointsOpen,
            onClick = onToggleBreakpoints,
        )
    }
}

// Groups the rail's global app actions (theme) apart from its tool-panel buttons; inset so it reads as
// a group separator rather than spanning the full rail width.
@Composable
private fun RailDivider() {
    Box(
        Modifier.padding(horizontal = 10.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun ToolRailButton(
    icon: DrawableResource,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    val tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    // The rail is icon-only; hovering names the tool so an unfamiliar glyph is still legible.
    HoverTooltip(label = contentDescription) {
        Box(
            Modifier.size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(background)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = vectorResource(icon),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// The seam between the content and the docked tool panel: a visible grip with a wide invisible grab
// strip and a horizontal resize cursor on hover, mirroring the detail panel's vertical [DragHandle].
@Composable
private fun PanelResizeHandle(onDragDelta: (Float) -> Unit) {
    Box(
        Modifier.fillMaxHeight()
            .width(9.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { onDragDelta(it) },
            )
            .resizeCursor(ResizeAxis.Horizontal),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(3.dp).height(36.dp).background(MaterialTheme.colorScheme.outline))
    }
}

@Composable
private fun TopBar(
    listenAddress: String,
    capturing: Boolean,
    onToggleCapture: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .height(TopBarHeight)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pause/resume recording; the icon shows the action, not the current state. The server keeps
        // listening while paused — only new exchanges are dropped.
        IconButton(onClick = onToggleCapture, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = vectorResource(if (capturing) Res.drawable.ic_pause else Res.drawable.ic_play_arrow),
                contentDescription = if (capturing) "Pause capturing" else "Resume capturing",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_delete),
                contentDescription = "Clear captured traffic",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        // The address a device should dial, prefixed by a recording dot: green while capturing, muted
        // when paused, so the dot always agrees with the pause/resume button.
        Box(
            Modifier.size(8.dp).background(
                color = if (capturing) LocalWailoColors.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = CircleShape,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            listenAddress,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DragHandle(onDragDelta: (Float) -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .height(9.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { onDragDelta(it) },
            )
            .resizeCursor(ResizeAxis.Vertical),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(36.dp).height(3.dp).background(MaterialTheme.colorScheme.outline))
    }
}
