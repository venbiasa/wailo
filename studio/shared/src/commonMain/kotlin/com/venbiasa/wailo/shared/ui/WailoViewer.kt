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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_dark_mode
import com.venbiasa.wailo.shared.resources.ic_delete
import com.venbiasa.wailo.shared.resources.ic_light_mode
import com.venbiasa.wailo.shared.resources.ic_pause
import com.venbiasa.wailo.shared.resources.ic_play_arrow
import com.venbiasa.wailo.shared.theme.LocalWailoColors
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
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = remember(entries, selectedId) { entries.firstOrNull { it.id == selectedId } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val minDetail = 180.dp
            val maxDetail = (maxHeight - 160.dp).coerceAtLeast(minDetail)
            var detailHeight by remember { mutableStateOf(360.dp) }
            val density = LocalDensity.current

            Column(Modifier.fillMaxSize()) {
                TopBar(
                    listenAddress = listenAddress,
                    capturing = capturing,
                    onToggleCapture = onToggleCapture,
                    onClear = onClear,
                    darkTheme = darkTheme,
                    onToggleDarkTheme = onToggleDarkTheme,
                )
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
                        onClose = { selectedId = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    listenAddress: String,
    capturing: Boolean,
    onToggleCapture: () -> Unit,
    onClear: () -> Unit,
    darkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
        Spacer(Modifier.weight(1f))
        // Icon reflects the target mode (moon = switch to dark, sun = switch to light), the common
        // toggle convention so a glance tells you what tapping will do.
        IconButton(onClick = onToggleDarkTheme, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = vectorResource(if (darkTheme) Res.drawable.ic_light_mode else Res.drawable.ic_dark_mode),
                contentDescription = if (darkTheme) "Switch to light mode" else "Switch to dark mode",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
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
