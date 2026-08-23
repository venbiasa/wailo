package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.shared.BodyHandle
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.format.BodyContent
import com.venbiasa.wailo.shared.format.DiffOp
import com.venbiasa.wailo.shared.format.DiffRow
import com.venbiasa.wailo.shared.format.bodyContent
import com.venbiasa.wailo.shared.format.canonicalJson
import com.venbiasa.wailo.shared.format.changeHunkStarts
import com.venbiasa.wailo.shared.format.contentType
import com.venbiasa.wailo.shared.format.diffLines
import com.venbiasa.wailo.shared.format.formatBytes
import com.venbiasa.wailo.shared.format.inlineDiffRange
import com.venbiasa.wailo.shared.format.jsonLineKey
import com.venbiasa.wailo.shared.format.parseJson
import com.venbiasa.wailo.shared.format.statusChipText
import com.venbiasa.wailo.shared.format.statusKind
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_keyboard_arrow_down
import com.venbiasa.wailo.shared.resources.ic_swap_horiz
import com.venbiasa.wailo.shared.resources.ic_swap_vert
import com.venbiasa.wailo.shared.resources.ic_wrap_text
import com.venbiasa.wailo.shared.theme.LocalWailoColors
import kotlinx.coroutines.launch
import okio.ByteString
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

/**
 * Which slice of the two exchanges is being compared. Request/response and headers/body are flattened into
 * one list rather than split across a side toggle and a tab row: the answer is nearly always "the response
 * bodies", and a single row of tabs gets there in one click instead of two.
 */
private enum class CompareSection(val label: String) {
    ResponseBody("Resp Body"),
    ResponseHeaders("Resp Headers"),
    RequestBody("Req Body"),
    RequestHeaders("Req Headers"),
    Summary("Summary"),
}

private val CompareSection.isBody: Boolean
    get() = this == CompareSection.ResponseBody || this == CompareSection.RequestBody

/**
 * Above this many characters a body is compared exactly as it arrived — no parse, no key sorting, no
 * re-serialization.
 *
 * Canonicalizing builds a whole `JsonNode` tree and writes a second copy of the document out of it, so it
 * costs several times the payload in allocation before a single line has been compared. That is a good trade
 * on the bodies people actually diff and a bad one on a multi-megabyte export, where the tidying is what
 * would stall rather than the diff.
 */
private const val MAX_CANONICAL_CHARS = 1_000_000

/**
 * Above this many lines on either side the two documents are shown paired off positionally instead of
 * aligned.
 *
 * Alignment costs O((n+m)·D), which stays affordable because D is bounded — but n+m is not, and at some
 * length even a small edit distance is more work than a viewer should do between two frames. This is where
 * the answer stops being worth its wait, and saying so beats appearing to hang.
 */
private const val MAX_ALIGNED_LINES = 20_000

/**
 * A side-by-side diff of two captured exchanges.
 *
 * Both sides are canonicalized before they are compared, which is the whole reason this is usable on real
 * traffic: headers are sorted by name and JSON is re-serialized with sorted keys, so the arbitrary orderings
 * a server is free to change between two calls don't read as differences. What is left is the change the
 * user is actually hunting.
 */
@Composable
internal fun ComparePanel(
    left: FlowEntry,
    right: FlowEntry,
    modifier: Modifier,
    onSwap: () -> Unit,
) {
    // Response bodies are the usual question, but opening there when neither side has one shows "nothing to
    // compare" over two exchanges that differ plenty — so the first section with something in it wins.
    // Decided from the handles' declared sizes, which are known now; the bytes themselves arrive later.
    var section by remember(left.id, right.id) { mutableStateOf(firstNonEmptySection(left, right)) }
    // Sorting object keys is what makes two JSON payloads line up, but it also reorders the document away
    // from what the server actually sent — so it stays a switch, on by default.
    var sortKeys by remember { mutableStateOf(true) }
    // Soft wrap belongs to the pair, not to a pane: two editors free to disagree about it would put row N at
    // different heights and pull the columns apart. So the toggle each editor floats in its own corner is
    // suppressed here and asked once, from the toolbar.
    var wrap by remember { mutableStateOf(true) }

    val leftBodyHandle = section.bodyHandle(left)
    val rightBodyHandle = section.bodyHandle(right)
    val leftBytes = rememberBodyBytes(leftBodyHandle)
    val rightBytes = rememberBodyBytes(rightBodyHandle)

    // Whether this section is really JSON, sniffed from the bodies themselves — not assumed from "it is a
    // body tab". Key sorting and JSON syntax color are both meaningless on HTML, XML, or plain text, and
    // applying them anyway is worse than not offering them: sorting cannot run, and the highlighter would
    // paint an HTML document's quoted attributes as if they were JSON strings.
    val json = remember(left, right, section, leftBytes, rightBytes) {
        section.isJsonBody(left, leftBytes) || section.isJsonBody(right, rightBytes)
    }
    val bodyKind = remember(left, right, section, leftBytes, rightBytes) {
        section.bodyKindLabel(left, leftBytes) ?: section.bodyKindLabel(right, rightBytes) ?: "not JSON"
    }

    val leftLines = remember(left, section, sortKeys, leftBytes) { section.lines(left, leftBytes, sortKeys) }
    val rightLines = remember(right, section, sortKeys, rightBytes) { section.lines(right, rightBytes, sortKeys) }
    val tooLongToAlign = leftLines.size > MAX_ALIGNED_LINES || rightLines.size > MAX_ALIGNED_LINES
    val rows = remember(leftLines, rightLines, json, tooLongToAlign) {
        if (tooLongToAlign) {
            pairedOff(leftLines, rightLines)
        } else {
            diffLines(leftLines, rightLines, if (json) ::jsonLineKey else { line -> line })
        }
    }

    // Each pane is a real document: its own side of every row, with a blank line wherever the row belongs to
    // the other side only. Padding both to the same length is what lets two independent editors stay in step
    // — row N is the same row in both — and it is also what the fold scanner and the selection run over, so
    // dragging across a gap copies the side's text with the gaps in place rather than the two interleaved.
    val leftState = remember(rows) { CodeEditorState(rows.joinToString("\n") { it.left.orEmpty() }) }
    val rightState = remember(rows) { CodeEditorState(rows.joinToString("\n") { it.right.orEmpty() }) }

    // Folding is scanned per side, on the padded documents, so an arrow appears only where *that* document
    // really opens a block. What a collapse hides is then merged, running to whichever side closes later:
    // hiding a different number of rows in each pane would slide the columns apart.
    val leftFolds = remember(leftState) { computeFoldRegions(leftState) }
    val rightFolds = remember(rightState) { computeFoldRegions(rightState) }
    val spans = remember(leftFolds, rightFolds) { mergeFoldSpans(leftFolds, rightFolds) }
    // Which openers the user collapsed is not a property of the rows, so it is kept separately — and reset
    // when the rows change underneath it, since a row index means nothing across two different documents.
    val folded = remember(rows) { mutableStateListOf<Int>() }
    // Counted over every row, not just the visible ones, so collapsing a block does not appear to make the
    // changes inside it go away. Stepping opens whatever is hiding its destination instead.
    val hunks = remember(rows) { changeHunkStarts(rows) }

    // The diff is over whatever prefix was fetched, not the whole payload (ADR-0069). Saying so matters more
    // here than anywhere else: two bodies that agree for their first few megabytes and diverge after would
    // otherwise read as identical.
    val clipped = section.isBody &&
        ((leftBodyHandle?.size ?: 0L) > leftBytes.size || (rightBodyHandle?.size ?: 0L) > rightBytes.size)

    // A scroll position each, mirrored — *not* one state shared by both panes. A `LazyListState` holds the
    // delta a gesture is asking for and hands it to the list that measures next, which zeroes it; a second
    // list reading the same state therefore never moves at all. So the two keep their own and copy each
    // other's position instead. Every row is the same height on both sides, so an index and an offset name
    // exactly the same place in either pane, and the copy is skipped when the target is already there —
    // which is what kills the echo before it can bounce back as a loop.
    val leftList = rememberLazyListState()
    val rightList = rememberLazyListState()
    LaunchedEffect(leftList, rightList) {
        snapshotFlow { leftList.firstVisibleItemIndex to leftList.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> rightList.matchScroll(index, offset) }
    }
    LaunchedEffect(leftList, rightList) {
        snapshotFlow { rightList.firstVisibleItemIndex to rightList.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> leftList.matchScroll(index, offset) }
    }

    val scope = rememberCoroutineScope()
    var hunkAt by remember(rows) { mutableStateOf(-1) }

    fun stepHunk(delta: Int) {
        if (hunks.isEmpty()) return
        val next = (hunkAt + delta).let {
            when {
                it < 0 -> hunks.lastIndex
                it > hunks.lastIndex -> 0
                else -> it
            }
        }
        hunkAt = next
        val target = hunks[next]
        // Open anything collapsed over the destination before scrolling to it, the way the code editor
        // reveals a search hit — otherwise the button would appear dead on a change inside a folded block.
        folded.removeAll { start -> spans[start]?.let { target in (start + 1)..it.endLine } == true }
        scope.launch {
            // The list index is the row's position among the *visible* rows. Recomputed here rather than read
            // from the panes, which are a composition away from catching up with the folds just removed —
            // scrolling by what they currently show would land at the pre-expansion position.
            val hidden = hiddenUnder(spans, folded)
            leftList.animateScrollToItem((0 until target).count { it !in hidden })
        }
    }

    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        // The seam between the strips is drawn, not laid out. A `ColumnDivider` here sizes itself with
        // `fillMaxHeight`, and in a row with no height of its own that resolves to the whole window — which
        // is what once stretched this header over the tabs and both panes. Painting it on the left strip's
        // trailing edge keeps the row free to be exactly as tall as a wrapped URL needs.
        val seam = MaterialTheme.colorScheme.outlineVariant
        Row(Modifier.fillMaxWidth()) {
            IdentityStrip(
                left,
                right,
                "A",
                Modifier.weight(1f).drawBehind {
                    val x = size.width - 0.5.dp.toPx()
                    drawLine(seam, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                },
            )
            IdentityStrip(right, left, "B", Modifier.weight(1f))
        }
        RowDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            UnderlineTabs(
                items = CompareSection.entries.map { TabItem(it, it.label) },
                selected = section,
                onSelect = { section = it },
                modifier = Modifier.weight(1f),
            )
            ChangeCount(hunks.size, hunkAt)
            RotatedIconButton(
                icon = Res.drawable.ic_keyboard_arrow_down,
                label = "Previous change",
                rotate = 180f,
                enabled = hunks.isNotEmpty(),
                onClick = { stepHunk(-1) },
            )
            RotatedIconButton(
                icon = Res.drawable.ic_keyboard_arrow_down,
                label = "Next change",
                enabled = hunks.isNotEmpty(),
                onClick = { stepHunk(1) },
            )
            if (tooLongToAlign) {
                NotAlignedBadge(maxOf(leftLines.size, rightLines.size))
            }
            ToggleChip(
                icon = Res.drawable.ic_swap_vert,
                label = when {
                    tooLongToAlign -> "Too long to align — showing both sides line for line"
                    !json && section.isBody -> "Key sorting applies to JSON only — this body is $bodyKind"
                    !json -> "Key sorting applies to JSON bodies only"
                    sortKeys -> "Sorting JSON keys"
                    else -> "Keeping JSON key order"
                },
                on = sortKeys,
                enabled = json && !tooLongToAlign,
                onToggle = { sortKeys = !sortKeys },
            )
            ToggleChip(
                icon = Res.drawable.ic_wrap_text,
                label = if (wrap) "Soft wrap: on" else "Soft wrap: off",
                on = wrap,
                onToggle = { wrap = !wrap },
            )
            RotatedIconButton(
                icon = Res.drawable.ic_swap_horiz,
                label = "Swap A and B",
                onClick = onSwap,
            )
        }
        RowDivider()
        if (clipped) {
            MutedText(
                "Comparing the first ${formatBytes(leftBytes.size.toLong())} of each body.",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            RowDivider()
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                leftLines.isEmpty() && rightLines.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        MutedText("Nothing to compare in ${section.label}.")
                    }
                else -> ContextMenuHost(copyActions(rows, leftLines, rightLines)) {
                    DiffPanes(
                        rows = rows,
                        leftState = leftState,
                        rightState = rightState,
                        leftFolds = leftFolds,
                        rightFolds = rightFolds,
                        spans = spans,
                        folded = folded,
                        json = json,
                        wrap = wrap,
                        leftList = leftList,
                        rightList = rightList,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * One side's identity, sized to sit directly over its column of the diff below. Both strips take the same
 * weight as the panes, so A always reads above A.
 *
 * [other] is the opposite side, and it decides what appears below the URL: only fields the two disagree on —
 * which app, which device, which capture path — so the common case of two calls from one app carries no
 * second line at all. Anything true of both sides is not what someone opened a diff to read.
 */
@Composable
private fun IdentityStrip(entry: FlowEntry, other: FlowEntry, badge: String, modifier: Modifier) {
    val exchange = entry.exchange
    val request = exchange.request
    val response = exchange.response
    val hasError = exchange.error.isNotEmpty()
    val status = statusColor(statusKind(response?.code, hasError))
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val strong = MaterialTheme.colorScheme.onSurface
    // Only what tells the two apart. Timing and size belong to the row in the main list, and repeating them
    // here spent a whole line on facts nobody opened a diff to read.
    val detail = buildAnnotatedString {
        fun field(text: String) {
            if (length > 0) withStyle(SpanStyle(color = muted)) { append("  ·  ") }
            withStyle(SpanStyle(color = strong, fontWeight = FontWeight.SemiBold)) { append(text) }
        }
        if (entry.appId != other.appId) field(entry.appId)
        if (entry.deviceName != other.deviceName) field(entry.deviceName)
        if (entry.viaProxy != other.viaProxy) field(if (entry.viaProxy) "Proxy" else "Socket")
    }

    val url = request?.url
    Column(modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // Top-aligned, because the URL below wraps to as many lines as it needs — the badge and chips
        // should stay beside its first line rather than float in the middle of a tall block, the same way
        // the detail panel's header handles a long URL.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(18.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                statusChipText(response?.code, response?.message ?: "", hasError),
                Modifier.clip(CircleShape)
                    .background(status.copy(alpha = if (dark) 0.22f else 0.14f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = status,
                maxLines = 1,
            )
            Text(
                request?.method?.ifEmpty { "?" } ?: "?",
                style = monoLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            // Wraps rather than ellipsizes: the tail of a URL is where the id that distinguishes two calls
            // usually lives, so cutting it off removes the very thing being compared. Selectable and
            // part-coloured like the detail panel's, so it can be read and copied out of here.
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    if (url.isNullOrBlank()) AnnotatedString("(no URL)") else urlAnnotated(url),
                    style = monoLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (detail.isNotEmpty()) {
            SelectionContainer {
                Text(detail, style = monoLabel())
            }
        }
    }
}

/**
 * The two sides' fold regions as one map over rows: every opener either side has, each running to whichever
 * of them closes later. Folding has to hide the same rows in both panes — a block that runs three rows
 * further on one side would otherwise leave the columns describing different rows — while the arrows stay
 * per-side, drawn from the maps this merges.
 *
 * The closing bracket carried here is only a placeholder; each pane draws its own from [CodeEditorDecor.foldArrows].
 */
private fun mergeFoldSpans(left: Map<Int, Fold>, right: Map<Int, Fold>): Map<Int, Fold> =
    (left.keys + right.keys).associateWith { start ->
        val l = left[start]
        val r = right[start]
        if ((l?.endLine ?: -1) >= (r?.endLine ?: -1)) l!! else r!!
    }

/** The rows collapsed away under [folded], each of which hides everything after its opener up to its close. */
private fun hiddenUnder(spans: Map<Int, Fold>, folded: List<Int>): Set<Int> {
    if (folded.isEmpty()) return emptySet()
    return buildSet {
        for (start in folded) {
            val end = spans[start]?.endLine ?: continue
            for (row in start + 1..end) add(row)
        }
    }
}

// "3 / 12" while stepping, the bare total before the first step, and the one state worth its own words:
// a diff with nothing in it, which an empty counter would leave the user hunting for.
@Composable
private fun ChangeCount(total: Int, at: Int) {
    val label = when {
        total == 0 -> "identical"
        at < 0 -> "$total ${if (total == 1) "change" else "changes"}"
        else -> "${at + 1} / $total"
    }
    Text(
        label,
        Modifier.padding(horizontal = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

// One glyph serves a mirrored pair (previous/next) through [rotate], the same trick the editor's find bar
// uses for its match arrows.
@Composable
private fun RotatedIconButton(
    icon: DrawableResource,
    label: String,
    onClick: () -> Unit,
    rotate: Float = 0f,
    enabled: Boolean = true,
) {
    HoverTooltip(label) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
            Icon(
                vectorResource(icon),
                contentDescription = label,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DisabledFeatureAlpha)
                },
                modifier = Modifier.size(16.dp).rotate(rotate),
            )
        }
    }
}

/**
 * Says that these two were not aligned, and why. Without it the view is indistinguishable from a diff where
 * genuinely everything changed — the reader would take a bailout for an answer.
 */
@Composable
private fun NotAlignedBadge(lines: Int) {
    HoverTooltip("$lines lines is past the $MAX_ALIGNED_LINES-line limit for aligning two documents") {
        Box(
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(LocalWailoColors.current.warning.copy(alpha = 0.18f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "not aligned",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Filled and accent-tinted while on, the same state language as the editor's soft-wrap and match-case chips.
@Composable
private fun ToggleChip(
    icon: DrawableResource,
    label: String,
    on: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    HoverTooltip(label) {
        Box(
            Modifier.size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (on && enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                )
                .clickable(enabled = enabled, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                vectorResource(icon),
                contentDescription = label,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    on -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Whole-document copy, alongside (not instead of) the drag-select each pane does for itself. A selection
 * answers "this part of this side"; these answer the two things a selection is bad at — one side entire,
 * which would otherwise be a drag through thousands of rows, and the comparison itself, which is not text
 * on screen at all.
 *
 * The text copied is what is on screen: canonicalized, and only as much of the body as was fetched.
 */
@Composable
private fun copyActions(rows: List<DiffRow>, leftLines: List<String>, rightLines: List<String>): List<ContextMenuAction> {
    val clipboard = LocalClipboardManager.current
    return remember(rows, leftLines, rightLines, clipboard) {
        buildList {
            if (leftLines.isNotEmpty()) {
                add(ContextMenuAction("Copy A") { clipboard.setText(AnnotatedString(leftLines.joinToString("\n"))) })
            }
            if (rightLines.isNotEmpty()) {
                add(ContextMenuAction("Copy B") { clipboard.setText(AnnotatedString(rightLines.joinToString("\n"))) })
            }
            add(ContextMenuAction("Copy diff") { clipboard.setText(AnnotatedString(unifiedDiffText(rows))) })
        }
    }
}

/**
 * The comparison as patch-shaped text — the form that pastes usefully into a ticket or a chat, where a
 * two-column layout does not survive. A changed row is emitted as its removal then its addition, which is
 * how every diff tool spells the same thing.
 */
private fun unifiedDiffText(rows: List<DiffRow>): String = buildString {
    for (row in rows) {
        when (row.op) {
            DiffOp.Same -> row.left?.let { appendLine("  $it") }
            DiffOp.Removed -> row.left?.let { appendLine("- $it") }
            DiffOp.Added -> row.right?.let { appendLine("+ $it") }
            DiffOp.Changed -> {
                row.left?.let { appendLine("- $it") }
                row.right?.let { appendLine("+ $it") }
            }
        }
    }
}

/**
 * The two columns, each an ordinary [CodeEditor] over its own padded document — not a diff-shaped lookalike.
 * The first version of this panel hand-rolled a two-column renderer and had to reimplement everything an
 * editor already does; it ended up with no soft wrap, no drag-select, no find, and folding that collapsed
 * both sides on one side's brackets. Reusing the real editor is what makes those behave here exactly as
 * they do in the detail panel, and the diff shrinks to what it actually is: tints, gutter numbers, and a
 * shared scroll.
 *
 * Rows are the diff's, not either document's: a side with nothing on this row holds a blank line and draws a
 * filler band, which is why the gutter number has to be supplied rather than counted.
 *
 * The two are held level by three things. [wrap] and the horizontal [ScrollState] are literally shared, so
 * neither can disagree. The vertical positions are mirrored ([matchScroll], since one `LazyListState` cannot
 * drive two lists). And the panes are given an *identical* width rather than a weight each: a `Row` splitting
 * an odd number of pixels hands one child the spare one, which is enough to change how many monospace cells
 * fit and so where a wrapped line breaks — one column reflowing a row the other doesn't would put the two out
 * of step for the rest of the document.
 */
@Composable
private fun DiffPanes(
    rows: List<DiffRow>,
    leftState: CodeEditorState,
    rightState: CodeEditorState,
    leftFolds: Map<Int, Fold>,
    rightFolds: Map<Int, Fold>,
    spans: Map<Int, Fold>,
    folded: MutableList<Int>,
    json: Boolean,
    wrap: Boolean,
    leftList: LazyListState,
    rightList: LazyListState,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val wailo = LocalWailoColors.current
    // The hue is lighter on dark, where a low alpha would wash out — the same correction the detail panel's
    // status pill makes. onSurface luminance stands in for "is dark theme".
    val dark = scheme.surface.luminance() < 0.5f

    // One hue for "these differ", not a red side and a green one. Red-against-green states a direction —
    // this was removed, that was added — and a comparison has none: A and B are two captures, either may be
    // the older, and swapping them would invert every color while meaning exactly the same thing. Which side
    // a line is missing from is already said by the filler band opposite it.
    //
    // Deliberately faint, because the line still has to be read *through* the tint; the span tint is the one
    // place the hue is heavy enough to fight the text, which is why the editor resets the text under it.
    val rowTint = wailo.warning.copy(alpha = if (dark) 0.16f else 0.12f)
    val spanTint = wailo.warning.copy(alpha = if (dark) 0.38f else 0.30f)
    // A side with no line here is a filler band, not blank space: an empty gap would read as "this line is
    // empty" rather than "this line does not exist on this side".
    val fillerTint = scheme.onSurface.copy(alpha = 0.04f)

    val inline = remember(rows) {
        rows.map { row ->
            if (row.op == DiffOp.Changed && row.left != null && row.right != null) {
                inlineDiffRange(row.left, row.right)
            } else {
                null to null
            }
        }
    }

    val hScroll = rememberScrollState()
    val language = if (json) CodeLanguage.Json else CodeLanguage.PlainText
    val toggleFold: (Int) -> Unit = { if (!folded.remove(it)) folded.add(it) }
    val foldedRows = folded.toSet()
    val leftArrows = remember(leftFolds) { leftFolds.mapValues { it.value.closeChar } }
    val rightArrows = remember(rightFolds) { rightFolds.mapValues { it.value.closeChar } }

    fun tint(row: Int, own: String?): Color? = when {
        own == null -> fillerTint
        rows.getOrNull(row)?.op == DiffOp.Same -> null
        else -> rowTint
    }

    BoxWithConstraints(modifier.background(scheme.surface)) {
        val paneWidth = (maxWidth - 1.dp) / 2
        Row(Modifier.fillMaxSize()) {
            CodeEditor(
                state = leftState,
                language = language,
                readOnly = true,
                modifier = Modifier.width(paneWidth).fillMaxHeight(),
                decor = CodeEditorDecor(
                    listState = leftList,
                    hScroll = hScroll,
                    wrap = wrap,
                    lineNumber = { rows.getOrNull(it)?.leftNumber },
                    otherLength = { rows.getOrNull(it)?.right?.length ?: 0 },
                    otherMaxLength = rightState.maxLineLength,
                    rowTint = { tint(it, rows.getOrNull(it)?.left) },
                    spanRange = { inline.getOrNull(it)?.first },
                    spanTint = spanTint,
                    verticalScrollbar = false,
                    foldSpans = spans,
                    foldArrows = leftArrows,
                    foldedRows = foldedRows,
                    onToggleFold = toggleFold,
                ),
            )
            ColumnDivider()
            CodeEditor(
                state = rightState,
                language = language,
                readOnly = true,
                modifier = Modifier.width(paneWidth).fillMaxHeight(),
                decor = CodeEditorDecor(
                    listState = rightList,
                    hScroll = hScroll,
                    wrap = wrap,
                    lineNumber = { rows.getOrNull(it)?.rightNumber },
                    otherLength = { rows.getOrNull(it)?.left?.length ?: 0 },
                    otherMaxLength = leftState.maxLineLength,
                    rowTint = { tint(it, rows.getOrNull(it)?.right) },
                    spanRange = { inline.getOrNull(it)?.second },
                    spanTint = spanTint,
                    verticalScrollbar = true,
                    foldSpans = spans,
                    foldArrows = rightArrows,
                    foldedRows = foldedRows,
                    onToggleFold = toggleFold,
                ),
            )
        }
    }
}

/**
 * Puts this pane at the other's scroll position, unless it is already there. The guard is what makes a pair
 * of mirrors safe: the copy moves the target, the target's own watcher sees the move and copies back, and
 * that second copy is a no-op — so the two settle instead of driving each other.
 */
private suspend fun LazyListState.matchScroll(index: Int, offset: Int) {
    if (firstVisibleItemIndex == index && firstVisibleItemScrollOffset == offset) return
    scrollToItem(index, offset)
}

private fun CompareSection.bodyHandle(entry: FlowEntry): BodyHandle? = when (this) {
    CompareSection.ResponseBody -> entry.responseBody
    CompareSection.RequestBody -> entry.requestBody
    else -> null
}

/**
 * The tab to open on: the first, in tab order, that either side has content for. Bodies are judged by their
 * handle's size rather than their bytes because those load asynchronously and this has to answer now;
 * headers by their count. `Summary` is the floor — it is derived from the exchange, so it is never empty.
 */
private fun firstNonEmptySection(left: FlowEntry, right: FlowEntry): CompareSection =
    CompareSection.entries.firstOrNull { section ->
        when (section) {
            CompareSection.ResponseBody, CompareSection.RequestBody ->
                (section.bodyHandle(left)?.size ?: 0L) > 0L || (section.bodyHandle(right)?.size ?: 0L) > 0L
            CompareSection.ResponseHeaders ->
                left.exchange.response?.headers?.isNotEmpty() == true ||
                    right.exchange.response?.headers?.isNotEmpty() == true
            CompareSection.RequestHeaders ->
                left.exchange.request?.headers?.isNotEmpty() == true ||
                    right.exchange.request?.headers?.isNotEmpty() == true
            CompareSection.Summary -> true
        }
    } ?: CompareSection.Summary

private fun CompareSection.bodyHeaders(entry: FlowEntry): List<Header> = when (this) {
    CompareSection.RequestBody -> entry.exchange.request?.headers.orEmpty()
    CompareSection.ResponseBody -> entry.exchange.response?.headers.orEmpty()
    else -> emptyList()
}

/** Whether this side's body parses as JSON — the precondition for both key sorting and JSON syntax color. */
private fun CompareSection.isJsonBody(entry: FlowEntry, body: ByteString): Boolean {
    if (!isBody || body.size == 0) return false
    return (bodyContent(body, bodyHeaders(entry).contentType()) as? BodyContent.Text)?.json == true
}

/**
 * What a non-JSON body is instead, for the disabled sort control's tooltip — the declared subtype where
 * there is one ("html", "xml"), since "this body is html" answers *why* sorting is off far better than the
 * control merely going grey. Null when the side has no body to describe.
 */
private fun CompareSection.bodyKindLabel(entry: FlowEntry, body: ByteString): String? {
    if (!isBody || body.size == 0) return null
    val headers = bodyHeaders(entry)
    return when (bodyContent(body, headers.contentType())) {
        BodyContent.Empty -> null
        is BodyContent.Binary -> "binary"
        is BodyContent.Text -> headers.contentType()
            ?.substringBefore(';')
            ?.substringAfter('/')
            ?.substringAfterLast('+')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: "plain text"
    }
}

/**
 * The two sides laid alongside each other line for line, with no attempt to align them — what is shown once
 * a document is past [MAX_ALIGNED_LINES]. Every row reads as differing, which is honest: nothing here has
 * been matched, so nothing here is known to be the same.
 */
private fun pairedOff(left: List<String>, right: List<String>): List<DiffRow> =
    (0 until maxOf(left.size, right.size)).map { i ->
        val l = left.getOrNull(i)
        val r = right.getOrNull(i)
        DiffRow(
            op = if (l != null && r != null) DiffOp.Changed else if (l != null) DiffOp.Removed else DiffOp.Added,
            left = l,
            right = r,
            leftNumber = if (l != null) i + 1 else null,
            rightNumber = if (r != null) i + 1 else null,
        )
    }

/** The comparable text for one side of this section, already canonicalized. */
private fun CompareSection.lines(entry: FlowEntry, body: ByteString, sortKeys: Boolean): List<String> {
    val exchange = entry.exchange
    return when (this) {
        CompareSection.Summary -> summaryLines(entry)
        CompareSection.RequestHeaders -> headerLines(exchange.request?.headers.orEmpty())
        CompareSection.ResponseHeaders -> headerLines(exchange.response?.headers.orEmpty())
        CompareSection.RequestBody -> bodyLines(exchange.request?.headers.orEmpty(), body, sortKeys)
        CompareSection.ResponseBody -> bodyLines(exchange.response?.headers.orEmpty(), body, sortKeys)
    }
}

// Sorted by name so the wire order — which a server may change between two calls for no reason the user
// cares about — never shows up as a difference. A repeated name keeps both values, ordered by value.
private fun headerLines(headers: List<Header>): List<String> =
    headers.map { "${it.name}: ${it.value_}" }
        .sortedWith(compareBy({ it.substringBefore(':').lowercase() }, { it }))

private fun bodyLines(headers: List<Header>, body: ByteString, sortKeys: Boolean): List<String> {
    if (body.size == 0) return emptyList()
    return when (val content = bodyContent(body, headers.contentType())) {
        BodyContent.Empty -> emptyList()
        is BodyContent.Binary -> listOf("⟨ binary • ${formatBytes(content.size.toLong())} ⟩")
        is BodyContent.Text -> {
            val text = if (content.json && content.text.length <= MAX_CANONICAL_CHARS) {
                parseJson(content.text)?.let { canonicalJson(it, sortKeys) } ?: content.text
            } else {
                content.text
            }
            text.lines()
        }
    }
}

// The fields that are worth an eye before the payloads: what was asked, how it came back, and how long it
// took. Query parameters get a line each (sorted) so a single changed parameter is a single changed line
// rather than one unreadably long URL against another.
private fun summaryLines(entry: FlowEntry): List<String> {
    val exchange = entry.exchange
    val request = exchange.request
    val response = exchange.response
    val url = request?.url.orEmpty()
    val query = url.substringAfter('?', "")

    fun field(name: String, value: String): String = name.padEnd(14) + value

    return buildList {
        add(field("Method", request?.method?.ifEmpty { "?" } ?: "?"))
        add(field("URL", url.substringBefore('?')))
        query.split('&').filter { it.isNotEmpty() }.sorted().forEach { add(field("Query", it)) }
        add(field("Status", statusChipText(response?.code, response?.message.orEmpty(), exchange.error.isNotEmpty())))
        add(field("Duration", durationText(exchange.duration_ms)))
        add(field("Request size", formatBytes(request?.body_size ?: 0L)))
        add(field("Response size", formatBytes(response?.body_size ?: 0L)))
        add(field("Client", entry.appId))
        add(field("Device", entry.deviceName))
        add(field("Source", if (entry.viaProxy) "Proxy" else "Socket"))
        if (exchange.error.isNotEmpty()) add(field("Error", exchange.error))
    }
}
