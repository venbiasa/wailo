package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.shared.PausedFlow
import com.venbiasa.wailo.shared.SeedRuleDef
import com.venbiasa.wailo.shared.format.prettyPrintJson
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import com.venbiasa.wailo.shared.resources.ic_delete
import com.venbiasa.wailo.shared.resources.ic_sync_arrow_down
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.jetbrains.compose.resources.vectorResource

/**
 * The paused-traffic inspector: the content of the standalone breakpoint window (ADR-0034), shown while
 * one or more devices are holding a request/response at a breakpoint (ADR-0027). It replaces the old
 * full-window modal — a hold no longer scrims the main viewer; it opens its own window so traffic stays
 * browsable alongside it.
 *
 * The left column stacks two lists — the holds Waiting for a human, over the armed Seed queue (ADR-0041) —
 * and the pane on the right shows whichever row is selected, marked with a leading accent bar: a hold as
 * an editor, a seed as a read-only preview of the response it will hand back. Holds resolve in **any**
 * order: pick any row, then Resume or Abort it; the rest keep waiting. Resolving the selected hold
 * advances the selection to the next. The window is user-owned — it stays open with nothing waiting (that
 * is how you arm seeds ahead of the traffic they answer) until the user closes it.
 *
 * [seeds] is the armed queue, held by the host so it survives closing and reopening this window;
 * [onFillSeeds]/[onClearSeeds] arm and empty it, and [onLoadSeedBody] reads a seed's stored body for the
 * preview. A seed answers a matching response-phase hold before it ever reaches Waiting, so what's listed
 * here is what is still available to spend.
 *
 * A hold arriving steals attention only when it has to: [onBringToFront] raises the window when it is
 * buried, and the newcomer takes the pane unless the user is already looking at another hold.
 *
 * Both the left column's width and the split between its two lists are dragged, plain `remember` state:
 * deliberately not persisted, unlike the main window's tool panel — this window is opened for a task and
 * closed, so its proportions are a per-session convenience rather than a preference.
 *
 * The selected tab is remembered for the window's lifetime (it defaults to Headers, and a switch to Body
 * sticks as later holds arrive), but it is not persisted — reopening the window starts at Headers again.
 */
@Composable
internal fun BreakpointInspector(
    flows: List<PausedFlow>,
    seeds: List<SeedRuleDef>,
    onFillSeeds: () -> Unit,
    onClearSeeds: () -> Unit,
    onLoadSeedBody: suspend (SeedRuleDef) -> ByteArray,
    onBringToFront: () -> Unit,
    onResume: (correlationId: String, editedRequest: HttpRequest?, editedResponse: HttpResponse?) -> Unit,
    onAbort: (correlationId: String) -> Unit,
) {
    // What the right pane shows — transient window state, and the user's place in the window, so an
    // arriving hold is careful about overwriting it (see below).
    var selection by remember { mutableStateOf<InspectorSelection?>(flows.firstOrNull()?.let(::holdSelection)) }
    // Resolve it against what still exists before rendering: a hold that resolved and a seed that was
    // spent both vanish under the user, and the pane falls back to the first hold still waiting (then to
    // the empty state) rather than showing a stale one.
    val shown: InspectorSelection? = when (val current = selection) {
        is InspectorSelection.Hold -> current.takeIf { flows.any { it.correlationId == current.correlationId } }
        is InspectorSelection.Seed -> current.takeIf { seeds.any { it.id == current.seedId } }
        null -> null
    } ?: flows.firstOrNull()?.let(::holdSelection)

    // Correlation ids already accounted for, seeded from what's held right now so reopening the window
    // over a standing hold doesn't read as a fresh arrival.
    val seenHolds = remember { flows.mapTo(mutableSetOf()) { it.correlationId } }
    // Read the focus flag inside the effect, not in composition, so the whole inspector doesn't recompose
    // every time the window gains or loses focus.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(flows) {
        val arrived = flows.firstOrNull { it.correlationId !in seenHolds }
        seenHolds.clear()
        flows.mapTo(seenHolds) { it.correlationId }
        if (arrived == null) return@LaunchedEffect
        // A hold the user can't see is worse than one they can ignore, so raise a buried window and point
        // the pane at the newcomer. Once the window is already in front, only take the pane if it isn't
        // holding another paused exchange — that one is mid-edit, and yanking it away would lose the work.
        val focused = windowInfo.isWindowFocused
        if (!focused) onBringToFront()
        if (!focused || shown !is InspectorSelection.Hold) selection = holdSelection(arrived)
    }

    // Window-session tab, hoisted above the per-hold editor so it survives switching rows and new holds
    // arriving; it resets to Headers only when the window is next opened (not persisted).
    var tab by remember { mutableStateOf(PausedTab.Headers) }

    val density = LocalDensity.current
    var leftWidth by remember { mutableStateOf(QueuePanelWidth) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Clamp against the live window size, not just at drag time: shrinking the window must not leave
        // the left column wider than the editor's floor allows, or the editor would be squeezed to nothing.
        val maxLeft = (maxWidth - MinEditorWidth).coerceAtLeast(QueuePanelWidth)
        val left = leftWidth.coerceIn(QueuePanelWidth, maxLeft)
        val listsHeight = maxHeight - ResizeHandleThickness
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(left).fillMaxHeight()) {
                // Both lists start at half the free height and are dragged from there. The split is held
                // as the top list's height (not a ratio) so dragging it to a floor and resizing the window
                // keeps the section you sized where you put it.
                var waitingHeight by remember { mutableStateOf<Dp?>(null) }
                val maxWaiting = (listsHeight - MinSectionHeight).coerceAtLeast(MinSectionHeight)
                val waiting = (waitingHeight ?: (listsHeight / 2)).coerceIn(MinSectionHeight, maxWaiting)
                PausedFlowQueue(
                    flows = flows,
                    selectedId = (shown as? InspectorSelection.Hold)?.correlationId,
                    onSelect = { selection = InspectorSelection.Hold(it) },
                    modifier = Modifier.fillMaxWidth().height(waiting),
                )
                DragHandle { deltaPx ->
                    waitingHeight = (waiting + with(density) { deltaPx.toDp() })
                        .coerceIn(MinSectionHeight, maxWaiting)
                }
                SeedQueue(
                    seeds = seeds,
                    selectedId = (shown as? InspectorSelection.Seed)?.seedId,
                    onSelect = { selection = InspectorSelection.Seed(it) },
                    onFill = onFillSeeds,
                    onClear = onClearSeeds,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            PanelResizeHandle { deltaPx ->
                // Drag right (positive delta) to widen the left column.
                leftWidth = (left + with(density) { deltaPx.toDp() }).coerceIn(QueuePanelWidth, maxLeft)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (shown) {
                    is InspectorSelection.Hold -> {
                        val paused = flows.first { it.correlationId == shown.correlationId }
                        // Re-key on the hold's id so switching rows rebuilds the editor's seeded fields
                        // from that message; [tab] lives above the key, so the chosen tab carries across.
                        key(paused.correlationId) {
                            PausedFlowEditor(
                                paused = paused,
                                tab = tab,
                                onTabChange = { tab = it },
                                onResume = onResume,
                                onAbort = onAbort,
                            )
                        }
                    }
                    is InspectorSelection.Seed -> {
                        val index = seeds.indexOfFirst { it.id == shown.seedId }
                        key(shown.seedId) {
                            SeedDetail(
                                seed = seeds[index],
                                position = index + 1,
                                tab = tab,
                                onTabChange = { tab = it },
                                onLoadBody = onLoadSeedBody,
                            )
                        }
                    }
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // The window outlives its holds now, so the empty state has to say something: with
                        // nothing waiting, this is the arm-the-seeds-and-wait view.
                        MutedText("Nothing paused. Matching seeds answer holds automatically.")
                    }
                }
            }
        }
    }
}

// What the right pane is showing. Holds and seeds share the pane (and the same accent-bar selection in
// their lists), but a hold is editable and resolvable while a seed is a preview of a canned answer.
private sealed interface InspectorSelection {
    data class Hold(val correlationId: String) : InspectorSelection

    data class Seed(val seedId: String) : InspectorSelection
}

private fun holdSelection(flow: PausedFlow) = InspectorSelection.Hold(flow.correlationId)

// The left column's floor — the width the queue was fixed at before it became draggable — and the room
// the editor beside it needs to stay usable (its header row of fields is the binding constraint).
private val QueuePanelWidth = 264.dp
private val MinEditorWidth = 320.dp

// A section dragged to its floor still shows its header plus a row or two, so the drag never collapses a
// list into a bare title bar.
private val MinSectionHeight = 120.dp

/**
 * The Waiting list: every hold that needs a human, in the engine's order. A row names the phase, the
 * method + URL, and the originating device/app; the selected row carries a leading accent bar plus a fill.
 * Selection is the only row action — resolving (Resume/Abort) happens in the editor, so the destructive
 * Abort is always an explicit, deliberate click rather than a stray tap on a list.
 */
@Composable
private fun PausedFlowQueue(
    flows: List<PausedFlow>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    SectionColumn(modifier, title = "Waiting", count = flows.size) {
        if (flows.isEmpty()) {
            SectionEmptyText("Nothing paused.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(flows, key = { it.correlationId }) { flow ->
                    PausedFlowRow(
                        flow = flow,
                        selected = flow.correlationId == selectedId,
                        onSelect = { onSelect(flow.correlationId) },
                    )
                    RowDivider()
                }
            }
            VerticalListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/**
 * The Seed list: the armed queue of canned responses, in the order they'll be spent (ADR-0041). Selecting
 * a row previews it in the pane on the right; a seed is authored in the Seed panel, so the only actions
 * here are arming the queue and emptying it. Fill replaces the queue with the enabled seeds, flattened out
 * of their groups, so re-filling mid-session is how you reset a partly-spent sequence.
 */
@Composable
private fun SeedQueue(
    seeds: List<SeedRuleDef>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onFill: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    SectionColumn(
        modifier,
        title = "Seed",
        count = seeds.size,
        actions = {
            PanelIconButton(Res.drawable.ic_sync_arrow_down, "Fill from seed rules", onFill)
            PanelIconButton(Res.drawable.ic_delete, "Clear seed queue", onClear, enabled = seeds.isNotEmpty())
        },
    ) {
        if (seeds.isEmpty()) {
            SectionEmptyText("No seeds armed. Fill to load the enabled seed rules, in order.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(seeds, key = { _, seed -> seed.id }) { index, seed ->
                    SeedRow(
                        position = index + 1,
                        seed = seed,
                        selected = seed.id == selectedId,
                        onSelect = { onSelect(seed.id) },
                    )
                    RowDivider()
                }
            }
            VerticalListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

// One section of the left column: a top bar carrying the title, the count, and any actions, over a list
// body on the base surface — the same white/near-black every other list in the studio sits on, so the
// rows read as content rather than as chrome (ADR-0016).
@Composable
private fun SectionColumn(
    modifier: Modifier,
    title: String,
    count: Int,
    actions: @Composable RowScope.() -> Unit = {},
    body: @Composable BoxScope.() -> Unit,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth()
                .height(TopBarHeight)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            actions()
        }
        RowDivider()
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), content = body)
    }
}

// An armed seed: its 1-based position (the order is the whole point — position 1 answers the next match
// it fits), the URL it matches, and the method/status it answers with. Two lines like the Waiting rows
// beside it: this column is narrow and draggable narrower, and one line left the URL no room to read.
@Composable
private fun SeedRow(position: Int, seed: SeedRuleDef, selected: Boolean, onSelect: () -> Unit) {
    SelectableRow(selected = selected, onSelect = onSelect) {
        Text(
            position.toString(),
            Modifier.widthIn(min = 16.dp),
            style = monoLabel(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                seed.urlPattern.ifBlank { "(no pattern)" },
                style = monoSmall(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${seed.method.ifBlank { "ANY" }}  \u2192  ${seed.statusCode}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PausedFlowRow(flow: PausedFlow, selected: Boolean, onSelect: () -> Unit) {
    SelectableRow(selected = selected, onSelect = onSelect) {
        PhaseBadge(flow.phase == BreakpointPhase.BREAKPOINT_PHASE_REQUEST)
        Column(Modifier.weight(1f)) {
            Text(
                "${flow.request?.method ?: ""} ${flow.request?.url ?: ""}".trim(),
                style = monoSmall(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${flow.deviceName} \u00b7 ${flow.appId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// The row scaffold both left-column lists use, so a hold and a seed read as the same kind of pick: a
// leading accent bar — the primary at-a-glance selected marker, since the fill alone reads too subtly in
// this monochrome theme — over a surfaceVariant fill. The bar is transparent when unselected so the row's
// left edge stays flush.
@Composable
private fun SelectableRow(selected: Boolean, onSelect: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp)
                .fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Row(
            Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

// A compact chip telling a queue row apart at a glance: which side of the exchange is held.
@Composable
private fun PhaseBadge(isRequest: Boolean) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            if (isRequest) "REQ" else "RESP",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The editor for one held exchange, filling the window's right pane. It seeds an editable copy of the
 * held message; its two actions release the hold — Resume applies the edits (or leaves the message
 * unchanged) and Abort fails the app's call. [tab]/[onTabChange] are hoisted so the chosen tab persists
 * across holds (see [BreakpointInspector]).
 *
 * A REQUEST-phase hit edits the outgoing request (method, URL, headers, body); a RESPONSE-phase hit edits
 * the response the app will see (status code, headers, body) and shows the originating request read-only
 * for context. Headers are edited as `Name: Value` lines; the body uses the shared code editor. Bodies
 * are treated as UTF-8 text — editing a binary body is not a goal of this surface.
 */
@Composable
private fun PausedFlowEditor(
    paused: PausedFlow,
    tab: PausedTab,
    onTabChange: (PausedTab) -> Unit,
    onResume: (correlationId: String, editedRequest: HttpRequest?, editedResponse: HttpResponse?) -> Unit,
    onAbort: (correlationId: String) -> Unit,
) {
    val isRequest = paused.phase == BreakpointPhase.BREAKPOINT_PHASE_REQUEST
    val request = paused.request
    val response = paused.response

    // Editable fields + hoisted editors, keyed by the hold's id so moving to the next paused item
    // re-seeds them from that message rather than keeping the previous one's edits.
    var method by remember(paused.correlationId) { mutableStateOf(request?.method ?: "GET") }
    var url by remember(paused.correlationId) { mutableStateOf(request?.url ?: "") }
    var statusCode by remember(paused.correlationId) { mutableStateOf((response?.code ?: 200).toString()) }

    val headerEditor = remember(paused.correlationId) {
        CodeEditorState(headersToText(if (isRequest) request?.headers else response?.headers))
    }
    val bodyEditor = remember(paused.correlationId) {
        // Seed with the beautified body (pretty-printed JSON) when our beautifier can, else the raw text —
        // the same treatment the read-only BodyPreview gives captured bodies. prettyPrintJson only reflows
        // whitespace outside string literals, so editing and resuming a formatted body changes its layout,
        // never its content.
        val raw = (if (isRequest) request?.body else response?.body)?.utf8() ?: ""
        CodeEditorState(prettyPrintJson(raw) ?: raw)
    }

    val resume = {
        val bodyBytes = bodyEditor.currentText().encodeToByteArray()
        val headers = parseHeaders(headerEditor.currentText())
        if (isRequest) {
            onResume(
                paused.correlationId,
                HttpRequest(
                    method = method.trim().ifBlank { "GET" },
                    url = url.trim(),
                    body = bodyBytes.toByteString(),
                    body_size = bodyBytes.size.toLong(),
                    body_truncated = false,
                    headers = headers,
                ),
                null,
            )
        } else {
            onResume(
                paused.correlationId,
                null,
                HttpResponse(
                    code = statusCode.trim().toIntOrNull() ?: response?.code ?: 200,
                    message = response?.message ?: "",
                    body = bodyBytes.toByteString(),
                    body_size = bodyBytes.size.toLong(),
                    body_truncated = false,
                    headers = headers,
                ),
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .height(TopBarHeight)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isRequest) "Paused request" else "Paused response",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${paused.deviceName} \u00b7 ${paused.appId}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RowDivider()

        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isRequest) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabeledField("Method") {
                        MethodDropdown(method = method, onSelect = { method = it })
                    }
                    LabeledField("URL", Modifier.weight(1f)) {
                        CompactOutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = "https://\u2026",
                        )
                    }
                }
            } else {
                // The response editor shows which request produced it (read-only) so it's clear
                // what's being answered, then the editable status code.
                MutedText("${request?.method ?: ""} ${request?.url ?: ""}".trim())
                LabeledField("Status code", Modifier.widthIn(min = 96.dp)) {
                    CompactOutlinedTextField(
                        value = statusCode,
                        onValueChange = { next -> statusCode = next.filter { it.isDigit() }.take(3) },
                        placeholder = "200",
                    )
                }
            }
        }

        UnderlineTabs(
            items = listOf(
                TabItem(PausedTab.Headers, PausedTab.Headers.label),
                TabItem(PausedTab.Body, PausedTab.Body.label),
            ),
            selected = tab,
            onSelect = onTabChange,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                PausedTab.Headers -> CodeEditor(
                    state = headerEditor,
                    language = CodeLanguage.PlainText,
                    readOnly = false,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                PausedTab.Body -> CodeEditor(
                    state = bodyEditor,
                    language = CodeLanguage.Json,
                    readOnly = false,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        RowDivider()
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onAbort(paused.correlationId) }) {
                Text("Abort")
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = resume) {
                Text("Resume")
            }
        }
    }
}

/**
 * The read-only counterpart to [PausedFlowEditor], filling the same pane for a selected seed: the match it
 * answers, the response it will hand back, and its stored body. It shares [tab] with the editor so
 * stepping between a hold and a seed stays on Headers or Body rather than snapping back.
 *
 * Nothing here is editable and there are no actions — a seed is authored in the Seed panel, and it is
 * spent by a matching hold arriving, not by anything the user does in this window. [position] is its place
 * in the queue, which is what decides *which* seed answers when several match.
 */
@Composable
private fun SeedDetail(
    seed: SeedRuleDef,
    position: Int,
    tab: PausedTab,
    onTabChange: (PausedTab) -> Unit,
    onLoadBody: suspend (SeedRuleDef) -> ByteArray,
) {
    // The body lives in a host file, so it's read asynchronously; null is "not read yet", which the
    // previewer reports as such rather than flashing a wrong "No body".
    var body by remember(seed.id) { mutableStateOf<ByteString?>(null) }
    LaunchedEffect(seed.id) { body = onLoadBody(seed).toByteString() }
    val contentType = seed.headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .height(TopBarHeight)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Seed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(
                "#$position",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RowDivider()

        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                seed.urlPattern.ifBlank { "(no pattern)" },
                style = monoSmall(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            MutedText("${seed.method.ifBlank { "ANY" }}  \u2192  ${seed.statusCode}")
            // Says why the pane has no buttons, and names the one phase a seed can answer (ADR-0041).
            MutedText("Answers the first response hold it matches, then leaves the queue. Edit it in the Seed panel.")
        }

        UnderlineTabs(
            items = listOf(
                TabItem(PausedTab.Headers, PausedTab.Headers.label),
                TabItem(PausedTab.Body, PausedTab.Body.label),
            ),
            selected = tab,
            onSelect = onTabChange,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                PausedTab.Headers -> Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (seed.headers.isEmpty()) MutedText("No headers")
                    else seed.headers.forEach { KeyValueRow(it.name, it.value) }
                }
                // Reuses the traffic previewer, so an image or binary seed body is shown as itself rather
                // than as mojibake — a seed can serve any payload Map Local can.
                // A seed's body is authored here, so it is entirely in memory: what was fetched and what
                // exists are the same number.
                PausedTab.Body -> BodyPreview(
                    body = body,
                    capturedSize = body?.size?.toLong() ?: 0L,
                    contentType = contentType,
                    declaredSize = body?.size?.toLong() ?: 0L,
                    truncated = false,
                    modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
                )
            }
        }
    }
}

private enum class PausedTab(val label: String) { Headers("Headers"), Body("Body") }

// The HTTP methods a paused request can be re-pointed at. Unlike the rule editors' picker there is no
// blank "Any" — a concrete request always has a concrete method (mirrors the Map Local / breakpoint-rule
// dropdowns otherwise; kept file-local since its option set differs).
private val RequestMethodOptions = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

/**
 * Single-select HTTP method picker for the paused request, matching the rule editors' dropdown (a plain
 * field + [DropdownMenu], so the value can't be free-typed and the menu anchors to it). The held request's
 * own method is folded into the options so an exotic verb (e.g. PROPFIND) stays visible and selectable.
 */
@Composable
private fun MethodDropdown(method: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val options = remember(method) {
        if (method.isBlank() || method in RequestMethodOptions) RequestMethodOptions
        else listOf(method) + RequestMethodOptions
    }
    Box {
        // Render as a read-only compact field: the exact decoration the text fields use, so it lines up
        // with the URL/status fields by construction. It wraps to its content so the field hugs the verb.
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(interactionSource = interactionSource, indication = null) { expanded = true },
        ) {
            CompactFieldDecoration(
                value = method,
                interactionSource = interactionSource,
                trailingIcon = {
                    Icon(
                        vectorResource(Res.drawable.ic_arrow_drop_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                innerTextField = {
                    Text(
                        method,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

// A label over its field, matching the Map Local editor's field spacing.
@Composable
private fun LabeledField(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

// Headers <-> the editable `Name: Value` text. Serialization keeps insertion order; parsing splits on
// the first colon (values can contain colons, e.g. a URL), trims both sides, and drops blank lines.
private fun headersToText(headers: List<Header>?): String =
    headers.orEmpty().joinToString("\n") { "${it.name}: ${it.value_}" }

private fun parseHeaders(text: String): List<Header> =
    text.lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val idx = trimmed.indexOf(':')
        if (idx < 0) {
            Header(name = trimmed, value_ = "")
        } else {
            Header(name = trimmed.substring(0, idx).trim(), value_ = trimmed.substring(idx + 1).trim())
        }
    }
