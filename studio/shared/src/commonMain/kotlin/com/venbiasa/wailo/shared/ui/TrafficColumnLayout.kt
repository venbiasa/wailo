package com.venbiasa.wailo.shared.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizing for the traffic table's dragged column widths, shared by the list that renders them and the
 * host that persists them. Mirrors [ToolPanelLayout]: pure bounds + coercion, no Compose state.
 *
 * Widths travel as a `column key -> dp` map rather than as [TrafficColumn], which is internal — so the
 * host stores what it is handed without knowing the table's shape, and adding or dropping a column is
 * not a change to the host. A column with no entry keeps its [TrafficColumn.defaultWidth] instead of
 * being defaulted at save time, so the columns a user never dragged follow a later change to those
 * defaults rather than pinning the ones that happened to ship.
 */
object TrafficColumnLayout {
    /** Every column key that can carry a saved width, in display order. */
    val Keys: List<String> = TrafficColumn.entries.map { it.name }
}

// Clamped on the way out, not only when a drag sets it: a hand-edited or stale preference must not be
// able to leave a column too narrow to grab the handle of again.
internal fun Map<String, Float>.widthOf(column: TrafficColumn): Dp =
    this[column.name]?.dp?.coerceAtLeast(column.minWidth) ?: column.defaultWidth
