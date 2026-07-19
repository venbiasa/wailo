package com.venbiasa.wailo.shared.ui

/** A caret/selection position as a 0-based (line, column). Column is a char offset within the line. */
internal data class TextPos(val line: Int, val col: Int) : Comparable<TextPos> {
    override fun compareTo(other: TextPos): Int =
        if (line != other.line) line - other.line else col - other.col
}

/**
 * The editor's text, held as a list of lines (no trailing newline stored) rather than one immutable
 * `String`. This is the whole point of the Compose-native editor (ADR-0023): an edit splices only the
 * affected line span, so typing is O(1) amortized and a newline is a single list insert — never an O(n)
 * re-copy of the entire document. Whole-document `String`s are produced only on demand ([text]) for save
 * and validation, never per keystroke.
 *
 * The buffer always holds at least one (possibly empty) line, so `lineCount >= 1` and line 0 always exists.
 */
internal class EditorBuffer(text: String = "") {
    private val lines = ArrayList<String>()

    init {
        reset(text)
    }

    val lineCount: Int get() = lines.size

    fun line(index: Int): String = lines[index]

    fun lineLength(index: Int): Int = lines[index].length

    /** Replaces the whole document. Splitting on `\n` keeps `\r` inside lines so CRLF round-trips verbatim. */
    fun reset(text: String) {
        lines.clear()
        if (text.isEmpty()) {
            lines.add("")
        } else {
            var start = 0
            while (true) {
                val nl = text.indexOf('\n', start)
                if (nl < 0) {
                    lines.add(text.substring(start))
                    break
                }
                lines.add(text.substring(start, nl))
                start = nl + 1
            }
        }
        if (lines.isEmpty()) lines.add("")
    }

    fun text(): String = lines.joinToString("\n")

    /** The last valid caret position (end of the last line). */
    fun endPos(): TextPos = TextPos(lines.size - 1, lines.last().length)

    /** Clamps an arbitrary position onto a real (line, col) within the current text. */
    fun clamp(pos: TextPos): TextPos {
        val line = pos.line.coerceIn(0, lines.size - 1)
        return TextPos(line, pos.col.coerceIn(0, lines[line].length))
    }

    /** The text between two positions (order-independent), with `\n` between spanned lines. */
    fun textIn(a: TextPos, b: TextPos): String {
        val (s, e) = order(a, b)
        if (s.line == e.line) return lines[s.line].substring(s.col, e.col)
        val sb = StringBuilder()
        sb.append(lines[s.line].substring(s.col))
        for (l in s.line + 1 until e.line) {
            sb.append('\n').append(lines[l])
        }
        sb.append('\n').append(lines[e.line].substring(0, e.col))
        return sb.toString()
    }

    /**
     * Replaces the text in `[a, b)` (order-independent) with [insert] and returns the caret position at the
     * end of the inserted text. [insert] may contain newlines, which grow the line list; deleting across
     * lines shrinks it. Splicing the affected line span (not the whole list) is what keeps edits cheap.
     */
    fun replace(a: TextPos, b: TextPos, insert: String): TextPos {
        val (s, e) = order(clamp(a), clamp(b))
        val prefix = lines[s.line].substring(0, s.col)
        val suffix = lines[e.line].substring(e.col)
        val parts = insert.split("\n")
        if (parts.size == 1) {
            val merged = prefix + parts[0] + suffix
            if (s.line == e.line) {
                lines[s.line] = merged
            } else {
                lines.subList(s.line, e.line + 1).clear()
                lines.add(s.line, merged)
            }
            return TextPos(s.line, prefix.length + parts[0].length)
        }
        val replacement = ArrayList<String>(parts.size)
        replacement.add(prefix + parts.first())
        for (k in 1 until parts.size - 1) replacement.add(parts[k])
        replacement.add(parts.last() + suffix)
        lines.subList(s.line, e.line + 1).clear()
        lines.addAll(s.line, replacement)
        return TextPos(s.line + parts.size - 1, parts.last().length)
    }

    /** The end position after inserting [text] at [start] — the redo/undo bookkeeping needs this. */
    fun endAfterInsert(start: TextPos, text: String): TextPos {
        val nl = text.lastIndexOf('\n')
        return if (nl < 0) {
            TextPos(start.line, start.col + text.length)
        } else {
            TextPos(start.line + text.count { it == '\n' }, text.length - nl - 1)
        }
    }

    /** The length of the longest line — the editor's horizontal-scroll content width is derived from this. */
    fun maxLineLength(): Int = lines.maxOf { it.length }

    private fun order(a: TextPos, b: TextPos): Pair<TextPos, TextPos> =
        if (a <= b) a to b else b to a
}
