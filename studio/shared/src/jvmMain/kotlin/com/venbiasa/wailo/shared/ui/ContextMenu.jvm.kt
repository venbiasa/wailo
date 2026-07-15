package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_check
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.vectorResource

@Composable
internal actual fun ContextMenuHost(
    actions: List<ContextMenuAction>,
    content: @Composable () -> Unit,
) {
    // No actions → no menu: render the content plainly so an unbookmarkable row/chip ignores the
    // right click instead of popping an empty menu.
    if (actions.isEmpty()) {
        content()
        return
    }
    // Our representation renders straight from ContextMenuAction (the platform's ContextMenuItem can't
    // carry the check state), so it reads actions through a stable holder: the representation instance
    // stays identical across recompositions, which keeps this static CompositionLocal from re-running
    // the whole wrapped row/chip subtree, while still seeing the latest actions when state flips.
    val currentActions = rememberUpdatedState(actions)
    val representation = remember { WailoContextMenuRepresentation { currentActions.value } }
    CompositionLocalProvider(LocalContextMenuRepresentation provides representation) {
        ContextMenuArea(
            items = { currentActions.value.map { action -> ContextMenuItem(action.label, action.onSelect) } },
            content = content,
        )
    }
}

/**
 * A compact, theme-driven right-click menu anchored at the cursor. Renders from [actions] rather than
 * the [ContextMenuItem]s the platform hands back, so it can show each action's leading check slot.
 */
private class WailoContextMenuRepresentation(
    private val actions: () -> List<ContextMenuAction>,
) : ContextMenuRepresentation {
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        val cursor = status.rect.topLeft
        Popup(
            popupPositionProvider = remember(cursor) { CursorPositionProvider(cursor) },
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.width(IntrinsicSize.Max).padding(vertical = 4.dp)) {
                    actions().forEach { action ->
                        MenuItem(action) {
                            state.status = ContextMenuState.Status.Closed
                            action.onSelect()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(action: ContextMenuAction, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(if (hovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(action.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        // The tick trails the label on the right; a non-null check reserves the slot even when unticked
        // so the row width (and the label position) stays put as the item toggles.
        if (action.checked != null) {
            Spacer(Modifier.width(24.dp))
            Box(Modifier.size(CheckSlot)) {
                if (action.checked) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(CheckSlot),
                    )
                }
            }
        }
    }
}

private val CheckSlot = 16.dp

/**
 * Places the popup at the right-click point: [cursor] is the press position within the menu's anchor,
 * so adding it to the anchor's window origin gives the cursor in window space. Clamped so the menu
 * never spills past the window edges.
 */
private class CursorPositionProvider(private val cursor: Offset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left + cursor.x.roundToInt())
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top + cursor.y.roundToInt())
            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}
