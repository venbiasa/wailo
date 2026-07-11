package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.FlowEntry

@Composable
internal fun WailoViewer(entries: List<FlowEntry>, zoneOffsetMillis: Int) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = remember(entries, selectedId) { entries.firstOrNull { it.id == selectedId } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val minDetail = 140.dp
            val maxDetail = (maxHeight - 160.dp).coerceAtLeast(minDetail)
            var detailHeight by remember { mutableStateOf(300.dp) }
            val density = LocalDensity.current

            Column(Modifier.fillMaxSize()) {
                TopBar(count = entries.size)
                RowDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TrafficList(
                        entries = entries,
                        selectedId = selectedId,
                        onSelect = { selectedId = it },
                        zoneOffsetMillis = zoneOffsetMillis,
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
                        zoneOffsetMillis = zoneOffsetMillis,
                        onClose = { selectedId = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(count: Int) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Wailo", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(12.dp))
        Text(
            "$count captured",
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
