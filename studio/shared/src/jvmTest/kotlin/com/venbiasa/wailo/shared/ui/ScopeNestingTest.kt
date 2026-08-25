package com.venbiasa.wailo.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals

private fun nestingOf(text: String): ScopeNesting =
    ScopeNesting.from(computeFoldRegions(CodeEditorState(text)))

/** What the sticky header pins: the blocks still open above a line. */
class ScopeNestingTest {

    // 0 {
    // 1   "page": {
    // 2     "items": [
    // 3       {
    // 4         "id": 1
    // 5       }
    // 6     ]
    // 7   },
    // 8   "tail": 1
    // 9 }
    private val nested = nestingOf(
        """
        {
          "page": {
            "items": [
              {
                "id": 1
              }
            ]
          },
          "tail": 1
        }
        """.trimIndent(),
    )

    @Test
    fun ancestorsRunOutermostFirst() {
        assertEquals(listOf(0, 1, 2, 3), nested.ancestorsOf(4, maxDepth = 5))
    }

    /** A block's own opener is not above it, and by its closing bracket the block is behind the reader. */
    @Test
    fun openerAndCloserSitOutsideTheirOwnBlock() {
        assertEquals(listOf(0, 1, 2), nested.ancestorsOf(3, maxDepth = 5))
        assertEquals(listOf(0, 1, 2), nested.ancestorsOf(5, maxDepth = 5))
        assertEquals(emptyList<Int>(), nested.ancestorsOf(0, maxDepth = 5))
        assertEquals(emptyList<Int>(), nested.ancestorsOf(9, maxDepth = 5))
    }

    /** Reaching a line back at the root walks out through four closed blocks, one hop each. */
    @Test
    fun closedBlocksAreSteppedOverOnTheWayOut() {
        assertEquals(listOf(0), nested.ancestorsOf(8, maxDepth = 5))
    }

    /**
     * Past the limit the outermost parents are kept, so the rows already on screen stay put as the reader
     * moves between depths — cutting the other end would slide every row of the band on each change.
     */
    @Test
    fun theCutKeepsTheOutermostParents() {
        assertEquals(listOf(0, 1), nested.ancestorsOf(4, maxDepth = 2))
        assertEquals(listOf(0, 1), nested.ancestorsOf(3, maxDepth = 2))
        assertEquals(emptyList<Int>(), nested.ancestorsOf(4, maxDepth = 0))
    }

    /**
     * A row deep in an array has every earlier element between it and the array's own opener. None of those
     * siblings is a parent, and the walk is what keeps the answer from costing one hop per element.
     */
    @Test
    fun siblingsAreNotParents() {
        val element = "  {\n    \"id\": 0,\n    \"name\": \"x\"\n  },\n"
        val nesting = nestingOf("[\n" + element.repeat(400) + "]")
        val lastOpener = 1 + 399 * 4
        assertEquals(listOf(0, lastOpener), nesting.ancestorsOf(lastOpener + 1, maxDepth = 5))
    }

    /** `], [` shares a line without nesting: the bracket that closed came first. */
    @Test
    fun blocksMeetingOnOneLineAreSiblings() {
        val nesting = nestingOf(
            """
            [
              1
            ], [
              2
            ]
            """.trimIndent(),
        )
        assertEquals(listOf(2), nesting.ancestorsOf(3, maxDepth = 5))
    }

    /** The band drops the outermost container, and still cuts the deep end of what is left. */
    @Test
    fun theBandLeavesOutTheRootBracket() {
        assertEquals(listOf(1, 2, 3), nested.stickyAncestorsOf(4, maxRows = 5))
        assertEquals(listOf(1, 2), nested.stickyAncestorsOf(4, maxRows = 2))
    }

    /** A line the root alone encloses pins nothing, so a shallow body carries no band at all. */
    @Test
    fun aLineAtTheRootPinsNothing() {
        assertEquals(emptyList<Int>(), nested.stickyAncestorsOf(8, maxRows = 5))
        assertEquals(emptyList<Int>(), nested.stickyAncestorsOf(4, maxRows = 0))
    }

    @Test
    fun aDocumentWithNoBlocksPinsNothing() {
        assertEquals(emptyList<Int>(), nestingOf("just text").ancestorsOf(0, maxDepth = 5))
        assertEquals(emptyList<Int>(), ScopeNesting.Empty.ancestorsOf(3, maxDepth = 5))
    }
}
