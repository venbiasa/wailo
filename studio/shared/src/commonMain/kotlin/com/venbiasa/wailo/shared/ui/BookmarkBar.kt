package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A one-row strip of host filter chips below the top bar. The leading `All` chip clears the filter;
 * each following chip filters the traffic list to one bookmarked host. The active chip is filled;
 * the rest read as quiet outlines. The strip scrolls horizontally when the chips overrun the window,
 * so a long bookmark list never pushes the table off-screen. Right-clicking a host chip removes it.
 *
 * Rendered only when [bookmarks] is non-empty (the caller guards this), so a bookmark-free setup
 * costs zero vertical space.
 */
@Composable
internal fun BookmarkBar(
    bookmarks: List<String>,
    activeHost: String?,
    onSelect: (String?) -> Unit,
    onRemove: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookmarkChip(
            label = "All",
            selected = activeHost == null,
            onClick = { onSelect(null) },
            onRemove = null,
        )
        bookmarks.forEach { host ->
            BookmarkChip(
                label = host,
                selected = host == activeHost,
                onClick = { onSelect(host) },
                onRemove = { onRemove(host) },
            )
        }
    }
}

/**
 * A single stadium chip. [onRemove] is null for the fixed `All` chip (nothing to remove); when set,
 * a secondary (right) click opens a native menu to delete the bookmark, keeping the removal gesture
 * consistent with the traffic rows' right-click menu.
 */
@Composable
private fun BookmarkChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    // Selected fills with the accent so the active filter reads at a glance in both schemes; the earlier
    // surfaceVariant fill was within a few values of the surfaceContainer strip in light mode and washed
    // out. Unselected stays a quiet outline chip and leans on the border to separate from the strip.
    val container = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline

    val actions = if (onRemove != null) listOf(ContextMenuAction("Remove", onSelect = onRemove)) else emptyList()

    ContextMenuHost(actions) {
        Box(
            Modifier.clip(CircleShape)
                .background(container)
                .border(1.dp, border, CircleShape)
                .clickable(onClick = onClick)
                .widthIn(max = 220.dp)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
