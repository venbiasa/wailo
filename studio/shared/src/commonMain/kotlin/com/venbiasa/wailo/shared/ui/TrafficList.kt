package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.format.codeText
import com.venbiasa.wailo.shared.format.formatBytes
import com.venbiasa.wailo.shared.format.formatClockTime
import com.venbiasa.wailo.shared.format.requestHost
import com.venbiasa.wailo.shared.format.statusKind
import com.venbiasa.wailo.shared.format.statusText
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_arrow_downward
import com.venbiasa.wailo.shared.resources.ic_check
import com.venbiasa.wailo.shared.toCurlCommand
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource

private val HeaderHeight = 34.dp
private val CellHPad = 12.dp
private val RowVPad = 8.dp
private val ResizeHandleWidth = 12.dp

@Composable
internal fun TrafficList(
    entries: List<FlowEntry>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    zoneOffsetMillis: Int,
    bookmarks: List<String>,
    onAddBookmark: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    allowHosts: List<String>,
    blockHosts: List<String>,
    onToggleAllowHost: (String) -> Unit,
    onToggleBlockHost: (String) -> Unit,
    onMapLocalFromUrl: (String, String, List<Header>, ByteArray?) -> Unit,
    onSeedFromUrl: (String, String, Int, List<Header>, ByteArray?) -> Unit,
    onBreakpointFromUrl: (String, String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // One scroll state shared by the header and every row keeps the columns locked together as the
    // table scrolls sideways when the columns are wider than the viewport.
    val hScroll = rememberScrollState()
    val widths = rememberColumnWidths()
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }

    // "At the bottom" means the last row is visible; it drives whether we keep tailing new traffic.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    var autoFollow by remember { mutableStateOf(true) }
    LaunchedEffect(atBottom) { autoFollow = atBottom }
    // Scrolling to the last index clamps at the content end, pinning the newest row to the bottom.
    LaunchedEffect(entries.size) {
        if (autoFollow && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    // Arrow-key navigation: Up/Down move the selection to the adjacent row and keep it on-screen.
    // With nothing selected yet, Down lands on the first row and Up on the last.
    fun moveSelection(delta: Int) {
        if (entries.isEmpty()) return
        val current = entries.indexOfFirst { it.id == selectedId }
        val target = when {
            current < 0 -> if (delta > 0) 0 else entries.lastIndex
            else -> (current + delta).coerceIn(0, entries.lastIndex)
        }
        if (target == current) return
        onSelect(entries[target].id)
        scope.launch { listState.ensureItemVisible(target) }
    }

    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        moveSelection(1)
                        true
                    }
                    Key.DirectionUp -> {
                        moveSelection(-1)
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // The header always shows, even before any traffic arrives, so the table reads as a table
        // rather than an empty placeholder.
        Column(Modifier.fillMaxSize()) {
            TableHeader(
                widths = widths,
                hScroll = hScroll,
                onResize = { column, deltaPx ->
                    val current = widths[column] ?: column.defaultWidth
                    val next = with(density) { current + deltaPx.toDp() }
                    widths[column] = next.coerceAtLeast(column.minWidth)
                },
            )
            RowDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, modifier = Modifier.matchParentSize()) {
                    items(entries, key = { it.id }) { entry ->
                        TrafficRow(
                            entry = entry,
                            widths = widths,
                            hScroll = hScroll,
                            selected = entry.id == selectedId,
                            onClick = {
                                onSelect(entry.id)
                                // Clicking a row hands keyboard focus to the list so Up/Down can take over.
                                focusRequester.requestFocus()
                            },
                            zoneOffsetMillis = zoneOffsetMillis,
                            bookmarks = bookmarks,
                            onAddBookmark = onAddBookmark,
                            onRemoveBookmark = onRemoveBookmark,
                            allowHosts = allowHosts,
                            blockHosts = blockHosts,
                            onToggleAllowHost = onToggleAllowHost,
                            onToggleBlockHost = onToggleBlockHost,
                            onMapLocalFromUrl = onMapLocalFromUrl,
                            onSeedFromUrl = onSeedFromUrl,
                            onBreakpointFromUrl = onBreakpointFromUrl,
                        )
                        RowDivider()
                    }
                }
                // Overlays the list's right edge; the desktop scrollbar self-hides unless the traffic
                // overflows the viewport, so it shows only when there's something to scroll.
                VerticalListScrollbar(
                    listState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
                // Bottom edge: appears only when the columns are wider than the viewport (the header and
                // rows share this hScroll, so the whole table tracks it).
                HorizontalScrollbar(
                    scrollState = hScroll,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                )
            }
        }
        if (!atBottom) {
            JumpToLatest(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                scope.launch {
                    if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
                    autoFollow = true
                }
            }
        }
    }
}

@Composable
private fun TableHeader(
    widths: SnapshotStateMap<TrafficColumn, Dp>,
    hScroll: ScrollState,
    onResize: (TrafficColumn, Float) -> Unit,
) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.horizontalScroll(hScroll).height(HeaderHeight)) {
            TrafficColumn.entries.forEach { column ->
                // The last column owns the leftover header space to the window edge, so it draws no
                // trailing divider/handle — otherwise that gap reads as a phantom empty column.
                HeaderCell(
                    column = column,
                    width = widths[column] ?: column.defaultWidth,
                    onResize = onResize,
                    resizable = column != TrafficColumn.entries.last(),
                )
            }
        }
    }
}

@Composable
private fun HeaderCell(
    column: TrafficColumn,
    width: Dp,
    onResize: (TrafficColumn, Float) -> Unit,
    resizable: Boolean,
) {
    Box(Modifier.width(width).fillMaxHeight()) {
        Text(
            column.title,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = CellHPad),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // A thin strip on the trailing edge; dragging it resizes this column, and hovering it shows
        // the horizontal resize cursor on desktop.
        if (resizable) {
            Box(
                Modifier.align(Alignment.CenterEnd)
                    .width(ResizeHandleWidth)
                    .fillMaxHeight()
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState(onDelta = { onResize(column, it) }),
                    )
                    .resizeCursor(ResizeAxis.Horizontal),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
            }
        }
    }
}

@Composable
private fun TrafficRow(
    entry: FlowEntry,
    widths: SnapshotStateMap<TrafficColumn, Dp>,
    hScroll: ScrollState,
    selected: Boolean,
    onClick: () -> Unit,
    zoneOffsetMillis: Int,
    bookmarks: List<String>,
    onAddBookmark: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    allowHosts: List<String>,
    blockHosts: List<String>,
    onToggleAllowHost: (String) -> Unit,
    onToggleBlockHost: (String) -> Unit,
    onMapLocalFromUrl: (String, String, List<Header>, ByteArray?) -> Unit,
    onSeedFromUrl: (String, String, Int, List<Header>, ByteArray?) -> Unit,
    onBreakpointFromUrl: (String, String) -> Unit,
) {
    val exchange = entry.exchange
    val request = exchange.request
    val response = exchange.response
    val clipboard = LocalClipboardManager.current
    val method = request?.method?.ifEmpty { "?" } ?: "?"
    val code = response?.code
    val hasError = exchange.error.isNotEmpty()
    val kind = statusKind(code, hasError)

    // Right-click can copy the complete captured request as cURL, bookmark this row's host (a tick once
    // saved; the slot is reserved when not, so the label never shifts as it toggles), add/remove the host
    // in the capture filter's allow or block list, or author a rule from its URL. The
    // Allowlist/Blocklist ticks track *exact* membership (so a subdomain isn't shown as listed under a
    // `*.example.com` entry, and toggling removes only the exact host it added — a wildcard entry is never
    // silently dropped); arming each list stays a deliberate switch in the capture-filter panel. A row with
    // no parseable host skips the bookmark/filter entries; one with no URL skips copy and rule authoring —
    // an all-empty list is a plain passthrough (no menu).
    val url = request?.url ?: ""
    val host = remember(url) { requestHost(url) }
    // A rule matches the method as captured; the display fallback above ("?") is not one.
    val ruleMethod = request?.method.orEmpty().trim().uppercase()
    val bookmarked = host in bookmarks
    val allowed = host in allowHosts
    val blocked = host in blockHosts
    val actions = buildList {
        if (request != null && url.isNotEmpty()) {
            add(
                ContextMenuAction("Copy cURL") {
                    clipboard.setText(AnnotatedString(request.toCurlCommand()))
                },
            )
        }
        if (host.isNotEmpty()) {
            add(
                ContextMenuAction("Bookmark", checked = bookmarked) {
                    if (bookmarked) onRemoveBookmark(host) else onAddBookmark(host)
                },
            )
            add(ContextMenuAction("Allowlist", checked = allowed) { onToggleAllowHost(host) })
            add(ContextMenuAction("Blocklist", checked = blocked) { onToggleBlockHost(host) })
        }
        if (url.isNotEmpty()) {
            // All three seed a new rule with this row's exact URL and method, so the panel opens on the
            // values that were right-clicked rather than asking for them again. Map Local and Seed also
            // carry this response's captured headers and its body's raw bytes (the editor decodes them as
            // JSON text or previews them as an image per the Content-Type), so the rule opens ready to
            // tweak; the bytes are copied on select, not per row.
            add(
                ContextMenuAction("Map Local\u2026") {
                    onMapLocalFromUrl(url, ruleMethod, response?.headers ?: emptyList(), capturedBody(response))
                },
            )
            // Seed carries the observed status code as well, where Map Local starts at 200: a seed exists
            // to replay this exchange into a hold, so an observed 500 or 429 is usually the whole point.
            add(
                ContextMenuAction("Seed\u2026") {
                    onSeedFromUrl(url, ruleMethod, code ?: 0, response?.headers ?: emptyList(), capturedBody(response))
                },
            )
            // Which phase(s) to pause isn't observable from a captured row, so the seeded rule takes the
            // same default as a hand-added one (response) and the user adjusts it in the editor.
            add(ContextMenuAction("Breakpoints\u2026") { onBreakpointFromUrl(url, ruleMethod) })
        }
    }

    ContextMenuHost(actions) {
        Box(
            Modifier.fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .clickable(onClick = onClick),
        ) {
            Row(Modifier.horizontalScroll(hScroll)) {
                Cell(TrafficColumn.Method, widths) {
                    CellText(method.uppercase(), monoSmall(), MaterialTheme.colorScheme.onSurface)
                }
                Cell(TrafficColumn.Url, widths) {
                    CellText(request?.url ?: "", MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurface)
                }
                Cell(TrafficColumn.Status, widths) {
                    CellText(statusText(kind), MaterialTheme.typography.bodySmall, statusColor(kind))
                }
                Cell(TrafficColumn.Code, widths) {
                    CellText(codeText(code), monoSmall(), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Cell(TrafficColumn.Client, widths) {
                    CellText(entry.appId, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Cell(TrafficColumn.Timestamp, widths) {
                    CellText(
                        formatClockTime(exchange.started_at_epoch_ms + zoneOffsetMillis),
                        monoSmall(),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Cell(TrafficColumn.Duration, widths) {
                    CellText(durationText(exchange.duration_ms), monoSmall(), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Cell(TrafficColumn.Request, widths) {
                    CellText(formatBytes(request?.body_size ?: 0L), monoSmall(), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Cell(TrafficColumn.Response, widths) {
                    CellText(formatBytes(response?.body_size ?: 0L), monoSmall(), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Cell(TrafficColumn.Edited, widths) {
                    if (entry.edited) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.ic_check),
                            contentDescription = "Edited",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(
    column: TrafficColumn,
    widths: SnapshotStateMap<TrafficColumn, Dp>,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier.width(widths[column] ?: column.defaultWidth)
            .padding(horizontal = CellHPad, vertical = RowVPad),
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

// Every data cell is single-line and ellipsizes on overflow (columns resize; cells never scroll).
@Composable
private fun CellText(text: String, style: TextStyle, color: Color) {
    Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun durationText(durationMs: Long): String = if (durationMs > 0) "$durationMs ms" else "—"

// Uses the FAB's default colors (primaryContainer/onPrimaryContainer), which tokens.json themes to the
// accent — so the button tracks the brand knob without a per-call-site override. Only the elevation is
// customized below.
@Composable
private fun JumpToLatest(modifier: Modifier, onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        // The default 6.dp shadow reads as too heavy floating over the table; keep it subtle.
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 2.dp,
            pressedElevation = 2.dp,
            focusedElevation = 2.dp,
            hoveredElevation = 3.dp,
        ),
    ) {
        Icon(
            imageVector = vectorResource(Res.drawable.ic_arrow_downward),
            contentDescription = "Jump to latest",
        )
    }
}

// The captured response's raw bytes to open a seeded rule's body on, or null when there's nothing to
// carry. Null, not an empty array: it's what tells the editor to load the rule's own stored body instead.
private fun capturedBody(response: HttpResponse?): ByteArray? =
    response?.body?.takeIf { it.size > 0 }?.toByteArray()

// Scrolls just enough to reveal [index] when it sits past either viewport edge; a fully visible row
// stays put so keyboard navigation doesn't jolt the list on every keystroke.
private suspend fun LazyListState.ensureItemVisible(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
        return
    }
    val above = item.offset - info.viewportStartOffset
    val below = (item.offset + item.size) - info.viewportEndOffset
    when {
        above < 0 -> animateScrollBy(above.toFloat())
        below > 0 -> animateScrollBy(below.toFloat())
    }
}
