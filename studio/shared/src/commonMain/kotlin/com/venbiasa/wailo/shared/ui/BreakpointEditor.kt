package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.shared.PausedFlow
import com.venbiasa.wailo.shared.format.prettyPrintJson
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import okio.ByteString.Companion.toByteString
import org.jetbrains.compose.resources.vectorResource

/**
 * The paused-traffic inspector: the content of the standalone breakpoint window (ADR-0034), shown while
 * one or more devices are holding a request/response at a breakpoint (ADR-0027). It replaces the old
 * full-window modal — a hold no longer scrims the main viewer; it opens its own window so traffic stays
 * browsable alongside it.
 *
 * A queue panel on the left always lists the waiting holds (even a lone one, so the layout never jumps as
 * the count changes); the editor on the right edits whichever is selected, and the selected row is marked
 * with a leading accent bar. Holds resolve in **any** order — pick any row, then Resume or Abort it; the
 * rest keep waiting. Resolving the selected hold advances the selection to the next; the host closes the
 * window once none remain.
 *
 * The selected editor tab is remembered for the window's lifetime (it defaults to Headers, and a switch to
 * Body sticks as later holds arrive), but it is not persisted — reopening the window starts at Headers again.
 */
@Composable
internal fun BreakpointInspector(
    flows: List<PausedFlow>,
    onResume: (correlationId: String, editedRequest: HttpRequest?, editedResponse: HttpResponse?) -> Unit,
    onAbort: (correlationId: String) -> Unit,
) {
    // Which hold is loaded into the editor — transient window state. Keep it pointing at a live hold:
    // when the selected one resolves (drops out of [flows]) or nothing is selected yet, snap to the
    // first remaining so the editor never shows a stale/blank pane.
    var selectedId by remember { mutableStateOf(flows.firstOrNull()?.correlationId) }
    LaunchedEffect(flows) {
        if (selectedId == null || flows.none { it.correlationId == selectedId }) {
            selectedId = flows.firstOrNull()?.correlationId
        }
    }
    val selected = flows.firstOrNull { it.correlationId == selectedId } ?: flows.firstOrNull()

    // Window-session tab, hoisted above the per-hold editor so it survives switching holds and new holds
    // arriving; it resets to Headers only when the window is next opened (not persisted).
    var tab by remember { mutableStateOf(PausedTab.Headers) }

    Row(Modifier.fillMaxSize()) {
        PausedFlowQueue(
            flows = flows,
            selectedId = selected?.correlationId,
            onSelect = { selectedId = it },
            modifier = Modifier.width(QueuePanelWidth).fillMaxHeight(),
        )
        ColumnDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            selected?.let { paused ->
                // Re-key on the hold's id so switching rows rebuilds the editor's seeded fields from that
                // message; [tab] lives above the key, so the chosen tab carries across the switch.
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
        }
    }
}

private val QueuePanelWidth = 264.dp

/**
 * The left queue: every waiting hold, in the engine's order. A row names the phase, the method + URL, and
 * the originating device/app; the selected row carries a leading accent bar plus a fill. Selection is the
 * only row action — resolving (Resume/Abort) happens in the editor, so the destructive Abort is always an
 * explicit, deliberate click rather than a stray tap on a list.
 */
@Composable
private fun PausedFlowQueue(
    flows: List<PausedFlow>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            Modifier.fillMaxWidth()
                .height(TopBarHeight)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Waiting",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                flows.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RowDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(flows, key = { it.correlationId }) { flow ->
                PausedFlowRow(
                    flow = flow,
                    selected = flow.correlationId == selectedId,
                    onSelect = { onSelect(flow.correlationId) },
                )
                RowDivider()
            }
        }
    }
}

@Composable
private fun PausedFlowRow(flow: PausedFlow, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading accent bar: the primary at-a-glance "this is selected" marker (the fill alone reads too
        // subtly in this monochrome theme). Transparent when unselected so the row's left edge is flush.
        Box(
            Modifier.width(3.dp)
                .fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Row(
            Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
