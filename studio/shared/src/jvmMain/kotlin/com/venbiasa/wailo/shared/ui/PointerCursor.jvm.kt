package com.venbiasa.wailo.shared.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor

internal actual fun Modifier.resizeCursor(axis: ResizeAxis): Modifier {
    val cursor = when (axis) {
        ResizeAxis.Horizontal -> Cursor(Cursor.E_RESIZE_CURSOR)
        ResizeAxis.Vertical -> Cursor(Cursor.N_RESIZE_CURSOR)
    }
    return pointerHoverIcon(PointerIcon(cursor))
}
