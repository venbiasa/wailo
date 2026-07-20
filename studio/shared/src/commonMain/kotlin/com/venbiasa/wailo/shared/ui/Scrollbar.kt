package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Overlays a vertical scrollbar for [listState] on desktop. The desktop scrollbar paints its thumb
 * only while the content overflows the viewport, so this reads as "a scrollbar appears only when the
 * list is actually scrollable" — a short list shows none. Non-desktop targets scroll by touch and have
 * no persistent scrollbar affordance, so their actual renders nothing — the same expect/actual seam as
 * [resizeCursor] and [HoverTooltip].
 */
@Composable
internal expect fun VerticalListScrollbar(listState: LazyListState, modifier: Modifier)
