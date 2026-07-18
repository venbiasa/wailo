package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Desktop [HoverTooltip]: a themed [TooltipArea] that pops [label] just below the anchored icon. It's
 * pinned to the icon's bottom-right and grows left/down (not centered) because the tool rail hugs the
 * window's right edge — a centered tip would spill off-screen. Styled like the right-click menu popup
 * (surfaceContainer + outline, small elevation) so floating chrome reads as one language and stays
 * within the neutral token set (both light and dark).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun HoverTooltip(label: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        delayMillis = 400,
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = Alignment.BottomEnd,
            alignment = Alignment.BottomStart,
            offset = DpOffset(0.dp, 4.dp),
        ),
        content = content,
    )
}
