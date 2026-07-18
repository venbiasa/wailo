package com.venbiasa.wailo.shared.ui

import androidx.compose.runtime.Composable

/**
 * Wraps [content] so that hovering it surfaces [label] as a small tooltip after a short delay — the
 * tool rail is icon-only and some glyphs aren't self-evident. Hover is a desktop-only affordance (like
 * [resizeCursor]), so non-desktop actuals just render [content] unchanged.
 */
@Composable
internal expect fun HoverTooltip(label: String, content: @Composable () -> Unit)
