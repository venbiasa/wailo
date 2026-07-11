package com.venbiasa.wailo.shared.ui

import androidx.compose.ui.Modifier

// Android has no hover cursor, so this is a no-op; the drag handles still work.
internal actual fun Modifier.resizeCursor(axis: ResizeAxis): Modifier = this
