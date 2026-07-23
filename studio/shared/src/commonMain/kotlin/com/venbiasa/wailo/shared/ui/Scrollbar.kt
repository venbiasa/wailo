package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.ScrollState
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

/**
 * Overlays a horizontal scrollbar for a [scrollState]-backed scrollable — the traffic table (header +
 * rows share one [ScrollState]) and the code editor when soft wrap is off. Like [VerticalListScrollbar]
 * the desktop thumb paints only while the content overflows, so it self-hides when everything fits;
 * non-desktop targets render nothing.
 */
@Composable
internal expect fun HorizontalScrollbar(scrollState: ScrollState, modifier: Modifier)
