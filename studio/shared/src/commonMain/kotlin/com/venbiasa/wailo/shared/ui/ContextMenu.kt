package com.venbiasa.wailo.shared.ui

import androidx.compose.runtime.Composable

/**
 * One action in a right-click context menu: its [label] and what selecting it does. [checked] drives a
 * leading check glyph: `null` reserves no icon space (a plain text row); `true`/`false` reserves a
 * fixed leading slot and shows the tick only when `true`, so the label stays put as the same item
 * flips state (e.g. a "Bookmark" row that gains a tick once the host is saved).
 */
internal data class ContextMenuAction(
    val label: String,
    val checked: Boolean? = null,
    val onSelect: () -> Unit,
)

/**
 * Wraps [content] so a secondary (right) click opens a native context menu of [actions]; an empty
 * [actions] list means no menu (a plain passthrough). Desktop supplies the real menu via
 * `ContextMenuArea`, which honors the platform's right-click conventions (macOS Control-click and
 * trackpad two-finger tap) that a hand-rolled pointer handler silently misses. Non-desktop targets
 * have no right-click and just render [content] — the same expect/actual seam as [resizeCursor].
 */
@Composable
internal expect fun ContextMenuHost(
    actions: List<ContextMenuAction>,
    content: @Composable () -> Unit,
)
