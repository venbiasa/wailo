package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.venbiasa.wailo.shared.format.JsonToken
import com.venbiasa.wailo.shared.format.jsonHighlightSpans
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_arrow_back
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import com.venbiasa.wailo.shared.theme.LocalWailoColors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.vectorResource

// A single line longer than this isn't per-line tokenized (a huge minified blob that didn't get
// pretty-printed) — it renders as plain text so one pathological line can't stall layout (ADR-0023).
private const val MAX_HIGHLIGHT_LINE = 10_000

// Fold-region scanning is O(document); above this we skip it (a body that big is either minified onto one
// line — nothing to fold — or large enough that a full re-scan per keystroke would be felt).
private const val FOLD_MAX_CHARS = 2_000_000

// The disclosure-arrow column between the number gutter and the text.
private val FOLD_COL_WIDTH = 16.dp

// Width, in monospace cells, of the collapsed-block `⋯` chip drawn between an opener and its pulled-up close
// bracket. The chip is the only hit target that expands a fold (besides the gutter arrow), and the dots are
// centered inside it, so it doubles as the "between the parentheses" spacing.
private const val DOTS_CELLS = 3

/**
 * The Compose-native code editor (ADR-0023): a [LazyColumn] of highlighted lines over the state's
 * [EditorBuffer], so only the visible lines are laid out and a keystroke costs one line + the viewport,
 * not the whole document. Monospace makes caret/selection/hit-test math a constant char width. It fills
 * the slot it's given (its own scroll), reads all colors from the theme (light/dark), and rides the app
 * text scale via the density's font scale.
 */
@Composable
internal fun ComposeCodeEditor(
    state: CodeEditorState,
    language: CodeLanguage,
    readOnly: Boolean,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val wailo = LocalWailoColors.current
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current

    // Share the JSON body preview's exact type (Common.monoSmall = bodySmall + Monospace) so the editor and
    // the read-only viewer read as one surface. letterSpacing is forced to 0 so a caret at column N lands at
    // exactly N cell widths — tracking would make the grid drift across a long line. `.sp` already carries
    // the app text scale (via density.fontScale), so it is NOT multiplied in again here. The glyph is centered
    // inside bodySmall's taller line box so it sits mid-row and aligns with the full-height caret (otherwise
    // the 12sp glyph rides high in the 16sp line and the caret reads as off-center).
    val textStyle = monoSmall().copy(
        color = scheme.onSurface,
        letterSpacing = 0.sp,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
    val measurer = rememberTextMeasurer()
    // Measure a whole run, not a single glyph: dividing a 64-char advance recovers the true (fractional) cell
    // width, so the caret can't drift away from the text down a long line the way an int single-glyph width would.
    val cell = remember(textStyle) { measurer.measure("M".repeat(64), textStyle) }
    val charWidthPx = cell.size.width / 64f
    val lineHeightPx = cell.size.height
    val charWidthDp = with(density) { charWidthPx.toDp() }
    val lineHeightDp = with(density) { lineHeightPx.toDp() }

    val highlight = remember(scheme, wailo) {
        HighlightColors(
            key = scheme.onSurface,
            string = wailo.success,
            number = wailo.info,
            keyword = wailo.warning,
            punctuation = scheme.onSurfaceVariant,
        )
    }

    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val findFocus = remember { FocusRequester() }

    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var viewportWidthPx by remember { mutableStateOf(0) }

    // Read as snapshot dependencies so the whole editor recomposes on edits (the LazyColumn still only
    // measures visible rows). The gutter sizes to the widest line number; content width to the longest line.
    val lineCount = state.lineCount()
    val caret = state.caret
    val gutterDigits = maxOf(2, lineCount.toString().length)
    val gutterWidthDp = charWidthDp * gutterDigits + 20.dp
    val contentWidthDp = charWidthDp * (state.maxLineLength + 1) + 8.dp

    // Folding. Regions are recomputed from the (structure of the) document on every edit — cheap under the
    // size cap, disabled above it — while `foldedStarts` (the opener lines the user collapsed) survives edits
    // and is pruned to whatever is still a real region. `hidden` is the set of folded-away line indices;
    // when it's non-empty the LazyColumn walks `visibleLines` instead of raw indices, and vertical motion
    // skips over the gaps. No folds → both stay null/empty so the huge-document path allocates nothing extra.
    val foldRegions = remember(state.version) { computeFoldRegions(state) }
    val foldedStarts = remember(state) { mutableStateListOf<Int>() }
    val activeFolds = foldedStarts.filter { foldRegions.containsKey(it) }
    val hidden = remember(foldRegions, activeFolds) {
        if (activeFolds.isEmpty()) emptySet() else buildSet {
            for (start in activeFolds) {
                val end = foldRegions[start]?.endLine ?: continue
                for (line in start + 1..end) add(line)
            }
        }
    }
    val visibleLines = remember(state.version, hidden) {
        if (hidden.isEmpty()) null
        else (0 until lineCount).filter { it !in hidden }
    }

    // A solid caret while typing that then blinks; reset to solid on every caret/edit so motion never hides it.
    var caretOn by remember { mutableStateOf(true) }
    LaunchedEffect(caret, state.version, readOnly) {
        if (readOnly) return@LaunchedEffect
        caretOn = true
        while (true) {
            kotlinx.coroutines.delay(530)
            caretOn = !caretOn
        }
    }
    // The caret belongs to whoever has focus; when the user clicks away to another field the editor keeps its
    // caret *position* but must stop drawing it, so two fields don't look focused at once.
    var focused by remember { mutableStateOf(false) }

    // Keep the caret line composed (so its row and this frame's edits stay live) and on-screen both ways.
    // With folds active the list index is the caret's position in `visibleLines`, not its raw line number.
    LaunchedEffect(caret, viewportWidthPx, visibleLines) {
        val targetIndex = if (visibleLines == null) {
            caret.line
        } else {
            visibleLines.indexOf(caret.line).let { if (it >= 0) it else visibleLines.indexOfLast { l -> l <= caret.line } }
        }.coerceAtLeast(0)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
            listState.scrollToItem(targetIndex)
        }
        val gutterPx = charWidthPx * gutterDigits + with(density) { 20.dp.toPx() + FOLD_COL_WIDTH.toPx() }
        val contentViewport = (viewportWidthPx - gutterPx).coerceAtLeast(1f)
        val caretX = caret.col * charWidthPx
        val target = when {
            caretX < hScroll.value -> caretX
            caretX > hScroll.value + contentViewport - charWidthPx -> caretX - contentViewport + charWidthPx
            else -> null
        }
        target?.let { hScroll.scrollTo(it.roundToInt().coerceIn(0, hScroll.maxValue)) }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // The selection to actually copy/cut. When it ends right at a collapsed opener's fold point (that line's
    // end, where `{`/`[` sits), stretch the end down to the matching close so the copy carries the whole
    // `{ ⋯ }` body the user sees as one unit — not just the visible opener line (the folded-away lines are
    // hidden, but they're still in the buffer). A selection that runs past the fold already spans it.
    fun effectiveSelection(): Pair<TextPos, TextPos>? {
        val sel = state.selectionRange() ?: return null
        var end = sel.second
        val fold = foldRegions[end.line]
        if (fold != null && end.line in foldedStarts && end.col == state.buffer.lineLength(end.line)) {
            end = TextPos(fold.endLine, state.buffer.lineLength(fold.endLine))
        }
        return sel.first to end
    }

    // Grow the live selection to its fold-aware span before an edit replaces it, so deleting/typing/pasting
    // over a block-selected `{ ⋯ }` affects the whole hidden body — not just the opener line, which would
    // strand the rest and leave the caret sitting after a lone bracket. No-op when nothing is folded in range.
    fun expandSelectionOverFold() {
        val range = effectiveSelection() ?: return
        if (range != state.selectionRange()) state.setSelection(range.first, range.second)
    }

    fun copySelection() {
        val range = effectiveSelection() ?: return
        val text = state.buffer.textIn(range.first, range.second)
        if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
    }

    fun cutSelection() {
        val range = effectiveSelection() ?: return
        val text = state.buffer.textIn(range.first, range.second)
        if (text.isEmpty()) return
        clipboard.setText(AnnotatedString(text))
        if (!readOnly) {
            expandSelectionOverFold()
            state.deleteSelection()
        }
    }

    fun pasteClipboard() {
        if (readOnly) return
        val text = clipboard.getText()?.text ?: return
        if (text.isNotEmpty()) {
            expandSelectionOverFold()
            state.insert(text)
        }
    }

    fun runFind(forward: Boolean) {
        if (findQuery.isEmpty()) return
        val from = if (forward) state.selectionRange()?.second ?: state.caret
        else state.selectionRange()?.first ?: state.caret
        val match = state.find(findQuery, from, forward) ?: return
        // Unfold any collapsed region hiding the hit, so the selection isn't stranded off-screen.
        if (match.first.line in hidden) {
            foldedStarts.removeAll { s -> foldRegions[s]?.let { match.first.line in (s + 1)..it.endLine } == true }
        }
        state.setSelection(match.first, match.second)
        focusRequester.requestFocus()
    }

    fun typedChar(cp: Int) {
        val base = codePointToString(cp)
        if (state.hasSelection()) {
            expandSelectionOverFold()
            state.insert(base)
            return
        }
        val ch = if (cp <= 0xFFFF) cp.toChar() else ' '
        val close = when (ch) { '(' -> ')'; '[' -> ']'; '{' -> '}'; '"' -> '"'; else -> null }
        if (close != null) {
            state.insert("$ch$close")
            state.moveCaret(TextPos(state.caret.line, state.caret.col - 1), extend = false)
        } else {
            state.insert(base)
        }
    }

    fun newlineWithIndent() {
        val c = state.caret
        val line = state.lineAt(c.line)
        val indent = line.take(c.col).takeWhile { it == ' ' || it == '\t' }
        val prev = line.getOrNull(c.col - 1)
        val extra = if (prev == '{' || prev == '[') "  " else ""
        state.insert("\n" + indent + extra)
    }

    // First visible (non-folded-away) line at or after [from] / at or before [from], or null past the ends.
    fun nextVisibleLine(from: Int): Int? {
        var n = from
        while (n <= lineCount - 1) { if (n !in hidden) return n; n++ }
        return null
    }

    fun prevVisibleLine(from: Int): Int? {
        var n = from
        while (n >= 0) { if (n !in hidden) return n; n-- }
        return null
    }

    fun horizontalMove(right: Boolean, shift: Boolean) {
        val sel = state.selectionRange()
        if (!shift && sel != null) {
            state.moveCaret(if (right) sel.second else sel.first, extend = false)
            return
        }
        val c = state.caret
        // Wrapping across a line boundary steps to the next/prev *visible* line, so a collapsed block is
        // crossed in one move (and the caret never lands on a hidden line) — that's what lets Shift+Arrow
        // select the whole `{ ⋯ }` unit instead of stalling char-by-char through its hidden body.
        val target = if (right) when {
            c.col < state.buffer.lineLength(c.line) -> TextPos(c.line, c.col + 1)
            else -> nextVisibleLine(c.line + 1)?.let { TextPos(it, 0) } ?: c
        } else when {
            c.col > 0 -> TextPos(c.line, c.col - 1)
            else -> prevVisibleLine(c.line - 1)?.let { TextPos(it, state.buffer.lineLength(it)) } ?: c
        }
        state.moveCaret(target, shift)
    }

    fun verticalMove(delta: Int, shift: Boolean) {
        val c = state.caret
        val dir = if (delta >= 0) 1 else -1
        var target = c.line
        var remaining = abs(delta)
        // Step one visible line at a time so a collapsed block counts as a single row, not its hidden span.
        while (remaining > 0) {
            var next = target + dir
            while (next in hidden) next += dir
            if (next < 0 || next > lineCount - 1) break
            target = next
            remaining--
        }
        if (target == c.line) return
        val col = minOf(state.desiredCol, state.buffer.lineLength(target))
        state.moveCaret(TextPos(target, col), shift, keepDesired = true)
    }

    // Collapse/expand the region opening on [startLine]. Folding pulls the caret out of the span it hides
    // (onto the opener) so it never strands on an invisible line.
    fun toggleFold(startLine: Int) {
        if (foldedStarts.remove(startLine)) return
        foldedStarts.add(startLine)
        val end = foldRegions[startLine]?.endLine ?: return
        if (state.caret.line in (startLine + 1)..end) {
            state.moveCaret(TextPos(startLine, state.buffer.lineLength(startLine)), extend = false)
        }
    }

    fun homeKey(shift: Boolean, toDocStart: Boolean) {
        if (toDocStart) {
            state.moveCaret(TextPos(0, 0), shift)
            return
        }
        val c = state.caret
        val line = state.lineAt(c.line)
        val firstNonWs = line.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) line.length else it }
        state.moveCaret(TextPos(c.line, if (c.col == firstNonWs) 0 else firstNonWs), shift)
    }

    fun endKey(shift: Boolean, toDocEnd: Boolean) {
        if (toDocEnd) {
            state.moveCaret(state.buffer.endPos(), shift)
            return
        }
        val c = state.caret
        state.moveCaret(TextPos(c.line, state.buffer.lineLength(c.line)), shift)
    }

    // Translates a key press into an edit/navigation. Runs before the focusable consumes the event, and
    // returns true when handled. `cmd` treats Meta (macOS) and Ctrl (elsewhere) alike for shortcuts.
    fun onKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val cmd = event.isMetaPressed || event.isCtrlPressed
        val shift = event.isShiftPressed
        when {
            cmd && event.key == Key.C -> copySelection()
            cmd && event.key == Key.X -> cutSelection()
            cmd && event.key == Key.V -> pasteClipboard()
            cmd && event.key == Key.A -> state.selectAll()
            cmd && event.key == Key.Z && !shift -> if (!readOnly) state.undo()
            cmd && (event.key == Key.Y || (shift && event.key == Key.Z)) -> if (!readOnly) state.redo()
            cmd && event.key == Key.F -> {
                findOpen = true
                findFocus.requestFocus()
            }
            event.key == Key.Escape -> {
                if (!findOpen) return false
                findOpen = false
                focusRequester.requestFocus()
            }
            event.key == Key.DirectionLeft -> horizontalMove(right = false, shift = shift)
            event.key == Key.DirectionRight -> horizontalMove(right = true, shift = shift)
            event.key == Key.DirectionUp -> verticalMove(-1, shift)
            event.key == Key.DirectionDown -> verticalMove(1, shift)
            event.key == Key.MoveHome -> homeKey(shift, toDocStart = cmd)
            event.key == Key.MoveEnd -> endKey(shift, toDocEnd = cmd)
            event.key == Key.PageUp -> verticalMove(-visibleRowCount(listState), shift)
            event.key == Key.PageDown -> verticalMove(visibleRowCount(listState), shift)
            readOnly -> return false
            event.key == Key.Enter -> newlineWithIndent()
            event.key == Key.Backspace -> {
                expandSelectionOverFold()
                state.deleteBackward()
            }
            event.key == Key.Delete -> {
                expandSelectionOverFold()
                state.deleteForward()
            }
            event.key == Key.Tab -> state.insert("  ")
            else -> {
                // Only real printable input types. Modifier/function/dead keys report utf16CodePoint 0 or
                // 0xFFFF (CHAR_UNDEFINED) — the source of the stray `￿` when Shift was pressed alone.
                val cp = event.utf16CodePoint
                if (!cmd && cp in 0x20..0x10FFFF && cp != 0x7F && cp != 0xFFFF) typedChar(cp) else return false
            }
        }
        return true
    }

    Column(modifier.background(scheme.surface)) {
        if (findOpen) {
            FindBar(
                query = findQuery,
                onQueryChange = { findQuery = it },
                onNext = { runFind(forward = true) },
                onPrev = { runFind(forward = false) },
                onClose = {
                    findOpen = false
                    focusRequester.requestFocus()
                },
                focusRequester = findFocus,
            )
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { viewportWidthPx = it.width }
                .onFocusChanged { focused = it.isFocused }
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { onKey(it) },
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                val rowCount = visibleLines?.size ?: lineCount
                items(count = rowCount, key = { visibleLines?.get(it) ?: it }) { row ->
                    val index = visibleLines?.get(row) ?: row
                    EditorLineRow(
                        state = state,
                        index = index,
                        language = language,
                        textStyle = textStyle,
                        highlight = highlight,
                        charWidthPx = charWidthPx,
                        charWidthDp = charWidthDp,
                        lineHeightPx = lineHeightPx,
                        lineHeightDp = lineHeightDp,
                        gutterWidthDp = gutterWidthDp,
                        contentWidthDp = contentWidthDp,
                        hScroll = hScroll,
                        caretOn = caretOn && focused,
                        foldable = foldRegions.containsKey(index),
                        folded = index in foldedStarts && foldRegions.containsKey(index),
                        foldCloseChar = foldRegions[index]?.closeChar,
                        onToggleFold = {
                            focusRequester.requestFocus()
                            toggleFold(index)
                        },
                        onPlaceCaret = { pos ->
                            focusRequester.requestFocus()
                            state.moveCaret(pos, extend = false)
                        },
                        onExtendSelect = { pos ->
                            focusRequester.requestFocus()
                            state.moveCaret(pos, extend = true)
                        },
                        onDragSelect = { anchorPos, caretPos ->
                            focusRequester.requestFocus()
                            state.setSelection(anchorPos, caretPos)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorLineRow(
    state: CodeEditorState,
    index: Int,
    language: CodeLanguage,
    textStyle: TextStyle,
    highlight: HighlightColors,
    charWidthPx: Float,
    charWidthDp: Dp,
    lineHeightPx: Int,
    lineHeightDp: Dp,
    gutterWidthDp: Dp,
    contentWidthDp: Dp,
    hScroll: ScrollState,
    caretOn: Boolean,
    foldable: Boolean,
    folded: Boolean,
    foldCloseChar: Char?,
    onToggleFold: () -> Unit,
    onPlaceCaret: (TextPos) -> Unit,
    onExtendSelect: (TextPos) -> Unit,
    onDragSelect: (TextPos, TextPos) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val lineText = state.lineAt(index)
    val caret = state.caret
    val selection = state.selectionRange()
    val isCaretLine = index == caret.line
    val annotated = remember(lineText, language, highlight) { annotateLine(lineText, language, highlight) }
    // A collapsed block reads as selected once the selection reaches the opener's fold point (its line end,
    // where `{`/`[` sits): the `⋯ }` chip then highlights and Ctrl+C copies the whole hidden body (the copy
    // side of this is `effectiveSelection`). Selecting only part of the opener, short of that point, doesn't.
    val foldPoint = TextPos(index, lineText.length)
    val foldBodySelected = folded && selection != null &&
        selection.first != selection.second &&
        selection.first <= foldPoint && selection.second >= foldPoint

    Row(Modifier.fillMaxWidth().height(lineHeightDp)) {
        Box(
            Modifier.width(gutterWidthDp).fillMaxHeight()
                .background(scheme.surfaceContainer)
                .padding(end = 8.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                (index + 1).toString(),
                // Inactive numbers use onSurfaceVariant (as the JSON preview does) rather than the faint
                // `outline`, which was too low-contrast to read; the caret's line brightens to onSurface.
                style = textStyle.copy(color = if (isCaretLine) scheme.onSurface else scheme.onSurfaceVariant),
                maxLines = 1,
            )
        }
        // Fold gutter: a disclosure arrow on opener lines (down = expanded, right = collapsed). Fixed width
        // so text left-edges stay aligned whether or not a line is foldable.
        Box(
            Modifier.width(FOLD_COL_WIDTH).fillMaxHeight().background(scheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (foldable) {
                Icon(
                    vectorResource(Res.drawable.ic_arrow_drop_down),
                    contentDescription = if (folded) "Expand block" else "Collapse block",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                        .rotate(if (folded) -90f else 0f)
                        .clickable(onClick = onToggleFold),
                )
            }
        }
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .background(if (isCaretLine && selection == null) scheme.onSurface.copy(alpha = 0.05f) else Color.Transparent)
                .clipToBounds()
                .horizontalScroll(hScroll),
        ) {
            Box(
                Modifier.width(contentWidthDp).fillMaxHeight()
                    .pointerInput(index, lineText, charWidthPx, lineHeightPx, folded) {
                        // Caret moves on pointer-DOWN (not up), so a click lands instantly instead of waiting
                        // out the double-tap window `detectTapGestures` imposes. Shift+click extends the current
                        // selection to the hit; a second quick press on the same spot selects the word; moving
                        // after the press drags a selection.
                        awaitPointerEventScope {
                            var lastDownTime = 0L
                            var lastDownCol = -1
                            while (true) {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val shift = currentEvent.keyboardModifiers.isShiftPressed
                                // On a collapsed row, only a hit on the `⋯` chip (the cells right after the
                                // opener text, before the close bracket) expands — clicking the opener text or
                                // the close bracket just places a caret, as the user asked.
                                val xCells = down.position.x / charWidthPx
                                if (folded && !shift && xCells >= lineText.length && xCells < lineText.length + DOTS_CELLS) {
                                    onToggleFold()
                                    continue
                                }
                                val downCol = colAt(down.position.x, charWidthPx, lineText.length)
                                val isDoubleClick = !shift &&
                                    down.uptimeMillis - lastDownTime < viewConfiguration.doubleTapTimeoutMillis &&
                                        abs(downCol - lastDownCol) <= 1
                                lastDownTime = down.uptimeMillis
                                lastDownCol = downCol
                                // Shift+click keeps the existing anchor and stretches to the hit; a later drag
                                // continues from that same anchor rather than the press point.
                                val dragAnchor = when {
                                    shift -> {
                                        onExtendSelect(TextPos(index, downCol))
                                        state.anchor ?: TextPos(index, downCol)
                                    }
                                    isDoubleClick -> {
                                        val (s, e) = wordBoundsAt(lineText, downCol)
                                        onDragSelect(TextPos(index, s), TextPos(index, e))
                                        TextPos(index, s)
                                    }
                                    else -> {
                                        onPlaceCaret(TextPos(index, downCol))
                                        TextPos(index, downCol)
                                    }
                                }
                                drag(down.id) { change ->
                                    val line = index + floor(change.position.y / lineHeightPx).toInt()
                                    val col = (change.position.x / charWidthPx).roundToInt().coerceAtLeast(0)
                                    onDragSelect(dragAnchor, TextPos(line, col))
                                    change.consume()
                                }
                            }
                        }
                    },
            ) {
                if (selection != null && index >= selection.first.line && index <= selection.second.line) {
                    val startCol = if (index == selection.first.line) selection.first.col else 0
                    val endCol = if (index == selection.second.line) selection.second.col else lineText.length
                    // A half-cell of slack past a fully-covered line's end shows the newline is in the range.
                    val slack = if (index != selection.second.line) charWidthDp * 0.5f else 0.dp
                    Box(
                        Modifier.offset(x = charWidthDp * startCol)
                            .width(charWidthDp * (endCol - startCol).coerceAtLeast(0) + slack)
                            .fillMaxHeight()
                            .background(scheme.primary.copy(alpha = 0.28f)),
                    )
                }
                // The collapsed-block highlight covers the `⋯` chip + close bracket, so a fold that's inside a
                // selection reads as selected across its whole visible extent.
                if (foldBodySelected) {
                    Box(
                        Modifier.offset(x = charWidthDp * lineText.length)
                            .width(charWidthDp * (DOTS_CELLS + 1))
                            .fillMaxHeight()
                            .background(scheme.primary.copy(alpha = 0.28f)),
                    )
                }
                Text(annotated, style = textStyle, softWrap = false, maxLines = 1)
                // A collapsed opener already drew its own `{`/`[`; pull the matching close up onto this row with
                // three dots between them, so it reads as one `{ ⋯ }` unit. The dots are drawn (not a glyph) so
                // they sit dead-center between the brackets both ways, and the chip is the only expand target.
                if (folded) {
                    val dotColor = scheme.onSurfaceVariant
                    Row(
                        Modifier.offset(x = charWidthDp * lineText.length).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(Modifier.width(charWidthDp * DOTS_CELLS).fillMaxHeight()) {
                            val r = 1.3.dp.toPx()
                            val step = 4.dp.toPx()
                            val cy = size.height / 2f
                            val cx = size.width / 2f
                            for (dx in floatArrayOf(-step, 0f, step)) drawCircle(dotColor, r, Offset(cx + dx, cy))
                        }
                        if (foldCloseChar != null) {
                            Text(foldCloseChar.toString(), style = textStyle.copy(color = dotColor), maxLines = 1)
                        }
                    }
                }
                if (isCaretLine && caretOn) {
                    Box(
                        Modifier.offset(x = charWidthDp * caret.col)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(scheme.primary),
                    )
                }
            }
        }
    }
}

// A slim find toolbar above the editor: type to search, Enter / Shift+Enter for next / previous, Escape
// to dismiss. Single-line substring find (a JSON string can't hold a raw newline).
@Composable
private fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .background(scheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CompactOutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else when (event.key) {
                        Key.Enter -> {
                            if (event.isShiftPressed) onPrev() else onNext()
                            true
                        }
                        Key.Escape -> {
                            onClose()
                            true
                        }
                        else -> false
                    }
                },
            placeholder = "Find",
        )
        IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) {
            Icon(
                vectorResource(Res.drawable.ic_arrow_back),
                contentDescription = "Previous match",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) {
            Icon(
                vectorResource(Res.drawable.ic_arrow_back),
                contentDescription = "Next match",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).rotate(180f),
            )
        }
        CloseButton(onClose, contentDescription = "Close find")
    }
}

private fun colAt(x: Float, charWidthPx: Float, lineLength: Int): Int =
    (x / charWidthPx).roundToInt().coerceIn(0, lineLength)

// The word span `[start, end)` around [col] for double-click selection: a run of identifier chars
// (letters/digits/`_`), else the single char clicked. Clicking at end-of-word picks the char to the left.
private fun wordBoundsAt(line: String, col: Int): Pair<Int, Int> {
    if (line.isEmpty()) return 0 to 0
    val idx = (if (col < line.length) col else col - 1).coerceIn(0, line.length - 1)
    fun isWord(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'
    if (!isWord(line[idx])) return idx to idx + 1
    var start = idx
    while (start > 0 && isWord(line[start - 1])) start--
    var end = idx + 1
    while (end < line.length && isWord(line[end])) end++
    return start to end
}

private fun visibleRowCount(listState: LazyListState): Int =
    listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)

/** A foldable region: the line its matching close sits on, and that closing bracket (`}` or `]`). */
private data class Fold(val endLine: Int, val closeChar: Char)

/**
 * Maps each opener line to its [Fold] for every *multi-line* `{}`/`[]` pair — the foldable regions, keyed by
 * the line the fold arrow sits on. String-aware (brackets inside `"…"` are ignored; a `\`-escaped char is
 * skipped), so it doesn't fold on braces inside string values. When two pairs open on one line, the outermost
 * (largest close line) wins, so that line's arrow folds the whole span. Bails out empty above [FOLD_MAX_CHARS]
 * since it scans the whole document.
 */
private fun computeFoldRegions(state: CodeEditorState): Map<Int, Fold> {
    val lineCount = state.lineCount()
    val stack = ArrayList<Int>() // opener line indices, innermost last
    val regions = HashMap<Int, Fold>()
    var scanned = 0
    for (li in 0 until lineCount) {
        val line = state.lineAt(li)
        scanned += line.length + 1
        if (scanned > FOLD_MAX_CHARS) return emptyMap()
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inString) {
                when (c) {
                    '\\' -> i++ // skip the escaped char
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> stack.add(li)
                    '}', ']' -> {
                        val openLine = stack.removeLastOrNull()
                        if (openLine != null && openLine < li) {
                            val existing = regions[openLine]
                            if (existing == null || li > existing.endLine) regions[openLine] = Fold(li, c)
                        }
                    }
                }
            }
            i++
        }
    }
    return regions
}

private class HighlightColors(
    val key: Color,
    val string: Color,
    val number: Color,
    val keyword: Color,
    val punctuation: Color,
)

// Colors a JSON line from its per-line spans; plain text (or an over-long line) is returned uncolored.
private fun annotateLine(line: String, language: CodeLanguage, colors: HighlightColors): AnnotatedString {
    if (language != CodeLanguage.Json || line.isEmpty() || line.length > MAX_HIGHLIGHT_LINE) {
        return AnnotatedString(line)
    }
    val spans = jsonHighlightSpans(line)
    if (spans.isEmpty()) return AnnotatedString(line)
    return buildAnnotatedString {
        append(line)
        for (span in spans) {
            val color = when (span.token) {
                JsonToken.Key -> colors.key
                JsonToken.StringValue -> colors.string
                JsonToken.Number -> colors.number
                JsonToken.Keyword -> colors.keyword
                JsonToken.Punctuation -> colors.punctuation
            }
            addStyle(SpanStyle(color = color), span.start, span.end)
        }
    }
}

// A typed code point as a string, building a surrogate pair for astral chars (no JVM Character in common).
private fun codePointToString(cp: Int): String =
    if (cp <= 0xFFFF) {
        cp.toChar().toString()
    } else {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
