package com.venbiasa.wailo.shared.ui

import androidx.compose.ui.Modifier

/** A drag handle's axis, used to pick the matching OS resize cursor where one exists. */
internal enum class ResizeAxis { Horizontal, Vertical }

/**
 * While hovered, shows the OS resize cursor for [axis] so a drag handle reads as draggable. Only
 * desktop has hover cursors; other targets return the modifier unchanged (the handle still drags).
 */
internal expect fun Modifier.resizeCursor(axis: ResizeAxis): Modifier
