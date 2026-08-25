package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.venbiasa.wailo.shared.format.JsonToken
import com.venbiasa.wailo.shared.format.jsonHighlightSpans
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_arrow_back
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import com.venbiasa.wailo.shared.resources.ic_find_replace
import com.venbiasa.wailo.shared.resources.ic_keyboard_arrow_down
import com.venbiasa.wailo.shared.resources.ic_match_case
import com.venbiasa.wailo.shared.resources.ic_swap_horiz
import com.venbiasa.wailo.shared.resources.ic_wrap_text
import com.venbiasa.wailo.shared.theme.LocalWailoColors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
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

// Compose packs a layout node's width and height into a single Long, so neither can exceed ~2^18 px. A single
// very long line — a minified body seeded raw into the breakpoint editor, or a huge JSON string value — made
// the content wider than that and crashed the whole layout pass ("Can't represent a width of N and height of
// 0"). Widths derived from line length are capped here. Soft wrap (the default) reflows long lines so the cap
// is never reached; with wrap off it only ever bounds the pathological case (the far tail of such a line just
// isn't horizontally reachable) and stays far past any real viewport, so normal scrolling is untouched.
private const val MAX_CONTENT_WIDTH_PX = 200_000f

// How many parent lines the sticky header may pin at once — past this the band costs more viewport than the
// context is worth. Which end of the chain survives the cut is `ScopeNesting.ancestorsOf`'s call, and it is
// the one that keeps the band from flickering rather than the one that reads best on a single frame.
private const val MAX_STICKY_ROWS = 5

/**
 * What one pane of a side-by-side diff needs the editor to do differently — and nothing else, so a plain
 * editor passes null and behaves exactly as it always has.
 *
 * The diff is *this* editor, not a lookalike: an earlier compare view hand-rolled its own two-column
 * renderer and had to reimplement wrapping, selection, folding and find, none of which matched. Everything
 * here is instead a value the two panes agree on, so the pair stays in step while each half remains an
 * ordinary editor over its own document.
 *
 * [listState] and [hScroll] are shared with the other pane, which is what locks the two columns together —
 * one scroll position, so they cannot drift. [wrap] is shared for the same reason, and its toggle moves to
 * the diff's own toolbar rather than floating in each pane.
 *
 * Alignment is carried by *filler* lines: the row grid is the diff's, so a row may exist on one side only.
 * [lineNumber] therefore supplies the gutter number — a padded document's row index is not its source line
 * number — and returns null on a filler, whose gutter stays blank. [minRows] is the height the opposite
 * pane's line at the same row: the editor wraps it with its own column count — both panes are the same width,
 * so the count matches — and takes the taller of the two, which is what lets a diff wrap at all. Without it a
 * line that reflows to three rows on one side and one on the other pushes everything below it out of step.
 * [otherMaxLength] serves the same purpose off wrap, where the shared [hScroll] has one travel for both: the
 * panes size their content to the *pair's* longest line, so the wider side can still be scrolled to its end.
 *
 * Folding is split in two because the two halves genuinely differ. [foldSpans] is the pair's merged answer
 * to *what a collapse hides*, running to whichever side closes later — hiding a different number of rows in
 * each pane would slide the columns apart, which is the one thing the row grid exists to prevent. [foldArrows]
 * is this side's own openers (mapped to its closing bracket), so the gutter offers a control only where this
 * document really opens a block and the `⋯ }` chip is only drawn over a real bracket. [foldedRows] is shared,
 * since one arrow folds both columns.
 */
internal class CodeEditorDecor(
    val listState: LazyListState,
    val hScroll: ScrollState,
    val wrap: Boolean,
    val lineNumber: (Int) -> Int?,
    val otherLength: (Int) -> Int,
    val otherMaxLength: Int,
    val rowTint: (Int) -> Color?,
    val spanRange: (Int) -> IntRange?,
    val spanTint: Color,
    // Both panes scroll as one, so only the outer one draws the thumb — a second would land mid-window, on
    // the seam between the columns, reporting a position its neighbour already shows.
    val verticalScrollbar: Boolean,
    val foldSpans: Map<Int, Fold>,
    val foldArrows: Map<Int, Char>,
    val foldedRows: Set<Int>,
    val onToggleFold: (Int) -> Unit,
)

/**
 * The code editor (ADR-0023): a [LazyColumn] of highlighted lines over the state's [EditorBuffer], so only
 * the visible lines are laid out and a keystroke costs one line + the viewport, not the whole document.
 * Monospace makes caret/selection/hit-test math a constant char width. Highlighted for [language] and
 * editable unless [readOnly]; it fills the slot it's given (its own scroll), reads all colors from the theme
 * (light/dark), and rides the app text scale via the density's font scale. Pure `commonMain` (no platform
 * types in the signature) since the Swing editor was sunset (ADR-0024).
 *
 * [decor] is null for every ordinary use; a side-by-side diff passes one to share scroll, wrap and folding
 * with its other pane and to tint the rows that differ. See [CodeEditorDecor].
 */
@Composable
internal fun CodeEditor(
    state: CodeEditorState,
    language: CodeLanguage,
    readOnly: Boolean,
    modifier: Modifier,
    decor: CodeEditorDecor? = null,
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

    val ownListState = rememberLazyListState()
    val ownHScroll = rememberScrollState()
    val listState = decor?.listState ?: ownListState
    val hScroll = decor?.hScroll ?: ownHScroll
    val focusRequester = remember { FocusRequester() }

    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findReplacement by remember { mutableStateOf("") }
    // The replace row is the rarer half of the widget, so it starts collapsed behind the disclosure arrow.
    var replaceOpen by remember { mutableStateOf(false) }
    // Case-insensitive by default (the editor norm — a body is hunted by shape, not by case); the Aa chip flips it.
    var matchCase by remember { mutableStateOf(false) }
    // Bumped whenever the query field should take focus. The request has to be made from an effect *inside*
    // the bar — issuing it here, from the Cmd+F handler, aims at a FocusRequester whose modifier node isn't
    // attached yet (the bar hasn't composed), which silently does nothing. Keying on a counter rather than
    // `findOpen` is what makes a second Cmd+F re-focus a bar that's already open.
    var findFocusRequests by remember { mutableStateOf(0) }
    // Bumped by every find that landed on a match, so a diff pane can put the hit on screen even though the
    // query field — not the pane — holds focus. See the reveal effect below.
    var findReveals by remember { mutableStateOf(0) }
    var viewportWidthPx by remember { mutableStateOf(0) }
    var viewportHeightPx by remember { mutableStateOf(0) }
    // Soft wrap on by default: long lines reflow onto extra visual rows at the viewport edge instead of
    // scrolling sideways. Held per editor instance (not persisted) and flipped by the corner toggle — except
    // in a diff, where both panes must wrap identically or the columns drift, so the pair decides.
    var ownWrap by remember { mutableStateOf(true) }
    val wrap = decor?.wrap ?: ownWrap

    // Read as snapshot dependencies so the whole editor recomposes on edits (the LazyColumn still only
    // measures visible rows). The gutter sizes to the widest line number; content width to the longest line.
    val lineCount = state.lineCount()
    val caret = state.caret
    val gutterDigits = maxOf(2, lineCount.toString().length)
    val gutterWidthDp = charWidthDp * gutterDigits + 20.dp
    val widestLine = maxOf(state.maxLineLength, decor?.otherMaxLength ?: 0)
    val contentWidthDp = (charWidthDp * (widestLine + 1) + 8.dp)
        .coerceAtMost(with(density) { MAX_CONTENT_WIDTH_PX.toDp() })

    // The cell count that fits after the gutter + fold column (leaving room for the overlay scrollbar) is
    // where a wrapped line breaks. Null means "don't wrap" — wrap off, or the viewport not measured yet —
    // and the whole editor then falls back to its original "one physical line = one fixed-height row" grid.
    val gutterAndFoldPx = charWidthPx * gutterDigits + with(density) { 20.dp.toPx() + FOLD_COL_WIDTH.toPx() }
    val scrollbarReservePx = with(density) { 14.dp.toPx() }
    val contentViewportPx = viewportWidthPx - gutterAndFoldPx - scrollbarReservePx
    val wrapCols: Int? = if (wrap && contentViewportPx >= charWidthPx) wrapColumns(contentViewportPx, charWidthPx) else null

    // Folding. Regions are recomputed from the (structure of the) document on every edit — cheap under the
    // size cap, disabled above it — while `foldedStarts` (the opener lines the user collapsed) survives edits
    // and is pruned to whatever is still a real region. `hidden` is the set of folded-away line indices;
    // when it's non-empty the LazyColumn walks `visibleLines` instead of raw indices, and vertical motion
    // skips over the gaps. No folds → both stay null/empty so the huge-document path allocates nothing extra.
    //
    // `state` has to be part of every key that uses `version`, because a version counts edits *within* one
    // document. Callers that re-seed by swapping in a new CodeEditorState — the body preview stepping
    // through rows, the breakpoint editor switching paused traffic — hand over a fresh instance sitting back
    // at version 0, so keying on the version alone would keep the previous body's arrows (and its line
    // indices) over the new text until something finally bumped it.
    val ownFoldRegions = remember(state, state.version, decor != null) {
        if (decor == null) computeFoldRegions(state) else emptyMap()
    }
    val foldRegions = decor?.foldSpans ?: ownFoldRegions
    val foldedStarts = remember(state) { mutableStateListOf<Int>() }
    // In a diff the collapsed set belongs to the pair — one arrow folds both columns — so this editor's own
    // list stays empty and the panel's answer stands in for it. Either way the hidden rows are *derived* from
    // the spans rather than passed in, so there is one definition of what a fold covers.
    val activeFolds = (decor?.foldedRows ?: foldedStarts).filter { foldRegions.containsKey(it) }
    val collapsed = activeFolds.toSet()
    val hidden = remember(foldRegions, activeFolds) {
        if (activeFolds.isEmpty()) emptySet() else buildSet {
            for (start in activeFolds) {
                val end = foldRegions[start]?.endLine ?: continue
                for (line in start + 1..end) add(line)
            }
        }
    }
    val visibleLines = remember(state, state.version, hidden) {
        if (hidden.isEmpty()) null
        else (0 until lineCount).filter { it !in hidden }
    }

    // Sticky parent lines, over the same regions the fold arrows use. JSON only — the band's whole claim is
    // that an indented line belongs to the key above it, which plain text doesn't make. A diff pane is
    // excluded too: its rows are the pair's grid rather than one document's, and a pinned copy would sit on
    // top of the row tint that says what changed. Never let the band eat more than half the viewport — five
    // rows over a short slot (a rule body editor docked small) would leave nothing underneath them.
    val stickyScopes = language == CodeLanguage.Json && decor == null
    val stickyNesting = remember(stickyScopes, foldRegions) {
        if (stickyScopes) ScopeNesting.from(foldRegions) else ScopeNesting.Empty
    }
    val maxStickyRows = if (lineHeightPx <= 0) 0 else minOf(MAX_STICKY_ROWS, viewportHeightPx / lineHeightPx / 2)

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

    // Brings the caret line on screen both ways. With folds active the list index is the caret's position in
    // `visibleLines`, not its raw line number.
    suspend fun revealCaret() {
        val targetIndex = if (visibleLines == null) {
            caret.line
        } else {
            visibleLines.indexOf(caret.line).let { if (it >= 0) it else visibleLines.indexOfLast { l -> l <= caret.line } }
        }.coerceAtLeast(0)
        // The sticky band paints over the top of the list, so the caret has to stay that many rows clear of
        // it: without this, a scroll that "revealed" the caret parks it behind the band and the keystrokes
        // that follow are typed blind. Counted in rows rather than pixels because a wrapped row is never
        // shorter than one line, which makes the clearance hold under wrap too.
        val clearRows = if (stickyScopes) maxStickyRows else 0
        val onScreen = listState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
        if (!onScreen || targetIndex - listState.firstVisibleItemIndex < clearRows) {
            listState.scrollToItem((targetIndex - clearRows).coerceAtLeast(0))
        }
        // Wrapped lines never overflow horizontally, so there's no sideways follow — just park the scroll at 0.
        if (wrapCols != null) {
            if (hScroll.value != 0) hScroll.scrollTo(0)
            return
        }
        val contentViewport = (viewportWidthPx - gutterAndFoldPx).coerceAtLeast(1f)
        val caretX = caret.col * charWidthPx
        val target = when {
            caretX < hScroll.value -> caretX
            caretX > hScroll.value + contentViewport - charWidthPx -> caretX - contentViewport + charWidthPx
            else -> null
        }
        target?.let { hScroll.scrollTo(it.roundToInt().coerceIn(0, hScroll.maxValue)) }
    }

    // Keep the caret line composed (so its row and this frame's edits stay live) and on-screen. A diff's panes
    // share one scroll position, so only the focused pane may chase its caret: the idle one's caret sits at the
    // top of its document and would otherwise drag the pair back there on every fold.
    LaunchedEffect(caret, viewportWidthPx, visibleLines, wrapCols, focused) {
        if (decor != null && !focused) return@LaunchedEffect
        revealCaret()
    }

    // Find hands focus to its query field and keeps it there, so a diff pane is never focused while searching
    // and the guard above would swallow the very scroll that puts the hit on screen — leaving the match
    // selected off-screen and the bar looking dead. A reveal the pane asks for explicitly is exempt: only the
    // pane whose bar found something bumps this, so the idle side still can't drag the pair to its own top.
    // Keyed on the count rather than the caret so a later fold or focus change cannot replay it. A plain
    // editor needs none of this — nothing bars its caret follow.
    LaunchedEffect(findReveals) {
        if (decor != null && findReveals > 0) revealCaret()
    }

    // The selection to actually copy/cut. When it ends right at a collapsed opener's fold point (that line's
    // end, where `{`/`[` sits), stretch the end down to the matching close so the copy carries the whole
    // `{ ⋯ }` body the user sees as one unit — not just the visible opener line (the folded-away lines are
    // hidden, but they're still in the buffer). A selection that runs past the fold already spans it.
    fun effectiveSelection(): Pair<TextPos, TextPos>? {
        val sel = state.selectionRange() ?: return null
        var end = sel.second
        val fold = foldRegions[end.line]
        if (fold != null && end.line in collapsed && end.col == state.buffer.lineLength(end.line)) {
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

    // Dismissing the bar empties it, so the next Cmd+F opens on a clean field instead of re-presenting a
    // query hunted through some earlier body. Match case and the replace row are modes rather than values,
    // so they carry over.
    fun closeFind() {
        findOpen = false
        findQuery = ""
        findReplacement = ""
        focusRequester.requestFocus()
    }

    // Selects the next/previous match, deliberately leaving focus where it is: the find bar keeps it, so a
    // run of Enters walks the document instead of the first one throwing the user back into the text (where
    // the second Enter would insert a newline). The match still shows — the selection highlight doesn't
    // depend on the editor being focused — and the reveal effects above scroll it into view.
    // [fromMatchStart] re-tests the current match in place, so flipping Match case updates the highlight
    // rather than skipping past the hit sitting under it.
    fun runFind(forward: Boolean, fromMatchStart: Boolean = false) {
        if (findQuery.isEmpty()) return
        val selection = state.selectionRange()
        val from = when {
            fromMatchStart -> selection?.first ?: state.caret
            forward -> selection?.second ?: state.caret
            else -> selection?.first ?: state.caret
        }
        val match = state.find(findQuery, from, forward, ignoreCase = !matchCase) ?: return
        // Unfold any collapsed region hiding the hit, so the selection isn't stranded off-screen.
        if (match.first.line in hidden) {
            val covering = activeFolds.filter { s ->
                foldRegions[s]?.let { match.first.line in (s + 1)..it.endLine } == true
            }
            if (decor == null) foldedStarts.removeAll(covering.toSet()) else covering.forEach(decor.onToggleFold)
        }
        state.setSelection(match.first, match.second)
        findReveals++
    }

    // Rewrites the highlighted match and steps to the next one, which is what makes repeated Replace sweep
    // the document. It replaces only a selection that *is* a match, so the first press after opening the row
    // (or after the user has clicked into the text) finds a hit instead of editing at the caret. The raw
    // selection is used on purpose — no `expandSelectionOverFold`, which would swallow a whole collapsed
    // block when a match happens to end at a folded opener's bracket.
    fun runReplace() {
        if (readOnly || findQuery.isEmpty()) return
        if (state.hasSelection() && state.selectedText().equals(findQuery, ignoreCase = !matchCase)) {
            state.insert(findReplacement)
        }
        runFind(forward = true)
    }

    fun runReplaceAll() {
        if (readOnly || findQuery.isEmpty()) return
        state.replaceAll(findQuery, findReplacement, ignoreCase = !matchCase)
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
    fun onKey(event: KeyEvent): Boolean {
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
                findFocusRequests++
            }
            event.key == Key.Escape -> {
                if (!findOpen) return false
                closeFind()
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

    // Maps a pointer drag (relative to the origin line's content box) to a document position. Off wrap it's
    // the original arithmetic — one physical line per fixed row — so nothing about the plain grid changes.
    // On wrap, uneven row heights mean a y-offset can't be divided by a constant, so it walks the list's
    // measured item offsets: origin item top + local y → an absolute viewport y → the item under it → the
    // visual row within that item → the column.
    fun resolveDrag(originLine: Int, localX: Float, localY: Float): TextPos {
        val cols = wrapCols
        if (cols == null) {
            val line = originLine + floor(localY / lineHeightPx).toInt()
            val col = (localX / charWidthPx).roundToInt().coerceAtLeast(0)
            return TextPos(line, col)
        }
        val info = listState.layoutInfo
        val originRow = visibleLines?.indexOf(originLine)?.takeIf { it >= 0 } ?: originLine
        val originItem = info.visibleItemsInfo.firstOrNull { it.index == originRow }
        val absY = (originItem?.offset ?: 0) + localY
        val hit = info.visibleItemsInfo.firstOrNull { absY >= it.offset && absY < it.offset + it.size }
            ?: if (absY < (info.visibleItemsInfo.firstOrNull()?.offset ?: 0)) info.visibleItemsInfo.firstOrNull()
            else info.visibleItemsInfo.lastOrNull()
        if (hit == null) return TextPos(originLine, 0)
        val line = visibleLines?.getOrNull(hit.index) ?: hit.index
        val len = state.buffer.lineLength(line)
        val vr = floor((absY - hit.offset).coerceAtLeast(0f) / lineHeightPx).toInt()
        val colInRow = (localX / charWidthPx).roundToInt()
        return TextPos(line, visualPosToCol(vr, colInRow, len, cols))
    }

    Column(modifier.background(scheme.surface)) {
        if (findOpen) {
            FindBar(
                query = findQuery,
                onQueryChange = { findQuery = it },
                replacement = findReplacement,
                onReplacementChange = { findReplacement = it },
                matchCase = matchCase,
                onToggleMatchCase = {
                    matchCase = !matchCase
                    runFind(forward = true, fromMatchStart = true)
                },
                replaceAvailable = !readOnly,
                replaceOpen = replaceOpen,
                onToggleReplaceOpen = { replaceOpen = !replaceOpen },
                onNext = { runFind(forward = true) },
                onPrev = { runFind(forward = false) },
                onReplace = { runReplace() },
                onReplaceAll = { runReplaceAll() },
                onClose = { closeFind() },
                focusSignal = findFocusRequests,
            )
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged {
                    viewportWidthPx = it.width
                    viewportHeightPx = it.height
                }
                .onFocusChanged { focused = it.isFocused }
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { onKey(it) },
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                val rowCount = visibleLines?.size ?: lineCount
                items(count = rowCount, key = { visibleLines?.get(it) ?: it }) { row ->
                    val index = visibleLines?.get(row) ?: row
                    // The arrow and the `⋯ }` chip follow *this* document's brackets, while what a collapse
                    // hides follows the pair's merged span — so a row whose other half is a blank filler no
                    // longer offers a control over nothing.
                    val opensHere = if (decor != null) {
                        decor.foldArrows.containsKey(index)
                    } else {
                        foldRegions.containsKey(index)
                    }
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
                        wrapCols = wrapCols,
                        resolveDrag = ::resolveDrag,
                        caretOn = caretOn && focused,
                        focused = focused,
                        foldable = opensHere,
                        folded = opensHere && index in collapsed,
                        foldCloseChar = if (decor != null) decor.foldArrows[index] else foldRegions[index]?.closeChar,
                        lineNumber = if (decor != null) decor.lineNumber(index) else index + 1,
                        minRows = decor?.let { visualRowCount(it.otherLength(index), wrapCols) } ?: 1,
                        rowTint = decor?.rowTint?.invoke(index),
                        spanRange = decor?.spanRange?.invoke(index),
                        spanTint = decor?.spanTint ?: Color.Transparent,
                        onToggleFold = {
                            focusRequester.requestFocus()
                            if (decor != null) decor.onToggleFold(index) else toggleFold(index)
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
            if (stickyScopes && maxStickyRows > 0) {
                StickyScopes(
                    state = state,
                    nesting = stickyNesting,
                    maxRows = maxStickyRows,
                    foldRegions = foldRegions,
                    listState = listState,
                    visibleLines = visibleLines,
                    hScroll = hScroll,
                    language = language,
                    textStyle = textStyle,
                    highlight = highlight,
                    lineHeightPx = lineHeightPx,
                    lineHeightDp = lineHeightDp,
                    gutterWidthDp = gutterWidthDp,
                )
            }
            // Self-hiding desktop scrollbars: they paint a thumb only while the body overflows. The
            // horizontal one exists only off wrap — wrapped lines never overflow sideways.
            if (decor == null || decor.verticalScrollbar) {
                VerticalListScrollbar(
                    listState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
            if (wrapCols == null) {
                HorizontalScrollbar(
                    scrollState = hScroll,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                )
            }
            // A diff's two panes must wrap together, so the toggle lives once in its toolbar instead of
            // twice in the corners, where the two could disagree and pull the columns apart.
            if (decor == null) {
                WrapToggle(
                    enabled = wrap,
                    onToggle = {
                        ownWrap = !ownWrap
                        focusRequester.requestFocus()
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 14.dp),
                )
            }
        }
    }
}

// The soft-wrap toggle: a compact icon chip floated in the editor's top-right corner (clear of the
// scrollbar). Tinted with the primary color while wrap is on so its state reads at a glance.
@Composable
private fun WrapToggle(enabled: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    // The align/padding [modifier] must ride a node that is a *direct* child of the editor's Box. HoverTooltip
    // (TooltipArea) emits its own layout node, which would otherwise be that child and swallow the alignment —
    // dropping the chip to the default top-left. So positioning lives on this wrapper Box; the tooltip + chip
    // sit inside it.
    Box(modifier) {
        HoverTooltip(if (enabled) "Soft wrap: on" else "Soft wrap: off") {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(scheme.surfaceContainer.copy(alpha = 0.9f))
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    vectorResource(Res.drawable.ic_wrap_text),
                    contentDescription = if (enabled) "Turn soft wrap off" else "Turn soft wrap on",
                    tint = if (enabled) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The blocks still open above the top of the viewport, pinned over it — so the key an object belongs to
 * stays on screen while its children scroll past. Each row is a real document line, highlighted and numbered
 * exactly as it is in place, and clicking one scrolls back to it.
 *
 * The deepest row is placed from its block's *closing* row rather than from its own slot, so it slides up
 * behind the row above as the block ends instead of disappearing the instant the chain shortens. JSON needs
 * that more than code does: a block here is often three lines, so the pop would fire on every array element.
 * That placement is read in the layout phase rather than in composition, so scrolling re-places the band
 * without recomposing it and only a change of *which* lines are pinned costs a recomposition.
 */
@Composable
private fun StickyScopes(
    state: CodeEditorState,
    nesting: ScopeNesting,
    maxRows: Int,
    foldRegions: Map<Int, Fold>,
    listState: LazyListState,
    visibleLines: List<Int>?,
    hScroll: ScrollState,
    language: CodeLanguage,
    textStyle: TextStyle,
    highlight: HighlightColors,
    lineHeightPx: Int,
    lineHeightDp: Dp,
    gutterWidthDp: Dp,
) {
    // Where a line sits in the list once folds have taken its neighbours out of it.
    fun rowOf(line: Int): Int? =
        if (visibleLines == null) line else visibleLines.binarySearch(line).takeIf { it >= 0 }

    val topRow = listState.firstVisibleItemIndex
    val topLine = visibleLines?.getOrNull(topRow) ?: topRow
    val lines = remember(nesting, topLine, maxRows) { nesting.stickyAncestorsOf(topLine, maxRows) }
    if (lines.isEmpty()) return

    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val closeRow = foldRegions[lines.last()]?.endLine?.let { rowOf(it) }
    val lastSlotPx = (lines.size - 1) * lineHeightPx

    fun lastTopPx(): Int {
        val closeTop = closeRow
            ?.let { row -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == row }?.offset }
            ?: return lastSlotPx
        // Ride just above the closing bracket once it has climbed into the band, and stop against the row
        // above: by the time the two fully overlap the block has left the chain, so the hand-off is seamless.
        return (closeTop - lineHeightPx).coerceIn(lastSlotPx - lineHeightPx, lastSlotPx)
    }

    Box(
        Modifier.fillMaxWidth()
            .height(lineHeightDp * lines.size + 1.dp)
            .clipToBounds()
            // The band is opaque and sits over the list, so without this it would swallow the wheel across
            // the whole top of the editor. Reversed for the reason a LazyColumn reverses it.
            .scrollable(state = listState, orientation = Orientation.Vertical, reverseDirection = true),
    ) {
        Box(
            Modifier.offset { IntOffset(0, lastTopPx() + lineHeightPx) }
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outlineVariant),
        )
        // Outermost composed last, so it paints over the deeper rows sliding up behind it.
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            key(line) {
                StickyScopeRow(
                    text = state.lineAt(line),
                    number = line + 1,
                    language = language,
                    textStyle = textStyle,
                    highlight = highlight,
                    lineHeightDp = lineHeightDp,
                    gutterWidthDp = gutterWidthDp,
                    hScroll = hScroll,
                    top = { if (i == lines.lastIndex) lastTopPx() else i * lineHeightPx },
                    // Land the line under the band rather than at the top of the list, where the band that
                    // is left once it stops being an ancestor would cover the very row that was clicked.
                    // [maxRows] is the band's ceiling, so the clearance holds whatever ends up pinned.
                    onClick = {
                        rowOf(line)?.let { row ->
                            scope.launch { listState.scrollToItem((row - maxRows).coerceAtLeast(0)) }
                        }
                    },
                )
            }
        }
    }
}

// One pinned line. It is a label with a single gesture — click to go back to it — so the fold column is
// drawn empty rather than carrying an arrow that wouldn't fold anything.
@Composable
private fun StickyScopeRow(
    text: String,
    number: Int,
    language: CodeLanguage,
    textStyle: TextStyle,
    highlight: HighlightColors,
    lineHeightDp: Dp,
    gutterWidthDp: Dp,
    hScroll: ScrollState,
    top: () -> Int,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val annotated = remember(text, language, highlight) { annotateLine(text, language, highlight) }
    val click by rememberUpdatedState(onClick)
    Row(
        Modifier.offset { IntOffset(0, top()) }
            .fillMaxWidth()
            .height(lineHeightDp)
            .background(scheme.surface)
            // A raw tap rather than `clickable`, which is a focus target: pulling focus off the editor would
            // fire its caret-follow effect, which scrolls straight back to wherever the caret was left.
            .pointerInput(Unit) { detectTapGestures { click() } },
    ) {
        Box(
            Modifier.width(gutterWidthDp).fillMaxHeight()
                .background(scheme.surfaceContainer)
                .padding(end = 8.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(number.toString(), style = textStyle.copy(color = scheme.onSurfaceVariant), maxLines = 1)
        }
        Box(Modifier.width(FOLD_COL_WIDTH).fillMaxHeight().background(scheme.surfaceContainer))
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            Text(
                annotated,
                modifier = Modifier.offset { IntOffset(-hScroll.value, 0) },
                style = textStyle,
                softWrap = false,
                maxLines = 1,
            )
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
    wrapCols: Int?,
    resolveDrag: (Int, Float, Float) -> TextPos,
    caretOn: Boolean,
    focused: Boolean,
    foldable: Boolean,
    folded: Boolean,
    foldCloseChar: Char?,
    lineNumber: Int?,
    minRows: Int,
    rowTint: Color?,
    spanRange: IntRange?,
    spanTint: Color,
    onToggleFold: () -> Unit,
    onPlaceCaret: (TextPos) -> Unit,
    onExtendSelect: (TextPos) -> Unit,
    onDragSelect: (TextPos, TextPos) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val lineText = state.lineAt(index)
    val len = lineText.length
    // Under wrap this line's own text spans [textRows] visual rows; off wrap it's always one. [rows] is the
    // row box's height, which in a diff cannot drop below what the opposite pane needs here — that is what
    // lets the two columns wrap and still stay level. The rows it adds are filler with no columns of their
    // own, so slicing or highlighting the text iterates [textRows]; over [rows] it would ask for a slice
    // starting past the line's end. The caret/selection/fold overlays sit on the (row, colInRow) grid via
    // [caretVisualPos].
    val textRows = visualRowCount(len, wrapCols)
    val rows = maxOf(textRows, minRows)
    val caret = state.caret
    val selection = state.selectionRange()
    val isCaretLine = index == caret.line
    // The current-line affordances (brighter gutter number + faint row tint) belong to a focused editor;
    // an unfocused viewer has no active caret, so its caret row must not be singled out.
    val activeCaretLine = isCaretLine && focused
    // The diff span sits on top of the syntax color and resets the text under it to plain onSurface: a JSON
    // string value is already green, and green-on-tint was the one combination that read as unhighlighted.
    val annotated = remember(lineText, language, highlight, spanRange, spanTint) {
        val base = annotateLine(lineText, language, highlight)
        if (spanRange == null) {
            base
        } else {
            buildAnnotatedString {
                append(base)
                val from = spanRange.first.coerceIn(0, lineText.length)
                val to = (spanRange.last + 1).coerceIn(from, lineText.length)
                if (to > from) {
                    addStyle(SpanStyle(background = spanTint, color = highlight.key), from, to)
                }
            }
        }
    }
    // A collapsed block reads as selected once the selection reaches the opener's fold point (its line end,
    // where `{`/`[` sits): the `⋯ }` chip then highlights and Ctrl+C copies the whole hidden body (the copy
    // side of this is `effectiveSelection`). Selecting only part of the opener, short of that point, doesn't.
    val foldPoint = TextPos(index, lineText.length)
    val foldBodySelected = folded && selection != null &&
        selection.first != selection.second &&
        selection.first <= foldPoint && selection.second >= foldPoint

    Row(Modifier.fillMaxWidth().height(lineHeightDp * rows)) {
        // The gutter number sits on the first visual row (top), so a wrapped line is numbered once, at its
        // opener, rather than floating in the vertical center of its whole wrapped block.
        Box(
            Modifier.width(gutterWidthDp).fillMaxHeight()
                .background(scheme.surfaceContainer)
                .padding(end = 8.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(Modifier.height(lineHeightDp), contentAlignment = Alignment.CenterEnd) {
                // Blank on a filler row, which belongs to the diff's grid rather than to this document and
                // so has no line of its own to number.
                Text(
                    lineNumber?.toString().orEmpty(),
                    // Inactive numbers use onSurfaceVariant (as the JSON preview does) rather than the faint
                    // `outline`, which was too low-contrast to read; the focused caret's line brightens to onSurface.
                    style = textStyle.copy(color = if (activeCaretLine) scheme.onSurface else scheme.onSurfaceVariant),
                    maxLines = 1,
                )
            }
        }
        // Fold gutter: a disclosure arrow on opener lines (down = expanded, right = collapsed), on the first
        // visual row. Fixed width so text left-edges stay aligned whether or not a line is foldable.
        Box(
            Modifier.width(FOLD_COL_WIDTH).fillMaxHeight().background(scheme.surfaceContainer),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (foldable) {
                Box(Modifier.height(lineHeightDp), contentAlignment = Alignment.Center) {
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
        }
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .background(
                    rowTint
                        ?: if (activeCaretLine && selection == null) {
                            scheme.onSurface.copy(alpha = 0.05f)
                        } else {
                            Color.Transparent
                        },
                )
                .clipToBounds()
                // Wrapped content fills the viewport and reflows; only the plain grid scrolls sideways.
                .let { if (wrapCols == null) it.horizontalScroll(hScroll) else it },
        ) {
            Box(
                (if (wrapCols == null) Modifier.width(contentWidthDp) else Modifier.fillMaxWidth())
                    .fillMaxHeight()
                    .pointerInput(index, lineText, charWidthPx, lineHeightPx, folded, wrapCols) {
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
                                // The (visual row, column-in-row) hit — the inverse of how the caret is drawn.
                                // Off wrap it collapses to row 0 and a straight column.
                                val hitRow = if (wrapCols == null) 0 else floor(down.position.y / lineHeightPx).toInt()
                                val hitColInRow = (down.position.x / charWidthPx).roundToInt()
                                // On a collapsed row, only a hit on the `⋯` chip (the cells right after the
                                // opener text, before the close bracket) expands — clicking the opener text or
                                // the close bracket just places a caret, as the user asked.
                                val chip = caretVisualPos(len, len, wrapCols)
                                if (folded && !shift && hitRow == chip.row &&
                                    hitColInRow >= chip.colInRow && hitColInRow < chip.colInRow + DOTS_CELLS
                                ) {
                                    onToggleFold()
                                    continue
                                }
                                val downCol = visualPosToCol(hitRow, hitColInRow, len, wrapCols)
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
                                    onDragSelect(dragAnchor, resolveDrag(index, change.position.x, change.position.y))
                                    change.consume()
                                }
                            }
                        }
                    },
            ) {
                if (selection != null && index >= selection.first.line && index <= selection.second.line) {
                    val startCol = if (index == selection.first.line) selection.first.col else 0
                    val endCol = if (index == selection.second.line) selection.second.col else len
                    // One highlight box per visual row: the slice of [startCol, endCol] that falls on that row.
                    // A half-cell of slack past a fully-covered line's end (its last row) shows the trailing
                    // newline is inside the range.
                    for (r in 0 until textRows) {
                        val rowStart = if (wrapCols == null) 0 else r * wrapCols
                        val rowEnd = if (wrapCols == null) len else minOf((r + 1) * wrapCols, len)
                        val segStart = maxOf(startCol, rowStart)
                        val segEnd = minOf(endCol, rowEnd)
                        val slack = if (r == textRows - 1 && index != selection.second.line) charWidthDp * 0.5f else 0.dp
                        if (segEnd > segStart || slack > 0.dp) {
                            Box(
                                Modifier.offset(x = charWidthDp * (segStart - rowStart), y = lineHeightDp * r)
                                    // Same layout-constraint cap as the content box: a full-line selection on a
                                    // huge single line (wrap off) would otherwise blow past what Compose can pack.
                                    .width((charWidthDp * (segEnd - segStart).coerceAtLeast(0) + slack).coerceAtMost(contentWidthDp))
                                    .height(lineHeightDp)
                                    .background(scheme.primary.copy(alpha = 0.28f)),
                            )
                        }
                    }
                }
                // The collapsed-block highlight covers the `⋯` chip + close bracket, so a fold that's inside a
                // selection reads as selected across its whole visible extent.
                if (foldBodySelected) {
                    val chip = caretVisualPos(len, len, wrapCols)
                    Box(
                        Modifier.offset(x = charWidthDp * chip.colInRow, y = lineHeightDp * chip.row)
                            .width(charWidthDp * (DOTS_CELLS + 1))
                            .height(lineHeightDp)
                            .background(scheme.primary.copy(alpha = 0.28f)),
                    )
                }
                if (wrapCols == null) {
                    Text(annotated, style = textStyle, softWrap = false, maxLines = 1)
                } else {
                    // One Text per visual row, sliced on the exact column boundaries the caret/selection math
                    // uses — so glyphs stay locked to the grid (Compose's own word-wrap would break elsewhere).
                    Column {
                        for (r in 0 until textRows) {
                            val s = r * wrapCols
                            val e = minOf(s + wrapCols, len)
                            Text(annotated.subSequence(s, e), style = textStyle, softWrap = false, maxLines = 1)
                        }
                    }
                }
                // A collapsed opener already drew its own `{`/`[`; pull the matching close up onto its row with
                // three dots between them, so it reads as one `{ ⋯ }` unit. The dots are drawn (not a glyph) so
                // they sit dead-center between the brackets both ways, and the chip is the only expand target.
                if (folded) {
                    val dotColor = scheme.onSurfaceVariant
                    val chip = caretVisualPos(len, len, wrapCols)
                    Row(
                        Modifier.offset(x = charWidthDp * chip.colInRow, y = lineHeightDp * chip.row).height(lineHeightDp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(Modifier.width(charWidthDp * DOTS_CELLS).height(lineHeightDp)) {
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
                    val cp = caretVisualPos(caret.col, len, wrapCols)
                    Box(
                        Modifier.offset(x = charWidthDp * cp.colInRow, y = lineHeightDp * cp.row)
                            .width(2.dp)
                            .height(lineHeightDp)
                            .background(scheme.primary),
                    )
                }
            }
        }
    }
}

/**
 * A slim find/replace toolbar above the editor. Type to search, Enter / Shift+Enter for next / previous,
 * Escape to dismiss; the Aa chip flips case sensitivity. Every action keeps focus in the field it was
 * invoked from, so the bar can be driven entirely from the keyboard (see `runFind`). The replace half stays
 * collapsed behind the disclosure arrow until asked for, and is absent on a read-only surface. Single-line
 * substring find (a JSON string can't hold a raw newline).
 */
@Composable
private fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    replacement: String,
    onReplacementChange: (String) -> Unit,
    matchCase: Boolean,
    onToggleMatchCase: () -> Unit,
    replaceAvailable: Boolean,
    replaceOpen: Boolean,
    onToggleReplaceOpen: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    focusSignal: Int,
) {
    val scheme = MaterialTheme.colorScheme
    val queryFocus = remember { FocusRequester() }
    val replacementFocus = remember { FocusRequester() }
    // Runs on first composition (the bar exists only while open, so opening it focuses the query) and again
    // on every later [focusSignal] change, which is how a second Cmd+F pulls the caret back out of the text.
    LaunchedEffect(focusSignal) { queryFocus.requestFocus() }

    // Both rows share the query field's Enter/Escape contract; only what Enter *does* differs.
    fun barKeys(onEnter: (KeyEvent) -> Unit): (KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else when (event.key) {
            Key.Enter -> {
                onEnter(event)
                true
            }
            Key.Escape -> {
                onClose()
                true
            }
            else -> false
        }
    }

    Row(
        Modifier.fillMaxWidth()
            .background(scheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (replaceAvailable) {
            // A chevron (right = collapsed, down = expanded) rather than the fold gutter's drop-down triangle:
            // that glyph is drawn to sit under a dropdown's text baseline, so it covers barely a fifth of its
            // viewport and reads as a speck on a standalone button. The chevron fills the same 16.dp frame.
            HoverTooltip(if (replaceOpen) "Hide replace" else "Show replace") {
                IconButton(onClick = onToggleReplaceOpen, modifier = Modifier.size(28.dp)) {
                    Icon(
                        vectorResource(Res.drawable.ic_keyboard_arrow_down),
                        contentDescription = if (replaceOpen) "Hide replace" else "Show replace",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).rotate(if (replaceOpen) 0f else -90f),
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompactOutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f)
                        .focusRequester(queryFocus)
                        .onPreviewKeyEvent(barKeys { if (it.isShiftPressed) onPrev() else onNext() }),
                    placeholder = "Find",
                    containerColor = scheme.surface,
                )
                FindToggleChip(
                    icon = Res.drawable.ic_match_case,
                    label = "Match case",
                    on = matchCase,
                    // A pointer click on any of the bar's buttons would otherwise leave focus on the button,
                    // so typing after one goes nowhere. Hand it back to the field the user was working in.
                    onToggle = {
                        onToggleMatchCase()
                        queryFocus.requestFocus()
                    },
                )
                FindIconButton(
                    icon = Res.drawable.ic_arrow_back,
                    label = "Previous match (Shift+Enter)",
                    onClick = {
                        onPrev()
                        queryFocus.requestFocus()
                    },
                )
                FindIconButton(
                    icon = Res.drawable.ic_arrow_back,
                    label = "Next match (Enter)",
                    rotate = 180f,
                    onClick = {
                        onNext()
                        queryFocus.requestFocus()
                    },
                )
                CloseButton(onClose, contentDescription = "Close find")
            }
            if (replaceAvailable && replaceOpen) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CompactOutlinedTextField(
                        value = replacement,
                        onValueChange = onReplacementChange,
                        modifier = Modifier.weight(1f)
                            .focusRequester(replacementFocus)
                            .onPreviewKeyEvent(
                                barKeys { event ->
                                    if (event.isMetaPressed || event.isCtrlPressed) onReplaceAll() else onReplace()
                                },
                            ),
                        placeholder = "Replace",
                        containerColor = scheme.surface,
                    )
                    FindIconButton(
                        icon = Res.drawable.ic_swap_horiz,
                        label = "Replace this match (Enter)",
                        onClick = {
                            onReplace()
                            replacementFocus.requestFocus()
                        },
                    )
                    FindIconButton(
                        icon = Res.drawable.ic_find_replace,
                        label = "Replace every match (Cmd/Ctrl+Enter)",
                        onClick = {
                            onReplaceAll()
                            replacementFocus.requestFocus()
                        },
                    )
                }
            }
        }
    }
}

// A find-bar option: filled and accent-tinted while on, so its state reads at a glance (the same language
// as the editor's soft-wrap toggle).
@Composable
private fun FindToggleChip(icon: DrawableResource, label: String, on: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    HoverTooltip(label) {
        Box(
            Modifier.size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (on) scheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                vectorResource(icon),
                contentDescription = label,
                tint = if (on) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// The bar's stateless actions. [rotate] lets one glyph serve a mirrored pair (back/forward).
@Composable
private fun FindIconButton(icon: DrawableResource, label: String, rotate: Float = 0f, onClick: () -> Unit) {
    HoverTooltip(label) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            Icon(
                vectorResource(icon),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).rotate(rotate),
            )
        }
    }
}

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
internal data class Fold(val endLine: Int, val closeChar: Char)

/**
 * Maps each opener line to its [Fold] for every *multi-line* `{}`/`[]` pair — the foldable regions, keyed by
 * the line the fold arrow sits on. String-aware (brackets inside `"…"` are ignored; a `\`-escaped char is
 * skipped), so it doesn't fold on braces inside string values. When two pairs open on one line, the outermost
 * (largest close line) wins, so that line's arrow folds the whole span. Bails out empty above [FOLD_MAX_CHARS]
 * since it scans the whole document.
 */
internal fun computeFoldRegions(state: CodeEditorState): Map<Int, Fold> {
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
