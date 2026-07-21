package com.venbiasa.wailo.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** The language a [CodeEditor] highlights. Deliberately small — grown as new surfaces need it. */
internal enum class CodeLanguage { Json, PlainText }

/**
 * The editor's document + caret/selection/undo, kept apart from the composable so the model is portable and
 * unit-testable (ADR-0023). The large text lives in an [EditorBuffer] (a list of lines), never in a single
 * snapshot `String`, so a keystroke splices one line rather than re-copying megabytes.
 *
 * [version] ticks on *any* change (drives recomposition of the visible rows and line-count reads);
 * [revision] ticks only on *user* edits (the debounced JSON validation observes it). The public contract —
 * [currentText]/[setText]/[touch]/[revision] — is what `RuleEditor` and its validation effect build on.
 */
@Stable
internal class CodeEditorState(initialText: String = "") {
    internal val buffer = EditorBuffer(initialText)

    var version by mutableStateOf(0)
        private set

    var revision by mutableStateOf(0)
        internal set

    var caret by mutableStateOf(TextPos(0, 0))
        internal set

    // The fixed end of a selection; null when there's just a caret. The selection is [anchor, caret],
    // order-independent, and the caret is always the moving end.
    var anchor by mutableStateOf<TextPos?>(null)
        internal set

    // The column vertical moves try to keep, so gliding up/down through short lines returns to the
    // original column (standard editor feel). Not Compose state — only read/written in event handlers.
    internal var desiredCol: Int = 0

    // High-water mark of the longest line; the horizontal-scroll content width derives from it. Kept as a
    // mark (grows, resets on setText) so it doesn't thrash the scroll range as lines shrink.
    var maxLineLength by mutableStateOf(buffer.maxLineLength())
        internal set

    private val undoStack = ArrayDeque<EditOp>()
    private val redoStack = ArrayDeque<EditOp>()

    // True while the last edit was a lone character insert, so the next one can extend the same undo group.
    private var coalescing = false

    fun lineCount(): Int {
        version // establish a snapshot dependency so line-count reads recompose on edits
        return buffer.lineCount
    }

    fun lineAt(index: Int): String {
        version
        return buffer.line(index)
    }

    fun currentText(): String = buffer.text()

    /** Replaces the whole document (Format / seed-from-capture). Not a user edit, so [revision] is untouched. */
    fun setText(text: String) {
        buffer.reset(text)
        caret = TextPos(0, 0)
        anchor = null
        desiredCol = 0
        undoStack.clear()
        redoStack.clear()
        coalescing = false
        maxLineLength = buffer.maxLineLength()
        version++
    }

    /** Nudges observers to re-read after a programmatic change (e.g. re-run validation on a seeded body). */
    fun touch() {
        revision++
    }

    // --- selection ---

    /** The normalized selection (start <= end), or null when it's empty (caret only). */
    fun selectionRange(): Pair<TextPos, TextPos>? {
        val a = anchor ?: return null
        if (a == caret) return null
        return if (a <= caret) a to caret else caret to a
    }

    fun hasSelection(): Boolean = selectionRange() != null

    fun selectedText(): String = selectionRange()?.let { buffer.textIn(it.first, it.second) } ?: ""

    fun setSelection(anchorPos: TextPos, caretPos: TextPos) {
        anchor = buffer.clamp(anchorPos)
        val c = buffer.clamp(caretPos)
        caret = c
        desiredCol = c.col
        coalescing = false
    }

    fun selectAll() = setSelection(TextPos(0, 0), buffer.endPos())

    /**
     * Moves the caret to [target]. [extend] grows the current selection (anchoring at the old caret if
     * there wasn't one); otherwise the selection collapses. [keepDesired] preserves [desiredCol] for
     * vertical motion through shorter lines.
     */
    fun moveCaret(target: TextPos, extend: Boolean, keepDesired: Boolean = false) {
        val clamped = buffer.clamp(target)
        anchor = if (extend) (anchor ?: caret) else null
        caret = clamped
        if (!keepDesired) desiredCol = clamped.col
        coalescing = false
    }

    // --- edits ---

    private fun applyEdit(from: TextPos, to: TextPos, insert: String, coalesce: Boolean) {
        val start = if (from <= to) from else to
        val end = if (from <= to) to else from
        val removed = buffer.textIn(start, end)
        val caretBefore = caret
        val newCaret = buffer.replace(start, end, insert)
        val op = EditOp(start, removed, insert, caretBefore, newCaret)

        val last = undoStack.lastOrNull()
        val canCoalesce = coalesce && coalescing && removed.isEmpty() &&
            insert.length == 1 && insert[0] != '\n' &&
            last != null && last.removed.isEmpty() && !last.inserted.contains('\n') &&
            buffer.endAfterInsert(last.start, last.inserted) == start
        if (canCoalesce) {
            undoStack[undoStack.lastIndex] = last.copy(inserted = last.inserted + insert, caretAfter = newCaret)
        } else {
            undoStack.addLast(op)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        redoStack.clear()
        coalescing = coalesce && removed.isEmpty() && insert.length == 1 && insert[0] != '\n'

        caret = newCaret
        anchor = null
        desiredCol = newCaret.col
        val editedLen = buffer.line(newCaret.line).length
        if (editedLen > maxLineLength) maxLineLength = editedLen
        version++
        revision++
    }

    /** Inserts [text], replacing any selection. Consecutive single chars fold into one undo step. */
    fun insert(text: String) {
        val sel = selectionRange()
        if (sel != null) applyEdit(sel.first, sel.second, text, coalesce = false)
        else applyEdit(caret, caret, text, coalesce = true)
    }

    fun deleteSelection(): Boolean {
        val sel = selectionRange() ?: return false
        applyEdit(sel.first, sel.second, "", coalesce = false)
        return true
    }

    fun deleteBackward() {
        if (deleteSelection()) return
        val c = caret
        val start = when {
            c.col > 0 -> TextPos(c.line, c.col - 1)
            c.line > 0 -> TextPos(c.line - 1, buffer.lineLength(c.line - 1))
            else -> return
        }
        applyEdit(start, c, "", coalesce = false)
    }

    fun deleteForward() {
        if (deleteSelection()) return
        val c = caret
        val end = when {
            c.col < buffer.lineLength(c.line) -> TextPos(c.line, c.col + 1)
            c.line < buffer.lineCount - 1 -> TextPos(c.line + 1, 0)
            else -> return
        }
        applyEdit(c, end, "", coalesce = false)
    }

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        buffer.replace(op.start, buffer.endAfterInsert(op.start, op.inserted), op.removed)
        redoStack.addLast(op)
        restoreAfterHistory(op.caretBefore)
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        buffer.replace(op.start, buffer.endAfterInsert(op.start, op.removed), op.inserted)
        undoStack.addLast(op)
        restoreAfterHistory(op.caretAfter)
    }

    private fun restoreAfterHistory(newCaret: TextPos) {
        caret = buffer.clamp(newCaret)
        anchor = null
        desiredCol = caret.col
        coalescing = false
        maxLineLength = maxOf(maxLineLength, buffer.maxLineLength())
        version++
        revision++
    }

    /**
     * Finds [query] starting from [from], wrapping once. Single-line queries only (find over a body is a
     * substring hunt, and a JSON string can't hold a raw newline). Returns the match's [start, end).
     */
    fun find(query: String, from: TextPos, forward: Boolean): Pair<TextPos, TextPos>? {
        if (query.isEmpty()) return null
        val count = buffer.lineCount
        for (offset in 0..count) {
            if (forward) {
                val line = (from.line + offset) % count
                val startCol = if (offset == 0) from.col else 0
                val idx = buffer.line(line).indexOf(query, startCol)
                if (idx >= 0) return TextPos(line, idx) to TextPos(line, idx + query.length)
            } else {
                val line = ((from.line - offset) % count + count) % count
                val hay = buffer.line(line)
                val upTo = if (offset == 0) from.col - 1 else hay.length
                if (upTo < 0) continue
                val idx = hay.lastIndexOf(query, upTo.coerceAtMost(hay.length))
                if (idx >= 0) return TextPos(line, idx) to TextPos(line, idx + query.length)
            }
        }
        return null
    }

    private data class EditOp(
        val start: TextPos,
        val removed: String,
        val inserted: String,
        val caretBefore: TextPos,
        val caretAfter: TextPos,
    )

    private companion object {
        const val MAX_UNDO = 1000
    }
}

@Composable
internal fun rememberCodeEditorState(initialText: String = ""): CodeEditorState =
    remember { CodeEditorState(initialText) }
