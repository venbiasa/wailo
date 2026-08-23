package com.venbiasa.wailo.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The two pure halves of dragging a rule past the edge of its panel: the edge-band speed curve, and the
// hit-test that turns on-screen row positions into a drop slot. Both are only exercisable in a real drag,
// which is exactly the thing a test can't perform, so they are pinned here as functions of numbers.
class DragAutoScrollTest {

    private fun speedIn(viewport: ClosedFloatingPointRange<Float>, pointerY: Float, band: Float = 64f) =
        dragAutoScrollSpeed(
            pointerY = pointerY,
            viewportStart = viewport.start,
            viewportEnd = viewport.endInclusive,
            bandPx = band,
            maxPxPerSecond = 1500f,
        )

    @Test
    fun pointerAwayFromBothEdgesDoesNotScroll() {
        assertEquals(0f, speedIn(0f..500f, 250f))
        assertEquals(0f, speedIn(0f..500f, 64f))
        assertEquals(0f, speedIn(0f..500f, 436f))
    }

    @Test
    fun edgeBandRampsUpTowardTheEdgeAndSignsTheDirection() {
        // Negative scrolls toward the start of the list, positive toward its end.
        assertEquals(-1500f, speedIn(0f..500f, 0f))
        assertEquals(1500f, speedIn(0f..500f, 500f))
        // Quadratic, so half-way into the band is a quarter of the speed — the shallow end has to stay
        // slow enough to drop a rule next to an edge on purpose.
        assertEquals(-375f, speedIn(0f..500f, 32f))
        assertEquals(375f, speedIn(0f..500f, 468f))
    }

    @Test
    fun pointerDraggedPastTheViewportHoldsTopSpeed() {
        assertEquals(-1500f, speedIn(0f..500f, -200f))
        assertEquals(1500f, speedIn(0f..500f, 700f))
    }

    @Test
    fun panelShorterThanTwoBandsStillScrollsBothWays() {
        assertTrue(speedIn(0f..80f, 10f) < 0f)
        assertTrue(speedIn(0f..80f, 70f) > 0f)
    }

    // Rows 5..12 of a 20-row list are measured; everything else has been scrolled out of view.
    private fun windowedCenters(index: Int): Float? = if (index in 5..12) 20f + (index - 5) * 40f else null

    @Test
    fun unmeasuredRowsBelowTheWindowAreNotCountedAsAbove() {
        // Three of the eight measured rows are above the pointer, so the slot is after the five scrolled off
        // the top plus those three. Counting the seven-row unmeasured tail too would append to the very end.
        assertEquals(8, countRowsAbove(20, ::windowedCenters, pointerY = 130f))
    }

    @Test
    fun unmeasuredRowsAboveTheWindowAreCountedAsAbove() {
        assertEquals(5, countRowsAbove(20, ::windowedCenters, pointerY = 0f))
        assertEquals(13, countRowsAbove(20, ::windowedCenters, pointerY = 400f))
    }

    @Test
    fun fullyMeasuredListResolvesEverySlot() {
        val centers: (Int) -> Float = { 10f + it * 20f }
        assertEquals(0, countRowsAbove(3, centers, pointerY = 0f))
        assertEquals(1, countRowsAbove(3, centers, pointerY = 15f))
        assertEquals(2, countRowsAbove(3, centers, pointerY = 35f))
        assertEquals(3, countRowsAbove(3, centers, pointerY = 999f))
    }
}
