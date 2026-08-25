package com.venbiasa.wailo.shared.ui

/**
 * The nesting forest of a document's foldable regions, in the shape that answers "which blocks are still
 * open above line N" — what the sticky header asks on every scroll frame.
 *
 * Built once per document because the obvious alternative is not affordable: scanning the region map is
 * O(regions), and the row you are reading in a large array has thousands of siblings between it and its
 * parent. Each region instead keeps a pointer to the one enclosing it, so a sibling is stepped over in a
 * single hop rather than one line at a time.
 *
 * The regions come from `computeFoldRegions`, i.e. from bracket matching, so they are properly nested: a
 * region that does not contain the line cannot hide a container that the line's own chain would miss.
 */
internal class ScopeNesting private constructor(
    private val starts: IntArray,
    private val ends: IntArray,
    private val parents: IntArray,
) {
    /**
     * The opener lines of the blocks enclosing [line], outermost first, keeping at most the [maxDepth]
     * outermost.
     *
     * The cut has to fall on the deep end to hold still. Keeping the innermost instead reads better on any
     * single frame — those are the entries naming the list being iterated — but the window then slides with
     * the reader, so every row of the band changes each time the depth does, and the band flickers through
     * a long body. Cutting the deep end leaves the outer rows fixed and only ever moves the last one, which
     * is also where the header's slide-out already happens.
     *
     * A line's own opener does not enclose it, and neither does a block whose closing bracket is on that
     * line — by then the block is behind the reader, not above them.
     */
    fun ancestorsOf(line: Int, maxDepth: Int): List<Int> {
        if (maxDepth <= 0) return emptyList()
        var i = lastStartBefore(line)
        while (i >= 0 && ends[i] <= line) i = parents[i]
        if (i < 0) return emptyList()
        val chain = ArrayList<Int>()
        while (i >= 0) {
            chain.add(starts[i])
            i = parents[i]
        }
        chain.reverse()
        return if (chain.size <= maxDepth) chain else chain.take(maxDepth)
    }

    /**
     * What a sticky header pins above [line]: [ancestorsOf] without the document's outermost container.
     *
     * That bracket sits at column 0 carrying no key, so pinning it spends a row of a small band to say "this
     * body is an object" — which the row under it already says, and which nothing the reader scrolls past
     * can make ambiguous.
     */
    fun stickyAncestorsOf(line: Int, maxRows: Int): List<Int> {
        val chain = ancestorsOf(line, maxRows + 1)
        return if (chain.isEmpty()) chain else chain.drop(1)
    }

    /** Index of the last region opening strictly above [line], or -1. [starts] is sorted and has no repeats. */
    private fun lastStartBefore(line: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (starts[mid] < line) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    companion object {
        val Empty = ScopeNesting(IntArray(0), IntArray(0), IntArray(0))

        fun from(regions: Map<Int, Fold>): ScopeNesting {
            if (regions.isEmpty()) return Empty
            val starts = regions.keys.toIntArray().also { it.sort() }
            val ends = IntArray(starts.size) { regions.getValue(starts[it]).endLine }
            val parents = IntArray(starts.size)
            // The blocks still open at this point in the sweep, innermost last. A block whose close is on or
            // above the next opener is a sibling of it, not its parent: sharing a line means the bracket that
            // closed came first (a block that opened before it closed would have been popped by that same
            // bracket), so `<=` is what separates `], [` from real nesting.
            val open = ArrayList<Int>()
            for (i in starts.indices) {
                while (open.isNotEmpty() && ends[open.last()] <= starts[i]) open.removeAt(open.lastIndex)
                parents[i] = open.lastOrNull() ?: -1
                open.add(i)
            }
            return ScopeNesting(starts, ends, parents)
        }
    }
}
