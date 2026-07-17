package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_left_panel_close
import com.venbiasa.wailo.shared.resources.ic_left_panel_open
import com.venbiasa.wailo.shared.resources.ic_rule
import com.venbiasa.wailo.shared.resources.ic_swap_vert
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

private val ExpandedWidth = 200.dp
private val CollapsedWidth = 52.dp

/**
 * The left tool rail: a persistent launcher for Wailo's tools. `Traffic` is the always-on main view
 * (shown selected); tools like `Map Local` open their own window via [onOpenMapLocal]. It collapses
 * to an icon-only strip so it stays cheap on narrower desktops, and grows as more tools land.
 */
@Composable
internal fun NavRail(
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onOpenMapLocal: () -> Unit,
) {
    Column(
        Modifier.fillMaxHeight()
            .width(if (collapsed) CollapsedWidth else ExpandedWidth)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 6.dp),
    ) {
        // The toggle icon shows the resulting state: a "close" glyph while open, "open" while collapsed.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.End,
        ) {
            IconButton(onClick = onToggleCollapsed, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = vectorResource(
                        if (collapsed) Res.drawable.ic_left_panel_open else Res.drawable.ic_left_panel_close,
                    ),
                    contentDescription = if (collapsed) "Expand sidebar" else "Collapse sidebar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        NavRailItem(
            icon = Res.drawable.ic_swap_vert,
            label = "Traffic",
            collapsed = collapsed,
            selected = true,
            onClick = {},
        )
        NavRailItem(
            icon = Res.drawable.ic_rule,
            label = "Map Local",
            collapsed = collapsed,
            selected = false,
            onClick = onOpenMapLocal,
        )
    }
}

@Composable
private fun NavRailItem(
    icon: DrawableResource,
    label: String,
    collapsed: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    val tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
    ) {
        Icon(
            imageVector = vectorResource(icon),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        if (!collapsed) {
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
