package com.venbiasa.wailo.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The set of comparisons Studio has windows open for (ADR-0079). Each rule here is one a window depends on:
 * whether a comparison is new, whether it is the same one seen from the other side, and whether its window
 * survives a swap or a poll.
 */
class OpenComparisonsTest {

    @Test
    fun aSecondComparisonJoinsTheFirstRatherThanReplacingIt() {
        val open = listOf<Comparison>()
            .withOpened(Comparison("a", "b"))
            .withOpened(Comparison("c", "d"))

        assertEquals(listOf(Comparison("a", "b"), Comparison("c", "d")), open)
    }

    /** Same two rows, either way round: one question, so one window — which the caller then raises. */
    @Test
    fun reopeningAComparisonFromEitherSideAddsNothing() {
        val open = listOf(Comparison("a", "b"))

        assertEquals(open, open.withOpened(Comparison("a", "b")))
        assertEquals(open, open.withOpened(Comparison("b", "a")))
        assertEquals(Comparison("a", "b").key, Comparison("b", "a").key)
    }

    @Test
    fun closingOneLeavesTheOthers() {
        val open = listOf(Comparison("a", "b"), Comparison("c", "d"))

        assertEquals(listOf(Comparison("c", "d")), open.withClosed(Comparison("a", "b").key))
    }

    /** A swap has to keep the comparison's key and its slot, or its window is torn down and reopened. */
    @Test
    fun swappingSidesKeepsThePositionAndTheKey() {
        val open = listOf(Comparison("a", "b"), Comparison("c", "d"))
        val swapped = open.withSidesSwapped(Comparison("c", "d").key)

        assertEquals(listOf(Comparison("a", "b"), Comparison("d", "c")), swapped)
        assertEquals(open.map { it.key }, swapped.map { it.key })
    }

    @Test
    fun aComparisonWhoseRowIsGoneIsDropped() {
        val open = listOf(Comparison("a", "b"), Comparison("b", "c"))

        assertEquals(listOf(Comparison("a", "b")), open.withOnlyLive(setOf("a", "b")))
        assertEquals(emptyList(), open.withOnlyLive(emptySet()))
    }

    /** The live ids are recomputed on every poll, so an unchanged set must not restate the windows. */
    @Test
    fun pruningNothingHandsBackTheSameList() {
        val open = listOf(Comparison("a", "b"))

        assertSame(open, open.withOnlyLive(setOf("a", "b", "c")))
    }
}
