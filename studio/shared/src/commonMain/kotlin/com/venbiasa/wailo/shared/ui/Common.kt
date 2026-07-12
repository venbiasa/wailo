package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The traffic table's columns, in display order, all left-aligned. [defaultWidth] is a compile-time
 * starting size chosen for each column's expected content; the user can then drag columns wider or
 * narrower down to [minWidth] ([rememberColumnWidths]). The header and every row read the same width
 * map, so they stay aligned.
 */
internal enum class TrafficColumn(
    val title: String,
    val defaultWidth: Dp,
    val minWidth: Dp,
) {
    Method("Method", 84.dp, 56.dp),
    Url("URL", 340.dp, 120.dp),
    Status("Status", 128.dp, 84.dp),
    Code("Code", 64.dp, 56.dp),
    Client("Client", 190.dp, 96.dp),
    Timestamp("Timestamp", 124.dp, 96.dp),
    Duration("Duration", 100.dp, 72.dp),
    Request("Request", 96.dp, 72.dp),
    Response("Response", 104.dp, 76.dp),
    Edited("Edited", 76.dp, 60.dp),
}

/** Live per-column widths, seeded from [TrafficColumn.defaultWidth] and mutated in place as the user drags. */
@Composable
internal fun rememberColumnWidths(): SnapshotStateMap<TrafficColumn, Dp> = remember {
    mutableStateMapOf<TrafficColumn, Dp>().apply {
        TrafficColumn.entries.forEach { put(it, it.defaultWidth) }
    }
}

@Composable
internal fun RowDivider(color: Color = MaterialTheme.colorScheme.outlineVariant) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

// Monospace styles keep the numeric columns and payloads tabular; chrome/text stays Noto Sans.
@Composable
internal fun monoSmall(): TextStyle =
    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

@Composable
internal fun monoLabel(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
