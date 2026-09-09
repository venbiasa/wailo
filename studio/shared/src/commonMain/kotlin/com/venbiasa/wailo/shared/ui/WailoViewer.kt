package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.shared.BreakpointNode
import com.venbiasa.wailo.shared.BreakpointRuleDef
import com.venbiasa.wailo.shared.CaptureFilterState
import com.venbiasa.wailo.shared.DeviceInfo
import com.venbiasa.wailo.shared.PairingAction
import com.venbiasa.wailo.shared.PairingState
import com.venbiasa.wailo.shared.FilterKey
import com.venbiasa.wailo.shared.FilterMatcher
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.PickedFile
import com.venbiasa.wailo.shared.ProxySetupAction
import com.venbiasa.wailo.shared.ProxyState
import com.venbiasa.wailo.shared.ProxyTargets
import com.venbiasa.wailo.shared.ResponseHeader
import com.venbiasa.wailo.shared.SeedNode
import com.venbiasa.wailo.shared.SeedRuleDef
import com.venbiasa.wailo.shared.TrafficFilter
import com.venbiasa.wailo.shared.compile
import com.venbiasa.wailo.shared.format.requestHost
import com.venbiasa.wailo.shared.toggleExactHost
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_breakpoint
import com.venbiasa.wailo.shared.resources.ic_content_paste_go
import com.venbiasa.wailo.shared.resources.ic_dark_mode
import com.venbiasa.wailo.shared.resources.ic_delete
import com.venbiasa.wailo.shared.resources.ic_devices
import com.venbiasa.wailo.shared.resources.ic_light_mode
import com.venbiasa.wailo.shared.resources.ic_lock
import com.venbiasa.wailo.shared.resources.ic_lock_open
import com.venbiasa.wailo.shared.resources.ic_pause
import com.venbiasa.wailo.shared.resources.ic_play_arrow
import com.venbiasa.wailo.shared.resources.ic_refresh
import com.venbiasa.wailo.shared.resources.ic_rule
import com.venbiasa.wailo.shared.resources.ic_settings
import com.venbiasa.wailo.shared.theme.LocalWailoColors
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

/**
 * The single docked tool slot on the right: at most one panel is open at a time, so the layout never has
 * to reason about two side panels, and each opens at the one host-owned [WailoViewer] width ratio. Modeled
 * as one value rather than a flag per panel so "two panels open at once" isn't a state that can be reached.
 */
private enum class ToolPanel { CaptureFilter, Unlock, MapLocal, Breakpoints, Seed, Devices, Settings }

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
    onRetryListen: suspend () -> Unit,
    portError: String?,
    onApplyPort: (Int) -> Unit,
    devices: List<DeviceInfo>,
    usbSupported: Boolean,
    usbPort: Int,
    usbPortError: String?,
    onApplyUsbPort: (Int) -> Unit,
    adbSupported: Boolean,
    maxRetained: Int,
    maxRetainedError: String?,
    onApplyMaxRetained: (Int) -> Unit,
    stickyScopeRows: Int,
    stickyScopeRowsError: String?,
    onApplyStickyScopeRows: (Int) -> Unit,
    mcpAccess: Boolean,
    onMcpAccessChange: (Boolean) -> Unit,
    mcpRedactSecrets: Boolean,
    onMcpRedactSecretsChange: (Boolean) -> Unit,
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
    onSeedFromMapLocalRule: (MapLocalRuleDef) -> Unit,
    onExportRules: suspend () -> String,
    onImportRules: suspend () -> String,
    breakpointNodes: List<BreakpointNode>,
    onBreakpointLayoutChange: (List<BreakpointNode>) -> Unit,
    breakpointsEnabled: Boolean,
    onBreakpointsEnabledChange: (Boolean) -> Unit,
    onOpenBreakpointWindow: () -> Unit,
    seedNodes: List<SeedNode>,
    onSeedLayoutChange: (List<SeedNode>) -> Unit,
    onLoadSeedBody: suspend (SeedRuleDef) -> ByteArray,
    onSaveSeedBody: suspend (SeedRuleDef, ByteArray) -> Unit,
    seedsEnabled: Boolean,
    onSeedsEnabledChange: (Boolean) -> Unit,
    openSeedPanelSignal: Int,
    proxy: ProxyState,
    onProxyEnabledChange: (Boolean) -> Unit,
    onApplyProxyPort: (Int) -> Unit,
    onProxySetupAction: (ProxySetupAction) -> Unit,
    proxyTargets: ProxyTargets,
    toolPanelWidthRatio: Float,
    onToolPanelWidthRatioChange: (Float) -> Unit,
    trafficColumnWidths: Map<String, Float>,
    onTrafficColumnWidthsChange: (Map<String, Float>) -> Unit,
    comparedIds: Set<String>,
    onCompare: (Pair<String, String>) -> Unit,
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
        // Whole URLs are the pool only an exact or wildcard match can use, and building them costs a
        // pass over every captured row — so it waits until a matcher actually asks for it.
        val urls by lazy {
            entries.map { it.exchange.request?.url ?: "" }.filter { it.isNotBlank() }.distinct().sorted()
        }
        val suggest: (FilterKey, FilterMatcher) -> List<String> = { key, matcher ->
            when (key) {
                FilterKey.Method -> availableMethods
                // A host is the useful seed for a substring test but would never match a whole-URL
                // comparison, so an exact or wildcard match is offered the URLs themselves instead.
                FilterKey.Url -> when (matcher) {
                    FilterMatcher.Equals, FilterMatcher.Wildcard -> urls
                    else -> hosts
                }
                FilterKey.StatusCode -> codes
                FilterKey.Client -> availableAppIds
                FilterKey.Edited, FilterKey.Proxy -> listOf("true", "false")
            }
        }
        suggest
    }

    val visibleEntries = remember(entries, activeHost, trafficFilter) {
        val host = activeHost
        // Parse the query and build its patterns once per filter change, not once per row: a regex or
        // wildcard rebuilt inside the loop would be recompiled for every captured exchange.
        val passes = trafficFilter.compile()
        entries.filter { entry ->
            (host == null || requestHost(entry.exchange.request?.url ?: "") == host) && passes(entry)
        }
    }

    // Which tool panel is docked, plus the rule drafts a traffic row can seed (Map Local's and Seed's, both
    // with a body, and a breakpoint's): transient view state (like the selection/filter above). The host
    // owns every panel's contents and their persistence — the panels only render them (ADR-0013/0021).
    // Every panel shares the one host-owned [toolPanelWidthRatio], so a width dragged for any of them is
    // the width the next one opens at, and it persists across restarts. It's a fraction of the window, not
    // a fixed dp, so the panel scales with the window (clamped to keep both panel and content usable, see
    // ToolPanelLayout).
    var openPanel by remember { mutableStateOf<ToolPanel?>(null) }
    var mapLocalDraft by remember { mutableStateOf<MapLocalRuleDef?>(null) }
    var mapLocalBodySeed by remember { mutableStateOf<ByteArray?>(null) }
    var seedDraft by remember { mutableStateOf<SeedRuleDef?>(null) }
    var seedBodySeed by remember { mutableStateOf<ByteArray?>(null) }
    var breakpointDraft by remember { mutableStateOf<BreakpointRuleDef?>(null) }
    // Importing a Map Local rule as a seed is the host's job (it owns both layouts and the body files),
    // so it reports back with a bumped counter rather than a value — the import is already persisted by
    // then, and this only reveals it. Skipped at 0 so the initial composition doesn't open the panel
    // unasked, the same handshake the filter bar's Cmd+F signal uses.
    LaunchedEffect(openSeedPanelSignal) {
        if (openSeedPanelSignal > 0) {
            // The import is a finished seed, so this lands on the list to show it there — clear any draft
            // a traffic row left behind, or the panel would open on that stale editor instead.
            seedDraft = null
            seedBodySeed = null
            openPanel = ToolPanel.Seed
        }
    }
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
                    // The chrome above the list has no fixed height: the bookmark bar and the filter bar
                    // come and go, the filter bar grows a second row once it has pills, and all of it
                    // scales with the user's text size. Measuring it is what keeps the detail panel's
                    // ceiling honest — a constant reserve here let a dragged panel eat the list and run
                    // up under the header as soon as anything above it appeared.
                    var chromeHeight by remember { mutableStateOf(0.dp) }
                    val maxDetail = (maxHeight - chromeHeight - MinTrafficListHeight).coerceAtLeast(minDetail)
                    var detailHeight by remember { mutableStateOf(360.dp) }

                    Column(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.fillMaxWidth()
                                .onSizeChanged { chromeHeight = with(density) { it.height.toDp() } },
                        ) {
                            TopBar(
                                listenAddress = listenAddress,
                                listening = listening,
                                onRetryListen = onRetryListen,
                                capturing = capturing,
                                onToggleCapture = onToggleCapture,
                                onClear = onClear,
                                proxy = proxy,
                            )
                            RowDivider()
                            // The bookmark bar only exists once there is something to show, so an empty
                            // setup costs no vertical space and reads exactly like the pre-bookmark viewer.
                            if (bookmarks.isNotEmpty()) {
                                BookmarkBar(
                                    bookmarks = bookmarks,
                                    activeHost = activeHost,
                                    onSelect = { activeHost = it },
                                    onRemove = onRemoveBookmark,
                                )
                                RowDivider()
                            }
                            // The view filter is hidden until Cmd/Ctrl+F opens it (see [openFilterSignal]),
                            // then sits closest to the list it narrows.
                            if (filterOpen) {
                                FilterBar(
                                    filter = trafficFilter,
                                    onFilterChange = { trafficFilter = it },
                                    suggestions = filterSuggestions,
                                    onRequestAddFilter = { addFilterOpen = true },
                                    onClose = {
                                        // Close == clear: never leave a hidden, still-filtered list. Focus
                                        // goes back to the root so it doesn't die with the field being
                                        // removed.
                                        trafficFilter = TrafficFilter()
                                        addFilterOpen = false
                                        filterOpen = false
                                        rootFocus.requestFocus()
                                    },
                                    focusSignal = filterFocusRequests,
                                )
                                RowDivider()
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            TrafficList(
                                entries = visibleEntries,
                                columnWidths = trafficColumnWidths,
                                onColumnWidthsChange = onTrafficColumnWidthsChange,
                                selectedId = selectedId,
                                onSelect = { selectedId = it },
                                comparedIds = comparedIds,
                                // A row starts a comparison between the selection (A) and it (B). The pair
                                // then belongs to its window, so moving the selection afterwards leaves it
                                // alone — and so does starting a second comparison.
                                onCompare = { id -> selectedId?.let { onCompare(it to id) } },
                                zoneOffsetMillis = zoneOffsetMillis,
                                bookmarks = bookmarks,
                                onAddBookmark = onAddBookmark,
                                onRemoveBookmark = onRemoveBookmark,
                                allowHosts = captureFilter.allowHosts,
                                blockHosts = captureFilter.blockHosts,
                                decryptHosts = proxy.decryptHosts,
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
                                onToggleDecryptHost = { host ->
                                    onProxySetupAction(
                                        ProxySetupAction.SetDecryptHosts(proxy.decryptHosts.toggleExactHost(host)),
                                    )
                                },
                                // A row's "Map Local…" seeds a fresh draft (exact URL + method + the
                                // captured body's bytes — JSON or image) and opens the tool panel — no
                                // separate window (ADR-0021).
                                onMapLocalFromUrl = { url, method, code, responseHeaders, seed ->
                                    mapLocalDraft = MapLocalRuleDef(
                                        id = MapLocalRuleDef.newId(),
                                        urlPattern = url,
                                        method = method,
                                        inline = true,
                                        // Start the mock close to the observed response: the code it came
                                        // back with and its real headers, minus the ones that describe the
                                        // live transfer rather than the payload (see seededHeaders).
                                        statusCode = code.takeIf { it > 0 } ?: 200,
                                        headers = seededHeaders(responseHeaders),
                                    )
                                    mapLocalBodySeed = seed
                                    openPanel = ToolPanel.MapLocal
                                },
                                onSeedFromUrl = { url, method, code, responseHeaders, seed ->
                                    seedDraft = SeedRuleDef(
                                        id = SeedRuleDef.newId(),
                                        urlPattern = url,
                                        method = method,
                                        statusCode = code.takeIf { it > 0 } ?: 200,
                                        headers = seededHeaders(responseHeaders),
                                    )
                                    seedBodySeed = seed
                                    openPanel = ToolPanel.Seed
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
                                proxy = proxy,
                                onProxySetupAction = onProxySetupAction,
                                modifier = Modifier.fillMaxWidth()
                                    .height(detailHeight.coerceIn(minDetail, maxDetail)),
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
                                onSeedFromRule = onSeedFromMapLocalRule,
                                onExportRules = onExportRules,
                                onImportRules = onImportRules,
                            )
                            ToolPanel.Seed -> SeedManager(
                                nodes = seedNodes,
                                onLayoutChange = onSeedLayoutChange,
                                initialDraft = seedDraft,
                                initialBodySeed = seedBodySeed,
                                enabled = seedsEnabled,
                                onEnabledChange = onSeedsEnabledChange,
                                onClose = closePanel,
                                onLoadBody = onLoadSeedBody,
                                onSaveBody = onSaveSeedBody,
                                // The picker is feature-agnostic — it just reads a file — so both
                                // response-authoring panels share the host's one implementation.
                                onPickFile = onPickMapLocalFile,
                            )
                            ToolPanel.CaptureFilter -> CaptureFilterManager(
                                filter = captureFilter,
                                onFilterChange = onCaptureFilterChange,
                                onClose = closePanel,
                            )
                            ToolPanel.Unlock -> UnlockManager(
                                proxy = proxy,
                                onAction = onProxySetupAction,
                                onClose = closePanel,
                            )
                            ToolPanel.Breakpoints -> BreakpointManager(
                                nodes = breakpointNodes,
                                onLayoutChange = onBreakpointLayoutChange,
                                initialDraft = breakpointDraft,
                                enabled = breakpointsEnabled,
                                onEnabledChange = onBreakpointsEnabledChange,
                                onOpenWindow = onOpenBreakpointWindow,
                                onClose = closePanel,
                            )
                            ToolPanel.Devices -> DevicesManager(
                                devices = devices,
                                usbSupported = usbSupported,
                                usbPort = usbPort,
                                adbSupported = adbSupported,
                                listenPort = listenPort,
                                pairing = pairing,
                                onPairingAction = onPairingAction,
                                proxy = proxy,
                                proxyTargets = proxyTargets,
                                onProxySetupAction = onProxySetupAction,
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
                                proxy = proxy,
                                onProxyEnabledChange = onProxyEnabledChange,
                                onApplyProxyPort = onApplyProxyPort,
                                onProxySetupAction = onProxySetupAction,
                                maxRetained = maxRetained,
                                // The whole capture, not the filtered view: the cap is about what the
                                // engine is holding, which no display filter changes.
                                retainedCount = entries.size,
                                maxRetainedError = maxRetainedError,
                                onApplyMaxRetained = onApplyMaxRetained,
                                stickyScopeRows = stickyScopeRows,
                                stickyScopeRowsError = stickyScopeRowsError,
                                onApplyStickyScopeRows = onApplyStickyScopeRows,
                                mcpAccess = mcpAccess,
                                onMcpAccessChange = onMcpAccessChange,
                                mcpRedactSecrets = mcpRedactSecrets,
                                onMcpRedactSecretsChange = onMcpRedactSecretsChange,
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
                        // "Map Local…"/"Seed…"/"Breakpoints…" opens one on a seeded draft.
                        if (panel == ToolPanel.MapLocal) {
                            mapLocalDraft = null
                            mapLocalBodySeed = null
                        }
                        if (panel == ToolPanel.Seed) {
                            seedDraft = null
                            seedBodySeed = null
                        }
                        if (panel == ToolPanel.Breakpoints) breakpointDraft = null
                    },
                )
            }
        }
    }
}

private val ToolRailWidth = 48.dp

// How much of the traffic list the detail panel may never take. Enough for a header row plus a couple of
// rows, so dragging the panel to its ceiling still leaves something to click back to.
private val MinTrafficListHeight = 120.dp

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

// Builds a seeded rule's headers from the captured response — shared by Map Local and Seed, which author
// the same response shape: keep the real headers so the mock starts close to what was observed, drop the
// transfer-only ones, and guarantee a Content-Type (the editor is JSON-focused, ADR-0021) when the
// response carried none.
private fun seededHeaders(responseHeaders: List<Header>): List<ResponseHeader> {
    val kept = responseHeaders
        .filterNot { it.name.lowercase() in NonSeededHeaderNames }
        .map { ResponseHeader(it.name, it.value_) }
    return if (kept.any { it.name.equals("Content-Type", ignoreCase = true) }) {
        kept
    } else {
        kept + ResponseHeader("Content-Type", "application/json")
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
            icon = Res.drawable.ic_lock_open,
            contentDescription = "Unlock",
            selected = openPanel == ToolPanel.Unlock,
            onClick = { onSelectPanel(ToolPanel.Unlock) },
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
        // Seed follows Breakpoints because it only exists to answer them (ADR-0041); the paste-go glyph
        // is the feature — a prepared response handed to a hold.
        ToolRailButton(
            icon = Res.drawable.ic_content_paste_go,
            contentDescription = "Seed",
            selected = openPanel == ToolPanel.Seed,
            onClick = { onSelectPanel(ToolPanel.Seed) },
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

@Composable
private fun TopBar(
    listenAddress: String,
    listening: Boolean,
    onRetryListen: suspend () -> Unit,
    capturing: Boolean,
    onToggleCapture: () -> Unit,
    onClear: () -> Unit,
    proxy: ProxyState,
) {
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
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
        // Both capture paths, always drawn, in the same shape: a dot for whether traffic is arriving and
        // the address to point something at. Both are permanent so their absence never has to be read as
        // an answer — an indicator that vanishes when off leaves "not capturing" and "no such feature"
        // looking identical.
        //
        // Pausing greys both at once, because a paused capture keeps every listener bound and records
        // from neither; a green dot over a path that is dropping everything would contradict the
        // pause button two icons to the left.
        ListenerStatus(
            // A listener that could not bind is the one case worth breaking grey/green for: otherwise
            // this reads as a working endpoint while no traffic can ever arrive, with nothing to say the
            // port (in Settings) is why.
            live = capturing && listening,
            failed = !listening,
            address = if (listening) listenAddress else "not listening",
            name = "Socket",
        )
        // The engine retakes a port it lost on its own, but not one it never got — something else was
        // holding it, and only the user knows when that's over. Offered here rather than only in Settings
        // because this is where the failure is visible, and it's a retry, not a setting to change.
        if (!listening) {
            IconButton(
                onClick = {
                    scope.launch {
                        retrying = true
                        try {
                            onRetryListen()
                        } finally {
                            retrying = false
                        }
                    }
                },
                enabled = !retrying,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_refresh),
                    contentDescription = "Retry binding the socket port",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        ListenerStatus(
            live = capturing && proxy.running,
            // Off is the proxy's resting state, so only a start that was actually refused is a failure.
            failed = proxy.error != null,
            address = proxy.address,
            name = "Proxy",
        )
    }
}

/**
 * One capture path in the top bar: a state dot, the address, and which path it is.
 *
 * The name is bracketed after the address rather than prefixed so the addresses line up as a column and
 * read as the primary content — the label answers "which one is this", which is the second question.
 *
 * The dot carries the difference between off and recording; only a failure also reddens the address, since
 * that is the one state the user has to act on. The label is the quietest step (`onSurfaceDisabled`, under
 * the address's `onSurfaceVariant`) because it is recognised rather than read — but not `outline`, a
 * hairline token that as text on `surfaceContainer` is barely a shade off the bar.
 */
@Composable
private fun ListenerStatus(
    live: Boolean,
    failed: Boolean,
    address: String,
    name: String,
) {
    Box(
        Modifier.size(8.dp).background(
            color = when {
                failed -> MaterialTheme.colorScheme.error
                live -> LocalWailoColors.current.success
                else -> MaterialTheme.colorScheme.outline
            },
            shape = CircleShape,
        ),
    )
    Spacer(Modifier.width(6.dp))
    SelectionContainer {
        Text(
            address,
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.width(4.dp))
    Text(
        "[$name]",
        style = MaterialTheme.typography.labelSmall,
        color = LocalWailoColors.current.onSurfaceDisabled,
    )
}