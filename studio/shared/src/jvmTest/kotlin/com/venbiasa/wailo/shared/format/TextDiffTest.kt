package com.venbiasa.wailo.shared.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextDiffTest {

    /**
     * The shape that made the whole feature useless: a long body whose two changes sit at opposite ends, so
     * head/tail trimming leaves a core of thousands of lines. Aligning by table cost n×m regardless of how
     * alike the sides were, so a size cap turned the easiest possible diff into "everything changed".
     */
    @Test
    fun aLongBodyChangedInTwoPlacesReportsTwoChangesAndNotTheWholeDocument() {
        val left = buildList {
            add("{")
            add("  \"secondsLeft\": 1800,")
            for (i in 0 until 4000) add("  \"field$i\": \"value$i\",")
            add("  \"servedAt\": \"2026-01-01T00:00:00Z\"")
            add("}")
        }
        val right = left.toMutableList().apply {
            this[1] = "  \"secondsLeft\": 1797,"
            this[size - 2] = "  \"servedAt\": \"2026-01-01T00:00:03Z\""
        }

        val rows = diffLines(left, right, ::jsonLineKey)

        assertEquals(left.size, rows.size)
        assertEquals(2, rows.count { it.op != DiffOp.Same })
        assertEquals(listOf(1, left.size - 2), rows.mapIndexedNotNull { i, r -> i.takeIf { r.op != DiffOp.Same } })
        assertTrue(rows.filter { it.op != DiffOp.Same }.all { it.op == DiffOp.Changed })
    }

    /**
     * Two *different* records of the same shape rather than two captures of one: half the length, changes
     * scattered through it, and an edit distance in the thousands. Still an alignment worth showing, and the
     * one a distance cap set by guesswork rejects.
     */
    @Test
    fun twoDifferentRecordsOfTheSameShapeStillAlignOnWhatTheyShare() {
        val left = buildList {
            for (block in 0 until 50) {
                for (i in 0 until 31) add("  \"shared$block-$i\": $i,")
                for (i in 0 until 49) add("  \"leftOnly$block-$i\": $i,")
            }
        }
        val right = buildList {
            for (block in 0 until 50) {
                for (i in 0 until 31) add("  \"shared$block-$i\": $i,")
                for (i in 0 until 9) add("  \"rightOnly$block-$i\": $i,")
            }
        }

        val rows = diffLines(left, right, ::jsonLineKey)

        // 1550 lines are common, so the edit distance is (4000-1550) + (2000-1550) = 2900.
        assertEquals(1550, rows.count { it.op == DiffOp.Same })
        assertEquals(left.size, rows.count { it.leftNumber != null })
        assertEquals(right.size, rows.count { it.rightNumber != null })
    }

    @Test
    fun aWholesaleReplacementStillFallsBackInsteadOfSearchingForever() {
        val left = (0 until 3000).map { "left line $it" }
        val right = (0 until 3000).map { "right line $it" }

        val rows = diffLines(left, right)

        assertEquals(3000, rows.size)
        assertTrue(rows.all { it.op == DiffOp.Changed })
    }

    @Test
    fun identicalTextIsAllSameRowsWithMatchingNumbers() {
        val lines = listOf("{", "  \"a\": 1", "}")
        val rows = diffLines(lines, lines)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.op == DiffOp.Same })
        assertEquals(listOf(1, 2, 3), rows.map { it.leftNumber })
        assertEquals(listOf(1, 2, 3), rows.map { it.rightNumber })
    }

    @Test
    fun oneChangedLineIsPairedNotSplitIntoARemoveAndAnAdd() {
        val rows = diffLines(
            listOf("{", "  \"total\": 1200", "}"),
            listOf("{", "  \"total\": 1900", "}"),
        )
        assertEquals(3, rows.size)
        assertEquals(DiffOp.Changed, rows[1].op)
        assertEquals("  \"total\": 1200", rows[1].left)
        assertEquals("  \"total\": 1900", rows[1].right)
        // Both sides are real lines, so neither gutter is blank.
        assertEquals(2, rows[1].leftNumber)
        assertEquals(2, rows[1].rightNumber)
    }

    @Test
    fun anAddedLineGetsAFillerOnTheLeftAndShiftsOnlyTheRightNumbers() {
        val rows = diffLines(
            listOf("a", "c"),
            listOf("a", "b", "c"),
        )
        assertEquals(3, rows.size)
        assertEquals(DiffOp.Added, rows[1].op)
        assertNull(rows[1].left)
        assertNull(rows[1].leftNumber)
        assertEquals("b", rows[1].right)
        assertEquals(2, rows[1].rightNumber)
        // The trailing shared line keeps each side's own numbering.
        assertEquals(2, rows[2].leftNumber)
        assertEquals(3, rows[2].rightNumber)
    }

    @Test
    fun aRemovedLineGetsAFillerOnTheRight() {
        val rows = diffLines(listOf("a", "b", "c"), listOf("a", "c"))
        assertEquals(DiffOp.Removed, rows[1].op)
        assertEquals("b", rows[1].left)
        assertNull(rows[1].right)
        assertNull(rows[1].rightNumber)
    }

    @Test
    fun anUnevenRunPairsWhatItCanAndSpillsTheRest() {
        // Two lines replaced by three: two Changed pairs, then the leftover addition.
        val rows = diffLines(
            listOf("head", "x1", "x2", "tail"),
            listOf("head", "y1", "y2", "y3", "tail"),
        )
        assertEquals(listOf(DiffOp.Same, DiffOp.Changed, DiffOp.Changed, DiffOp.Added, DiffOp.Same), rows.map { it.op })
        assertEquals("y3", rows[3].right)
        assertNull(rows[3].left)
    }

    @Test
    fun emptySidesDegradeToPureAdditionsOrRemovals() {
        assertTrue(diffLines(emptyList(), emptyList()).isEmpty())
        assertTrue(diffLines(emptyList(), listOf("a", "b")).all { it.op == DiffOp.Added })
        assertTrue(diffLines(listOf("a", "b"), emptyList()).all { it.op == DiffOp.Removed })
    }

    @Test
    fun everyRowKeepsItsSideInDocumentOrder() {
        val left = listOf("1", "2", "3", "4", "5")
        val right = listOf("1", "9", "3", "4", "8", "6")
        val rows = diffLines(left, right)
        assertEquals(left, rows.mapNotNull { it.left })
        assertEquals(right, rows.mapNotNull { it.right })
        // Numbering never repeats or goes backwards on either side.
        assertEquals(left.indices.map { it + 1 }, rows.mapNotNull { it.leftNumber })
        assertEquals(right.indices.map { it + 1 }, rows.mapNotNull { it.rightNumber })
    }

    @Test
    fun inlineRangeWidensToWholeTokensRatherThanTheBareDifferingCharacters() {
        val leftLine = "  \"total\": 1200"
        val rightLine = "  \"total\": 1900"
        val (left, right) = inlineDiffRange(leftLine, rightLine)
        // Only the second digit actually differs; highlighting just that would be unreadable.
        assertEquals("1200", leftLine.substring(assertNotNull(left)))
        assertEquals("1900", rightLine.substring(assertNotNull(right)))
    }

    @Test
    fun inlineRangeCoversAnInsertionOnOneSideOnly() {
        val (left, right) = inlineDiffRange("a c", "a b c")
        assertNull(left)
        // The span covers the inserted run together with the separator it brought along.
        assertEquals("b ", "a b c".substring(assertNotNull(right)))
    }

    @Test
    fun identicalLinesHaveNoInlineRange() {
        val (left, right) = inlineDiffRange("same", "same")
        assertNull(left)
        assertNull(right)
    }

    @Test
    fun hunksGroupConsecutiveChangedRowsIntoOneStop() {
        val rows = diffLines(
            listOf("a", "b", "c", "d", "e"),
            listOf("a", "B", "C", "d", "E"),
        )
        // b/c are one hunk, e is another — so stepping visits two places, not three.
        assertEquals(2, changeHunkStarts(rows).size)
    }

    @Test
    fun noChangesMeansNoHunks() {
        assertTrue(changeHunkStarts(diffLines(listOf("a"), listOf("a"))).isEmpty())
    }

    @Test
    fun aTrailingCommaAloneDoesNotMakeAJsonLineChanged() {
        // `b` is untouched; it only grew a comma because `c` was appended after it. Compared literally the
        // core has no common line at all, so every property in the block pairs up and reads as changed.
        val left = listOf("{", "  \"a\": 1,", "  \"b\": 2", "}")
        val right = listOf("{", "  \"a\": 9,", "  \"b\": 2,", "  \"c\": 3", "}")

        val literal = diffLines(left, right)
        assertEquals(
            listOf(DiffOp.Same, DiffOp.Changed, DiffOp.Changed, DiffOp.Added, DiffOp.Same),
            literal.map { it.op },
        )

        val keyed = diffLines(left, right, ::jsonLineKey)
        assertEquals(
            listOf(DiffOp.Same, DiffOp.Changed, DiffOp.Same, DiffOp.Added, DiffOp.Same),
            keyed.map { it.op },
        )
        // The row is Same but each side still shows its own text, comma and all.
        assertEquals("  \"b\": 2", keyed[2].left)
        assertEquals("  \"b\": 2,", keyed[2].right)
        // Stepping gets more precise too: literally, the spurious middle row welds the changed `a` and the
        // added `c` into one run, so next/previous offers a single stop covering the whole block.
        assertEquals(1, changeHunkStarts(literal).size)
        assertEquals(2, changeHunkStarts(keyed).size)
    }

    @Test
    fun keyingOnlyIgnoresTheCommaAndNotTheContentBeforeIt() {
        val rows = diffLines(listOf("  \"a\": 1,"), listOf("  \"a\": 2"), ::jsonLineKey)
        assertEquals(DiffOp.Changed, rows[0].op)
    }

    @Test
    fun anEmptiedArrayDoesNotDragTheChangeBelowItOutOfAlignment() {
        // The array's lines vanish from the right, and `page` changes underneath. Both land in one run, so
        // pairing by position hands `{` a partner five lines from where it belongs and reports the real
        // change as a deletion — everything after the array reads as modified.
        val left = canonicalJson(parseJson("""{"items":[{"id":1}],"page":1,"total":9}""")!!, sortKeys = true)
        val right = canonicalJson(parseJson("""{"items":[],"page":2,"total":9}""")!!, sortKeys = true)
        val rows = diffLines(left.lines(), right.lines(), ::jsonLineKey)

        // `page` is paired with `page`, and nothing else on the right is claimed by the array's removal.
        val changed = rows.filter { it.op == DiffOp.Changed }
        assertEquals(
            listOf("  \"items\": [" to "  \"items\": []," , "  \"page\": 1," to "  \"page\": 2,"),
            changed.map { it.left to it.right },
        )
        // The array's own lines are removals against fillers, not partners for the properties below.
        assertTrue(rows.filter { it.op == DiffOp.Removed }.all { it.right == null })
        // `total` never moved, so it must still read as unchanged.
        assertTrue(rows.any { it.op == DiffOp.Same && it.left == "  \"total\": 9" })
    }

    @Test
    fun aDeeperLineStepsAsideSoTheRealPartnerBelowItCanPair() {
        // `    {` sits opposite `"page": 2,` but is nested a level further in, and the line it really
        // belongs with is one further down — so it becomes a removal instead of spending the pair.
        val rows = diffLines(
            listOf("head", "    {", "  \"page\": 1,", "tail"),
            listOf("head", "  \"page\": 2,", "tail"),
        )
        assertEquals(listOf(DiffOp.Same, DiffOp.Removed, DiffOp.Changed, DiffOp.Same), rows.map { it.op })
        assertEquals("  \"page\": 1,", rows[2].left)
        assertEquals("  \"page\": 2,", rows[2].right)
    }

    @Test
    fun anExtraLineOnTheRightStepsAsideJustAsOneOnTheLeftDoes() {
        // The mirror of the case above. Searching for a partner from the first index it *could* sit at makes
        // "one line further on" and "right here" the same answer, which pairs `page` with the brace above it
        // and leaves its real partner reading as a bare insertion — the whole run below reads as changed.
        val rows = diffLines(
            listOf("head", "  \"page\": 1,", "tail"),
            listOf("head", "    {", "  \"page\": 2,", "tail"),
        )
        assertEquals(listOf(DiffOp.Same, DiffOp.Added, DiffOp.Changed, DiffOp.Same), rows.map { it.op })
        assertEquals("  \"page\": 1,", rows[2].left)
        assertEquals("  \"page\": 2,", rows[2].right)
    }

    @Test
    fun aRunWithNoCorrespondenceEitherWayStillPairsForAnInlineHighlight() {
        // The ordinary "these lines were replaced" case: no partner exists on either side, so pairing is
        // still what gives each row a left and a right to highlight within.
        val rows = diffLines(listOf("head", "b", "c", "tail"), listOf("head", "B", "C", "tail"))
        assertEquals(listOf(DiffOp.Same, DiffOp.Changed, DiffOp.Changed, DiffOp.Same), rows.map { it.op })
    }

    @Test
    fun jsonLineKeyLeavesACommaInsideAStringAlone() {
        // Only the structural comma is punctuation; one inside a value is part of the value.
        assertEquals("  \"note\": \"a, b\"", jsonLineKey("  \"note\": \"a, b\""))
        assertEquals("  \"note\": \"a, b\"", jsonLineKey("  \"note\": \"a, b\","))
    }
}
