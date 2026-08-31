package com.venbiasa.wailo.shared.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

// What a restart depends on: the map the host stores is the only description of the table's layout, so
// these pin how a width that is missing, present, or nonsense resolves back into a column.
class TrafficColumnLayoutTest {

    @Test
    fun everyColumnCanBeSaved() {
        // A key missing here is a column whose width the host would silently never persist.
        assertEquals(TrafficColumn.entries.map { it.name }, TrafficColumnLayout.Keys)
    }

    @Test
    fun aColumnWithNoSavedWidthKeepsItsDefault() {
        assertEquals(TrafficColumn.Url.defaultWidth, emptyMap<String, Float>().widthOf(TrafficColumn.Url))
    }

    @Test
    fun aSavedWidthWins() {
        assertEquals(600.dp, mapOf("Url" to 600f).widthOf(TrafficColumn.Url))
    }

    @Test
    fun aSavedWidthBelowTheFloorIsClamped() {
        // Hand-edited prefs, or a floor raised since the width was saved: either way the column has to stay
        // wide enough to grab its resize handle again.
        assertEquals(TrafficColumn.Url.minWidth, mapOf("Url" to 1f).widthOf(TrafficColumn.Url))
    }

    @Test
    fun widthsForOtherColumnsAreIgnored() {
        assertEquals(TrafficColumn.Url.defaultWidth, mapOf("Method" to 600f).widthOf(TrafficColumn.Url))
    }
}
