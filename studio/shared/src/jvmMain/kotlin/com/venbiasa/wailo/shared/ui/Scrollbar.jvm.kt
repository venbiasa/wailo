package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.HorizontalScrollbar as FoundationHorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun VerticalListScrollbar(listState: LazyListState, modifier: Modifier) {
    // rememberScrollbarAdapter(LazyListState) tracks the list's scroll extent; VerticalScrollbar paints
    // the thumb transparent while the content fits, so it self-hides for lists that don't overflow.
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState = listState),
        modifier = modifier,
    )
}

@Composable
internal actual fun HorizontalScrollbar(scrollState: ScrollState, modifier: Modifier) {
    // Same self-hiding behavior for a ScrollState-backed horizontal scroll (the traffic table / editor).
    FoundationHorizontalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = modifier,
    )
}
