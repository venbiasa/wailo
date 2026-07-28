package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.shared.PausedFlow
import com.venbiasa.wailo.shared.format.prettyPrintJson
import okio.ByteString.Companion.toByteString

/**
 * The paused-traffic editor: a modal overlay shown while a device is holding a request/response at a
 * breakpoint (ADR-0027). It seeds an editable copy of the held message, and its two actions release the
 * hold — Resume applies the edits (or leaves the message unchanged) and Abort fails the app's call. It is
 * deliberately decision-forcing: there is no dismiss, because the device is blocked until one of the two
 * actions is taken (or the link drops, which fails open on the device side). One item is edited at a
 * time; if several are paused at once, resolving this one reveals the next.
 *
 * A REQUEST-phase hit edits the outgoing request (method, URL, headers, body); a RESPONSE-phase hit edits
 * the response the app will see (status code, headers, body) and shows the originating request read-only
 * for context. Headers are edited as `Name: Value` lines; the body uses the shared code editor. Bodies
 * are treated as UTF-8 text — editing a binary body is not a goal of this surface.
 */
@Composable
internal fun BreakpointEditor(
    paused: PausedFlow,
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
    var tab by remember(paused.correlationId) { mutableStateOf(PausedTab.Body) }

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

    // Full-window scrim: darkens the app and swallows taps so the traffic behind it is inert while a
    // hold is open (the editor card swallows its own taps so a click inside it doesn't dismiss focus).
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.fillMaxWidth(0.88f)
                .fillMaxHeight(0.86f)
                .widthIn(max = 920.dp)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.background,
            shadowElevation = 8.dp,
        ) {
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
                            LabeledField("Method", Modifier.widthIn(min = 96.dp)) {
                                CompactOutlinedTextField(
                                    value = method,
                                    onValueChange = { method = it },
                                    placeholder = "GET",
                                )
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
                    onSelect = { tab = it },
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
    }
}

private enum class PausedTab(val label: String) { Headers("Headers"), Body("Body") }

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
