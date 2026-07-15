package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // One scroll state shared by the header and every row keeps the columns locked together as the
    // table scrolls sideways when the columns are wider than the viewport.
    val hScroll = rememberScrollState()
    val widths = rememberColumnWidths()
    val density = LocalDensity.current

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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { entry ->
                    TrafficRow(
                        entry = entry,
                        widths = widths,
                        hScroll = hScroll,
                        selected = entry.id == selectedId,
                        onClick = { onSelect(entry.id) },
                        zoneOffsetMillis = zoneOffsetMillis,
                        bookmarks = bookmarks,
                        onAddBookmark = onAddBookmark,
                        onRemoveBookmark = onRemoveBookmark,
                    )
                    RowDivider()
                }
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
) {
    val exchange = entry.exchange
    val request = exchange.request
    val response = exchange.response
    val method = request?.method?.ifEmpty { "?" } ?: "?"
    val code = response?.code
    val hasError = exchange.error.isNotEmpty()
    val kind = statusKind(code, hasError)

    // Right-click toggles this row's host as a bookmark; a row whose URL has no parseable host gets no
    // action, so its context menu is empty (a plain passthrough). The single "Bookmark" entry carries a
    // tick once saved (and reserves the slot when not), so the label never shifts as it toggles.
    val host = remember(request?.url) { requestHost(request?.url ?: "") }
    val bookmarked = host in bookmarks
    val actions = if (host.isEmpty()) {
        emptyList()
    } else {
        listOf(
            ContextMenuAction("Bookmark", checked = bookmarked) {
                if (bookmarked) onRemoveBookmark(host) else onAddBookmark(host)
            },
        )
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

// Colors track the theme's accent rather than the FAB default (primaryContainer), which this grayscale
// scheme never defines and would otherwise fall back to the Material baseline purple.
@Composable
private fun JumpToLatest(modifier: Modifier, onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
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
