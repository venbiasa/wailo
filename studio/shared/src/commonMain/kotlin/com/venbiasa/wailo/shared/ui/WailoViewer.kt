package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.shared.BreakpointNode
import com.venbiasa.wailo.shared.BreakpointRuleDef
import com.venbiasa.wailo.shared.CaptureFilterState
import com.venbiasa.wailo.shared.DeviceInfo
import com.venbiasa.wailo.shared.PairingAction
import com.venbiasa.wailo.shared.PairingState
import com.venbiasa.wailo.shared.FilterKey
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.MapLocalHeader
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.PickedFile
import com.venbiasa.wailo.shared.TrafficFilter
import com.venbiasa.wailo.shared.format.requestHost
import com.venbiasa.wailo.shared.matches
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_breakpoint
import com.venbiasa.wailo.shared.resources.ic_dark_mode
import com.venbiasa.wailo.shared.resources.ic_delete
import com.venbiasa.wailo.shared.resources.ic_devices
import com.venbiasa.wailo.shared.resources.ic_light_mode
import com.venbiasa.wailo.shared.resources.ic_lock
import com.venbiasa.wailo.shared.resources.ic_pause
import com.venbiasa.wailo.shared.resources.ic_play_arrow
import com.venbiasa.wailo.shared.resources.ic_rule
import com.venbiasa.wailo.shared.resources.ic_settings
import com.venbiasa.wailo.shared.theme.LocalWailoColors
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

/**
 * The single docked tool slot on the right: at most one panel is open at a time, so the layout never has
 * to reason about two side panels, and each opens at the one host-owned [WailoViewer] width ratio. Modeled
 * as one value rather than a flag per panel so "two panels open at once" isn't a state that can be reached.
 */
private enum class ToolPanel { CaptureFilter, MapLocal, Breakpoints, Devices, Settings }

// Common HTTP verbs lead the filter's Method menu (in this order); anything else follows alphabetically.
private val MethodOrder = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

private fun methodRank(method: String): Int =
    MethodOrder.indexOf(method).let { if (it < 0) MethodOrder.size else it }

@Composable
internal fun WailoViewer(
    entries: List<FlowEntry>,
    zoneOffsetMillis: Int,
    darkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    openFilterSignal: Int,
    listenAddress: String,
    listenPort: Int,
    listening: Boolean,
    portError: String?,
    onApplyPort: (Int) -> Unit,
    devices: List<DeviceInfo>,
    usbSupported: Boolean,
    usbPort: Int,
    usbPortError: String?,
    onApplyUsbPort: (Int) -> Unit,
    pairing: PairingState,
    onPairingAction: (PairingAction) -> Unit,
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
    mapLocalEnabled: Boolean,
    onMapLocalEnabledChange: (Boolean) -> Unit,
    breakpointNodes: List<BreakpointNode>,
    onBreakpointLayoutChange: (List<BreakpointNode>) -> Unit,
    breakpointsEnabled: Boolean,
    onBreakpointsEnabledChange: (Boolean) -> Unit,
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

    // The traffic list's view filter (search + facets): also transient view state — a non-destructive lens
    // over the captured rows, ANDed on top of the host chip. It resets on restart, like the selection and
    // host filter above, so it needs no host/engine plumbing.
    var trafficFilter by remember { mutableStateOf(TrafficFilter()) }
    // The filter bar is hidden until Cmd/Ctrl+F opens it (like the code editor's find bar). Closing it (its
    // × or Esc) clears every filter, so a hidden bar can never leave the list silently filtered.
    var filterOpen by remember { mutableStateOf(false) }
    // Bumped to pull focus into the keyword field when the bar opens (and on every later Cmd+F). The
    // request must fire from an effect inside the bar once it's composed — same trick as the find bar.
    var filterFocusRequests by remember { mutableStateOf(0) }
    // The `+ Add filter` modal, rendered by this viewer so its scrim dims only the left pane.
    var addFilterOpen by remember { mutableStateOf(false) }
    // Cmd/Ctrl+F arrives as a host-bumped counter rather than a key handler here (see [openFilterSignal]).
    // Skipped at 0 so the initial composition doesn't spring the bar open unasked.
    LaunchedEffect(openFilterSignal) {
        if (openFilterSignal > 0) {
            filterOpen = true
            filterFocusRequests += 1
        }
    }

    // The autocomplete pools the add-filter modal offers per field, derived from what's actually been
    // captured so a field only suggests values that exist. Methods are uppercased to match the row's
    // display and the filter's comparison; common verbs lead (see MethodOrder), then the rest
    // alphabetically. URL suggests hosts (the useful "contains" seed); Status Code the codes seen.
    val availableMethods = remember(entries) {
        entries.map { it.exchange.request?.method?.ifEmpty { "?" } ?: "?" }
            .map { it.uppercase() }
            .distinct()
            .sortedWith(compareBy({ methodRank(it) }, { it }))
    }
    val availableAppIds = remember(entries) { entries.map { it.appId }.distinct().sorted() }
    val filterSuggestions = remember(entries, availableMethods, availableAppIds) {
        val hosts = entries.map { requestHost(it.exchange.request?.url ?: "") }
            .filter { it.isNotBlank() }.distinct().sorted()
        val codes = entries.mapNotNull { it.exchange.response?.code }.distinct().sorted().map { it.toString() }
        mapOf(
            FilterKey.Method to availableMethods,
            FilterKey.Url to hosts,
            FilterKey.StatusCode to codes,
            FilterKey.Client to availableAppIds,
            FilterKey.Edited to listOf("true", "false"),
        )
    }

    val visibleEntries = remember(entries, activeHost, trafficFilter) {
        val host = activeHost
        entries.filter { entry ->
            (host == null || requestHost(entry.exchange.request?.url ?: "") == host) &&
                trafficFilter.matches(entry)
        }
    }

    // Which tool panel is docked, plus the rule drafts a traffic row can seed (Map Local's, with its body,
    // and a breakpoint's): transient view state (like the selection/filter above). The host owns every
    // panel's contents and their persistence — the panels only render them (ADR-0013/0021). Every panel
    // shares the one host-owned [toolPanelWidthRatio], so a width dragged for any of them is the width the
    // next one opens at, and it persists across restarts. It's a fraction of the window, not a fixed dp, so
    // the panel scales with the window (clamped to keep both panel and content usable, see ToolPanelLayout).
    var openPanel by remember { mutableStateOf<ToolPanel?>(null) }
    var mapLocalDraft by remember { mutableStateOf<MapLocalRuleDef?>(null) }
    var mapLocalBodySeed by remember { mutableStateOf<ByteArray?>(null) }
    var breakpointDraft by remember { mutableStateOf<BreakpointRuleDef?>(null) }
    val density = LocalDensity.current
    // Somewhere for the caret to land when the filter bar closes, so the keystrokes after it don't fall
    // into the field that just disappeared. Focusable rather than a bare Box because only a focus target
    // can be handed focus.
    val rootFocus = remember { FocusRequester() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .focusRequester(rootFocus)
                .focusable(),
        ) {
            val totalWidth = maxWidth
            Row(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val minDetail = 180.dp
                    val maxDetail = (maxHeight - 160.dp).coerceAtLeast(minDetail)
                    var detailHeight by remember { mutableStateOf(360.dp) }

                    Column(Modifier.fillMaxSize()) {
                        TopBar(
                            listenAddress = listenAddress,
                            listening = listening,
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
                        // The view filter is hidden until Cmd/Ctrl+F opens it (see [openFilterSignal]), then
                        // sits closest to the list it narrows.
                        if (filterOpen) {
                            FilterBar(
                                filter = trafficFilter,
                                onFilterChange = { trafficFilter = it },
                                onRequestAddFilter = { addFilterOpen = true },
                                onClose = {
                                    // Close == clear: never leave a hidden, still-filtered list. Focus goes
                                    // back to the root so it doesn't die with the field being removed.
                                    trafficFilter = TrafficFilter()
                                    addFilterOpen = false
                                    filterOpen = false
                                    rootFocus.requestFocus()
                                },
                                focusSignal = filterFocusRequests,
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
                                    openPanel = ToolPanel.MapLocal
                                },
                                // Same seam for breakpoints: the row's exact URL + method become a draft
                                // rule and the panel opens on its editor. Nothing about a captured row says
                                // which phase to pause, so the draft keeps the model's default (response).
                                onBreakpointFromUrl = { url, method ->
                                    breakpointDraft = BreakpointRuleDef(
                                        id = BreakpointRuleDef.newId(),
                                        urlPattern = url,
                                        method = method,
                                    )
                                    openPanel = ToolPanel.Breakpoints
                                },
                            )
                            // The filter (or the host chip) can hide every row while traffic is still
                            // captured; say so, rather than an empty table that reads as "no traffic yet".
                            // Guarded on there being traffic at all, so the pre-traffic view stays blank.
                            if (entries.isNotEmpty() && visibleEntries.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    MutedText("No matching traffic")
                                }
                            }
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

                    // The `+ Add filter` modal dims only this (left) pane: the scrim fills the left
                    // BoxWithConstraints, so the tool panel on the right stays lit and usable. Scoped here
                    // (a sibling of the content Column) rather than inside the bar for exactly that reason.
                    if (addFilterOpen) {
                        // += 1 (not ++) so the lambda returns Unit: post-increment yields the old Int,
                        // which would type this as () -> Int and fail the () -> Unit callbacks below.
                        val dismissModal = {
                            addFilterOpen = false
                            filterFocusRequests += 1
                        }
                        Box(
                            Modifier.matchParentSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = dismissModal,
                                ),
                        )
                        AddFilterCard(
                            suggestions = filterSuggestions,
                            onAdd = { clause ->
                                trafficFilter = trafficFilter.copy(clauses = trafficFilter.clauses + clause)
                                dismissModal()
                            },
                            onCancel = dismissModal,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                // The docked tool panel sits to the right, resizable, between the content and the tool rail
                // (the Android Studio tool-window model). One [toolPanelWidthRatio] backs the single slot,
                // so a width dragged for one panel is exactly the width the next opens at, and it persists
                // across restarts.
                val docked = openPanel
                if (docked != null) {
                    val panelWidth = ToolPanelLayout.widthFor(toolPanelWidthRatio, totalWidth)
                    PanelResizeHandle { deltaPx ->
                        // Drag the handle left (negative delta) to widen; convert the new width back to a
                        // window fraction so it scales with the window and the host can persist it.
                        val dragged = panelWidth - with(density) { deltaPx.toDp() }
                        onToolPanelWidthRatioChange(ToolPanelLayout.ratioFor(dragged, totalWidth))
                    }
                    val closePanel = { openPanel = null }
                    Box(Modifier.width(panelWidth).fillMaxHeight()) {
                        when (docked) {
                            ToolPanel.MapLocal -> MapLocalManager(
                                nodes = mapLocalNodes,
                                initialDraft = mapLocalDraft,
                                initialBodySeed = mapLocalBodySeed,
                                onLayoutChange = onMapLocalLayoutChange,
                                enabled = mapLocalEnabled,
                                onEnabledChange = onMapLocalEnabledChange,
                                onClose = closePanel,
                                onLoadBody = onLoadMapLocalBody,
                                onSaveBody = onSaveMapLocalBody,
                                onPickFile = onPickMapLocalFile,
                            )
                            ToolPanel.CaptureFilter -> CaptureFilterManager(
                                filter = captureFilter,
                                onFilterChange = onCaptureFilterChange,
                                onClose = closePanel,
                            )
                            ToolPanel.Breakpoints -> BreakpointManager(
                                nodes = breakpointNodes,
                                onLayoutChange = onBreakpointLayoutChange,
                                initialDraft = breakpointDraft,
                                enabled = breakpointsEnabled,
                                onEnabledChange = onBreakpointsEnabledChange,
                                onClose = closePanel,
                            )
                            ToolPanel.Devices -> DevicesManager(
                                devices = devices,
                                usbSupported = usbSupported,
                                usbPort = usbPort,
                                pairing = pairing,
                                onPairingAction = onPairingAction,
                                onClose = closePanel,
                            )
                            ToolPanel.Settings -> SettingsManager(
                                listenPort = listenPort,
                                listenAddress = listenAddress,
                                listening = listening,
                                portError = portError,
                                onApplyPort = onApplyPort,
                                usbSupported = usbSupported,
                                usbPort = usbPort,
                                usbPortError = usbPortError,
                                onApplyUsbPort = onApplyUsbPort,
                                pairing = pairing,
                                onPairingAction = onPairingAction,
                                onClose = closePanel,
                            )
                        }
                    }
                }
                ColumnDivider()
                ToolRail(
                    darkTheme = darkTheme,
                    onToggleDarkTheme = onToggleDarkTheme,
                    openPanel = openPanel,
                    onSelectPanel = { panel ->
                        // Rail buttons are toggles: picking the open panel closes it.
                        openPanel = if (openPanel == panel) null else panel
                        // Reaching a rule panel from the rail lands on its rule list; only a row's
                        // "Map Local…"/"Breakpoints…" opens one on a seeded draft.
                        if (panel == ToolPanel.MapLocal) {
                            mapLocalDraft = null
                            mapLocalBodySeed = null
                        }
                        if (panel == ToolPanel.Breakpoints) breakpointDraft = null
                    },
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
 * leads as a global app action; below a divider come the side-panel tools, each highlighted while its
 * panel is docked. Settings sits alone at the foot of the rail — it configures the app rather than
 * inspecting traffic, and the bottom is where a settings entry is looked for.
 */
@Composable
private fun ToolRail(
    darkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    openPanel: ToolPanel?,
    onSelectPanel: (ToolPanel) -> Unit,
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
        // tool panel, so it never carries the selected highlight and sits above the tool divider. It stays
        // on the rail because flipping themes to check a screen in both is a one-click job (ADR-0016),
        // not something worth opening a panel for.
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
            selected = openPanel == ToolPanel.CaptureFilter,
            onClick = { onSelectPanel(ToolPanel.CaptureFilter) },
        )
        ToolRailButton(
            icon = Res.drawable.ic_rule,
            contentDescription = "Map Local",
            selected = openPanel == ToolPanel.MapLocal,
            onClick = { onSelectPanel(ToolPanel.MapLocal) },
        )
        ToolRailButton(
            icon = Res.drawable.ic_breakpoint,
            contentDescription = "Breakpoints",
            selected = openPanel == ToolPanel.Breakpoints,
            onClick = { onSelectPanel(ToolPanel.Breakpoints) },
        )
        ToolRailButton(
            icon = Res.drawable.ic_devices,
            contentDescription = "Devices",
            selected = openPanel == ToolPanel.Devices,
            onClick = { onSelectPanel(ToolPanel.Devices) },
        )
        Spacer(Modifier.weight(1f))
        ToolRailButton(
            icon = Res.drawable.ic_settings,
            contentDescription = "Settings",
            selected = openPanel == ToolPanel.Settings,
            onClick = { onSelectPanel(ToolPanel.Settings) },
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
    listening: Boolean,
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
        // when paused, so the dot always agrees with the pause/resume button. A server that couldn't bind
        // its port overrides both and goes red — otherwise this reads as a working endpoint while nothing
        // is listening and no traffic will ever arrive, with no hint that the port (in Settings) is why.
        Box(
            Modifier.size(8.dp).background(
                color = when {
                    !listening -> MaterialTheme.colorScheme.error
                    capturing -> LocalWailoColors.current.success
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = CircleShape,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (listening) listenAddress else "$listenAddress — not listening",
            style = MaterialTheme.typography.labelMedium,
            color = if (listening) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
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
