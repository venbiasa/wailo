package com.venbiasa.wailo.shared.format

/** What happened to one aligned row of a side-by-side diff. */
internal enum class DiffOp { Same, Added, Removed, Changed }

/**
 * One row of a side-by-side diff, already aligned: both sides of a [DiffOp.Changed] row sit together,
 * and the side that has nothing carries a null (the filler row that keeps the two columns lined up).
 *
 * Line numbers are 1-based and belong to each side's own document, so they skip over that side's fillers
 * exactly the way a diff viewer's gutters do.
 */
internal data class DiffRow(
    val op: DiffOp,
    val left: String?,
    val right: String?,
    val leftNumber: Int?,
    val rightNumber: Int?,
)

/**
 * Past this many edits the two texts are treated as having nothing to align, and the core is reported as one
 * wholesale replacement.
 *
 * The bound is on the *edit distance*, not on the size of the inputs, because size is not what makes an
 * alignment hopeless. Two captures of the same endpoint run to thousands of identical lines around a handful
 * of changed ones: the largest inputs are routinely the easiest and cheapest to align, and a size-based cap
 * rejects exactly those — reporting a 4000-line body as 4000 differences because two of its fields moved.
 *
 * Myers' algorithm costs O((n+m)·D), so bounding D bounds the work directly. What actually forces a bound is
 * the recorded trace — `(D+1)²` ints, so ~67 MB here — allocated transiently for one diff and released with
 * it. The number below is that memory budget rather than a guess about payloads: two bodies further apart
 * than this share so little that "replaced" is the honest answer anyway. If it ever needs to go higher, the
 * fix is Myers' linear-space refinement (recursive middle snake), which drops the trace entirely, not a
 * larger number here.
 */
private const val MAX_EDIT_DISTANCE = 4_096

/**
 * The line's identity for alignment purposes, with a structural trailing comma removed.
 *
 * A pretty-printed JSON line carries the comma that *follows* it in the document, so whether a property
 * ends in one is decided by whether anything comes after it in its object — not by the property itself.
 * Compared literally, adding a key to an object therefore reports its previous sibling as changed too:
 * `"b": 2` against `"b": 2,` are different strings for the same unchanged pair. Worse, one such mismatch
 * can leave the LCS with no common line to anchor on at all, at which case the whole block pairs up
 * index-wise and every property in it reads as changed.
 *
 * A line may only end in a comma structurally: [canonicalJson] escapes control characters, so no string
 * literal ever spans or ends a line, and the last character is punctuation whenever it is `,`.
 */
internal fun jsonLineKey(line: String): String = if (line.endsWith(",")) line.dropLast(1) else line

/**
 * Aligns [left] and [right] into side-by-side rows.
 *
 * [key] reduces a line to what its identity should be for matching; two lines with equal keys are aligned
 * and reported as [DiffOp.Same] even when their text differs, and each side keeps its own text for display.
 * It defaults to the line itself — pass [jsonLineKey] for pretty-printed JSON, where a trailing comma is a
 * fact about the line's neighbours rather than about the line.
 *
 * Identical head and tail lines are matched off before the alignment runs, which costs nothing and spares
 * the shared envelope two captures of one endpoint almost always have.
 *
 * A run of removals immediately followed by a run of additions is paired up index-wise into [DiffOp.Changed]
 * rows rather than being shown as a block of deletes above a block of inserts — that pairing is what lets
 * the caller highlight the differing span *within* a line.
 */
internal fun diffLines(
    left: List<String>,
    right: List<String>,
    key: (String) -> String = { it },
): List<DiffRow> {
    // Keyed once each, not per comparison: the LCS table below reads every pair, so mapping inside it would
    // run the key function n*m times over the same lines.
    val leftKeys = left.map(key)
    val rightKeys = right.map(key)

    var head = 0
    val maxHead = minOf(left.size, right.size)
    while (head < maxHead && leftKeys[head] == rightKeys[head]) head++

    var tail = 0
    while (tail < maxHead - head && leftKeys[left.size - 1 - tail] == rightKeys[right.size - 1 - tail]) tail++

    val rows = ArrayList<DiffRow>(maxOf(left.size, right.size))
    for (i in 0 until head) {
        rows.add(DiffRow(DiffOp.Same, left[i], right[i], i + 1, i + 1))
    }

    rows.addAll(
        alignCore(
            left.subList(head, left.size - tail),
            right.subList(head, right.size - tail),
            leftKeys.subList(head, left.size - tail),
            rightKeys.subList(head, right.size - tail),
            head,
        ),
    )

    for (i in 0 until tail) {
        val l = left.size - tail + i
        val r = right.size - tail + i
        rows.add(DiffRow(DiffOp.Same, left[l], right[r], l + 1, r + 1))
    }
    return rows
}

/**
 * The differing middle, aligned. [offset] is how many identical lines were trimmed off the head, so the
 * emitted line numbers stay those of the whole document.
 */
private fun alignCore(
    left: List<String>,
    right: List<String>,
    leftKeys: List<String>,
    rightKeys: List<String>,
    offset: Int,
): List<DiffRow> {
    if (left.isEmpty() && right.isEmpty()) return emptyList()
    val n = left.size
    val m = right.size
    if (n == 0 || m == 0) return pairUp(left, right, offset, offset)
    val matches = commonPairs(leftKeys, rightKeys) ?: return pairUp(left, right, offset, offset)

    val rows = ArrayList<DiffRow>(maxOf(n, m))
    // Removals and additions are buffered until the run ends, so a delete-block followed by an insert-block
    // can be emitted as paired Changed rows instead of two separate blocks.
    val pendingLeft = ArrayList<String>()
    val pendingRight = ArrayList<String>()
    var leftAt = 0
    var rightAt = 0

    fun flush() {
        if (pendingLeft.isEmpty() && pendingRight.isEmpty()) return
        rows.addAll(
            pairUp(
                pendingLeft,
                pendingRight,
                offset + leftAt - pendingLeft.size,
                offset + rightAt - pendingRight.size,
            ),
        )
        pendingLeft.clear()
        pendingRight.clear()
    }

    for (matchIndex in matches.indices) {
        val leftMatch = matches[matchIndex].first
        val rightMatch = matches[matchIndex].second
        while (leftAt < leftMatch) {
            pendingLeft.add(left[leftAt])
            leftAt++
        }
        while (rightAt < rightMatch) {
            pendingRight.add(right[rightAt])
            rightAt++
        }
        flush()
        rows.add(DiffRow(DiffOp.Same, left[leftAt], right[rightAt], offset + leftAt + 1, offset + rightAt + 1))
        leftAt++
        rightAt++
    }
    while (leftAt < n) {
        pendingLeft.add(left[leftAt])
        leftAt++
    }
    while (rightAt < m) {
        pendingRight.add(right[rightAt])
        rightAt++
    }
    flush()
    return rows
}

/**
 * The lines the two sides have in common, as `(leftIndex, rightIndex)` pairs in ascending order — a longest
 * common subsequence, found by Myers' algorithm.
 *
 * Returns null when the two sides are further apart than [MAX_EDIT_DISTANCE], which is the caller's signal to
 * give up on aligning and report a replacement instead.
 *
 * Myers walks the edit graph by increasing edit distance, so it stops as soon as it has an answer: for the
 * common case of two captures of one endpoint it settles in a handful of steps regardless of how long the
 * bodies are. The alternative, filling an n×m table, costs the same on near-identical input as on unrelated
 * input, and it is the *near-identical* case that has to stay affordable.
 */
private fun commonPairs(leftKeys: List<String>, rightKeys: List<String>): List<Pair<Int, Int>>? {
    val n = leftKeys.size
    val m = rightKeys.size
    val max = n + m
    val offset = max
    // Furthest x reached on each diagonal k = x - y, indexed by k + offset.
    val furthest = IntArray(2 * max + 1)
    // One snapshot per step, holding only the diagonals that step can reach: k in -d..d, so 2d+1 entries
    // based at -d. Keeping the slice rather than the whole array is what holds the trace to ~D² ints.
    val trace = ArrayList<IntArray>()

    for (d in 0..max) {
        if (d > MAX_EDIT_DISTANCE) return null
        trace.add(furthest.copyOfRange(offset - d, offset + d + 1))
        var k = -d
        while (k <= d) {
            val at = k + offset
            // Extend whichever neighbouring diagonal reached further: down (insert) or right (delete).
            var x = if (k == -d || (k != d && furthest[at - 1] < furthest[at + 1])) {
                furthest[at + 1]
            } else {
                furthest[at - 1] + 1
            }
            var y = x - k
            while (x < n && y < m && leftKeys[x] == rightKeys[y]) {
                x++
                y++
            }
            furthest[at] = x
            if (x >= n && y >= m) return retrace(trace, d, n, m)
            k += 2
        }
    }
    return null
}

/**
 * Walks the recorded trace back from the end of both inputs, collecting the diagonal runs — the matched
 * lines — that each step ended with.
 */
private fun retrace(trace: List<IntArray>, distance: Int, n: Int, m: Int): List<Pair<Int, Int>> {
    val matches = ArrayList<Pair<Int, Int>>()
    var x = n
    var y = m
    for (d in distance downTo 1) {
        // Snapshot taken before step d, based at diagonal -d: the entry for k sits at index k + d.
        val previous = trace[d]
        val k = x - y
        val fromK = if (k == -d || (k != d && previous[k - 1 + d] < previous[k + 1 + d])) k + 1 else k - 1
        val previousX = previous[fromK + d]
        val previousY = previousX - fromK
        while (x > previousX && y > previousY) {
            x--
            y--
            matches.add(x to y)
        }
        x = previousX
        y = previousY
    }
    while (x > 0 && y > 0) {
        x--
        y--
        matches.add(x to y)
    }
    matches.reverse()
    return matches
}

/** How far ahead a line will look for a better partner than the one sitting opposite it. */
private const val PAIR_LOOKAHEAD = 64

/**
 * Past this many lines in a run, partners are not searched for at all. A run this long only arises from the
 * wholesale-replacement fallback, where the two sides are further apart than [MAX_EDIT_DISTANCE] and the
 * pairing is guesswork anyway — so the search would cost more than the answer is worth.
 */
private const val MAX_PAIR_SEARCH_LINES = 2_000

/**
 * Pairs a run of removed lines against a run of added ones into [DiffOp.Changed] rows, leaving whatever
 * has no partner as one-sided rows against fillers.
 *
 * The pairing is by *correspondence*, not by position, which matters as soon as the two runs are unequal.
 * Emptying a JSON array drops a block of lines from one side, and any unrelated change below it falls into
 * the same run — zipped from the top, an array element's `{` gets reported as having "changed into" a
 * property several lines further down, and the real change opposite it is reported as a plain deletion.
 * The whole tail of the run reads as modified when one property moved.
 *
 * So a pair is only formed between lines that plausibly *are* each other, and a line with no plausible
 * partner opposite steps aside as a one-sided row rather than dragging the rest of the run out of step.
 * Where neither side has a partner anywhere in reach, the two are paired anyway — that is the ordinary
 * "these lines were replaced" case, and it is what gives the caller a left and a right to highlight within.
 */
private fun pairUp(left: List<String>, right: List<String>, leftStart: Int, rightStart: Int): List<DiffRow> {
    val rows = ArrayList<DiffRow>(maxOf(left.size, right.size))
    val search = left.size <= MAX_PAIR_SEARCH_LINES && right.size <= MAX_PAIR_SEARCH_LINES

    // Measured from [opposite] — the index this line already failed to pair with — so the search starts past
    // it and a hit is always at least 1 ahead. Counting from the first index searched instead would report a
    // partner on the very next line as 0, which is indistinguishable from "these two belong together".
    fun partnerAhead(fixed: String, run: List<String>, opposite: Int): Int? {
        if (!search) return null
        val limit = minOf(run.size, opposite + 1 + PAIR_LOOKAHEAD)
        for (k in opposite + 1 until limit) if (correspond(fixed, run[k])) return k - opposite
        return null
    }

    var i = 0
    var j = 0
    while (i < left.size && j < right.size) {
        if (correspond(left[i], right[j])) {
            rows.add(DiffRow(DiffOp.Changed, left[i], right[j], leftStart + i + 1, rightStart + j + 1))
            i++
            j++
            continue
        }
        val aheadOnRight = partnerAhead(left[i], right, j)
        val aheadOnLeft = partnerAhead(right[j], left, i)
        when {
            // Whichever side's partner is nearer is the side that has extra lines here; the other
            // side's line waits where it is rather than being spent on a pair it does not belong in.
            aheadOnLeft != null && (aheadOnRight == null || aheadOnLeft <= aheadOnRight) -> {
                rows.add(DiffRow(DiffOp.Removed, left[i], null, leftStart + i + 1, null))
                i++
            }
            aheadOnRight != null -> {
                rows.add(DiffRow(DiffOp.Added, null, right[j], null, rightStart + j + 1))
                j++
            }
            else -> {
                rows.add(DiffRow(DiffOp.Changed, left[i], right[j], leftStart + i + 1, rightStart + j + 1))
                i++
                j++
            }
        }
    }
    while (i < left.size) {
        rows.add(DiffRow(DiffOp.Removed, left[i], null, leftStart + i + 1, null))
        i++
    }
    while (j < right.size) {
        rows.add(DiffRow(DiffOp.Added, null, right[j], null, rightStart + j + 1))
        j++
    }
    return rows
}

/** Scanned from each end at most this far when judging correspondence: enough to tell two lines apart, and
 * a bound a minified line that never pretty-printed cannot blow up. */
private const val CORRESPOND_SCAN = 256

/** Below this share of shared text, two lines are treated as unrelated rather than as one line modified. */
private const val CORRESPOND_THRESHOLD = 0.35

/**
 * Whether two lines are plausibly the same line, changed.
 *
 * Indentation has to match first, which in structured text means the same nesting depth — that alone
 * separates an array element's brace from a property of the object containing it, the confusion that
 * scrambles a diff when an array empties. Beyond that it is how much of the two lines is shared at the
 * ends, which is where a key lives: `"page": 1,` and `"page": 2,` share almost everything, while `],` and
 * `"page": 2,` share an indent and a comma.
 */
private fun correspond(a: String, b: String): Boolean {
    if (indentWidth(a) != indentWidth(b)) return false
    val shortest = minOf(a.length, b.length)
    if (shortest == 0) return a.length == b.length

    var prefix = 0
    val prefixLimit = minOf(shortest, CORRESPOND_SCAN)
    while (prefix < prefixLimit && a[prefix] == b[prefix]) prefix++

    var suffix = 0
    val suffixLimit = minOf(shortest - prefix, CORRESPOND_SCAN)
    while (suffix < suffixLimit && a[a.length - 1 - suffix] == b[b.length - 1 - suffix]) suffix++

    // Long lines are measured against the scan window rather than their true length, so two minified blobs
    // that agree for a few hundred characters still read as one line modified instead of a replacement.
    val span = maxOf(a.length, b.length).coerceAtMost(2 * CORRESPOND_SCAN)
    return (prefix + suffix).toDouble() / span >= CORRESPOND_THRESHOLD
}

private fun indentWidth(line: String): Int {
    var i = 0
    while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
    return i
}

/**
 * The character range on each side of a [DiffOp.Changed] pair that actually differs, or null for a side
 * with nothing to mark.
 *
 * Found by trimming the shared prefix and suffix, then widening each end out to a token boundary — so
 * `"total": 1200` against `"total": 1900` marks `1200`/`1900` rather than the two digits that technically
 * differ, which is what makes the highlight readable at a glance.
 */
internal fun inlineDiffRange(left: String, right: String): Pair<IntRange?, IntRange?> {
    if (left == right) return null to null

    val shortest = minOf(left.length, right.length)
    var start = 0
    while (start < shortest && left[start] == right[start]) start++

    var common = 0
    while (common < shortest - start && left[left.length - 1 - common] == right[right.length - 1 - common]) common++
    var leftEnd = left.length - common
    var rightEnd = right.length - common

    // Widen backwards over a token the prefix cut through, then forwards over one the suffix did. Both sides
    // move together: the prefix is shared, so the same index is inside the same token on each.
    while (start > 0 && isTokenChar(left[start - 1]) &&
        (left.getOrNull(start)?.let(::isTokenChar) == true || right.getOrNull(start)?.let(::isTokenChar) == true)
    ) {
        start--
    }
    while (leftEnd < left.length && rightEnd < right.length &&
        isTokenChar(left[leftEnd]) && isTokenChar(right[rightEnd]) &&
        (leftEnd > start && isTokenChar(left[leftEnd - 1]) || rightEnd > start && isTokenChar(right[rightEnd - 1]))
    ) {
        leftEnd++
        rightEnd++
    }

    return (if (start < leftEnd) start until leftEnd else null) to
        (if (start < rightEnd) start until rightEnd else null)
}

private fun isTokenChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.'

/**
 * The index of the first row of each run of non-[DiffOp.Same] rows — the "hunks" the next/previous change
 * buttons step through, so a ten-line change is one stop rather than ten.
 */
internal fun changeHunkStarts(rows: List<DiffRow>): List<Int> {
    val starts = ArrayList<Int>()
    var previousWasChange = false
    rows.forEachIndexed { index, row ->
        val isChange = row.op != DiffOp.Same
        if (isChange && !previousWasChange) starts.add(index)
        previousWasChange = isChange
    }
    return starts
}
