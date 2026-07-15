package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One tab in an [UnderlineTabs] group. [icon] is optional and purely decorative — selection never
 * injects or removes an icon, so text-only tabs (Headers, Body, …) stay text-only while future tabs
 * (e.g. body previewers) can carry a leading glyph.
 */
internal data class TabItem<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
)

/**
 * Quiet text tabs with a 2dp underline on the selected item, over a full-width hairline baseline —
 * the low-chrome idiom for dense inspectors. Generic over the choice type. [trailingLabel] is a
 * small muted caption pinned to the right of the row (used to mark the pane as Request/Response).
 */
@Composable
internal fun <T> UnderlineTabs(
    items: List<TabItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
) {
    Box(modifier) {
        Box(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        // The selected underline is drawn (not laid out), so the row's height is just the tab labels
        // and a centered trailing caption lines up with them cleanly.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                // The tab strip scrolls horizontally when it can't fit; the caption is a fixed sibling
                // so it never scrolls out of reach. Inter-tab spacing lives inside each tab as padding
                // (not Arrangement.spacedBy) so the hover/click area covers it too. start=6 + a tab's
                // 10 puts the first label at the 16dp content edge.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEach { item ->
                        Tab(item = item, selected = item.value == selected, onSelect = onSelect)
                    }
                }
                // A fade sits over the strip's right edge, beside the caption. It's a transparent →
                // surface gradient, so it's invisible over the bare panel and only "turns on" when a
                // tab scrolls under it — the overflow itself does the work, no scroll-state logic.
                // matchParentSize takes the tab strip's height without inflating it; a bare fillMaxHeight
                // here would instead grab the whole panel height (Column measures it against the full
                // height), stretching the tab row and off-centering the caption.
                Box(Modifier.matchParentSize()) {
                    Box(
                        Modifier.align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(28.dp)
                            .background(
                                Brush.horizontalGradient(
                                    0f to Color.Transparent,
                                    1f to MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            ),
                    )
                }
            }
            if (trailingLabel != null) {
                Text(
                    trailingLabel,
                    Modifier.padding(start = 8.dp, end = 14.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun <T> Tab(item: TabItem<T>, selected: Boolean, onSelect: (T) -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val indicator = MaterialTheme.colorScheme.onSurface
    // Horizontal padding is the hit/hover area and the inter-tab gap; the underline is inset by the
    // same amount so it keeps hugging the label rather than widening with the padding.
    val hPad = 10.dp
    Row(
        Modifier
            .clickable { onSelect(item.value) }
            .drawBehind {
                if (selected) {
                    val stroke = 2.dp.toPx()
                    val inset = hPad.toPx()
                    drawRect(
                        color = indicator,
                        topLeft = Offset(inset, size.height - stroke),
                        size = Size(size.width - inset * 2, stroke),
                    )
                }
            }
            .padding(horizontal = hPad, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item.icon?.let {
            Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
        Text(
            item.label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = color,
        )
    }
}
