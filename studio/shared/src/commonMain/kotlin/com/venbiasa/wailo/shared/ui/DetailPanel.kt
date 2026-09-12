package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.shared.BodyHandle
import com.venbiasa.wailo.shared.LocalBodySaver
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.ProxySetupAction
import com.venbiasa.wailo.shared.ProxyState
import com.venbiasa.wailo.shared.format.BodyContent
import com.venbiasa.wailo.shared.format.UrlPart
import com.venbiasa.wailo.shared.format.basicAuthDecoded
import com.venbiasa.wailo.shared.format.bodyContent
import com.venbiasa.wailo.shared.format.bodyFileBaseName
import com.venbiasa.wailo.shared.format.bodyFileName
import com.venbiasa.wailo.shared.format.contentEncoding
import com.venbiasa.wailo.shared.format.contentType
import com.venbiasa.wailo.shared.format.formatBytes
import com.venbiasa.wailo.shared.format.requestHost
import com.venbiasa.wailo.shared.format.statusChipText
import com.venbiasa.wailo.shared.format.statusKind
import com.venbiasa.wailo.shared.format.urlSegments
import com.venbiasa.wailo.shared.isLockedProxyTunnel
import com.venbiasa.wailo.shared.theme.LocalWailoColors
import com.venbiasa.wailo.shared.toggleExactHost
import okio.ByteString

@Composable
internal fun DetailPanel(
    entry: FlowEntry,
    proxy: ProxyState,
    onProxySetupAction: (ProxySetupAction) -> Unit,
    modifier: Modifier,
    onClose: () -> Unit,
) {
    val exchange = entry.exchange
    val lockedHost = if (entry.isLockedProxyTunnel()) {
        requestHost(exchange.request?.url.orEmpty()).ifEmpty { null }
    } else {
        null
    }
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        DetailHeader(exchange, onClose)
        RowDivider()
        RequestResponseSplit(
            exchange = exchange,
            requestBody = entry.requestBody,
            responseBody = entry.responseBody,
            lockedHost = lockedHost,
            proxy = proxy,
            onProxySetupAction = onProxySetupAction,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

// Below this the header can't keep a readable URL beside the status/method chips, so the URL drops to
// its own full-width line under a compact chip+close row instead of being squeezed into a thin ribbon.
private val HeaderStackWidth = 440.dp

@Composable
private fun DetailHeader(exchange: HttpExchange, onClose: () -> Unit) {
    val request = exchange.request
    val response = exchange.response
    val method = request?.method?.ifEmpty { "?" } ?: "?"
    val hasError = exchange.error.isNotEmpty()
    val kind = statusKind(response?.code, hasError)
    val status = statusColor(kind)
    // The status hue rides in as a low-opacity tint (a badge, not a slab); heavier on dark, where the
    // hue is lighter and low alpha would wash out. onSurface luminance stands in for "is dark theme".
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    // The three pieces, defined once so the wide (inline) and narrow (stacked) headers place identical
    // chips and URL.
    val statusPill: @Composable () -> Unit = {
        Pill(
            text = statusChipText(response?.code, response?.message ?: "", hasError),
            container = status.copy(alpha = if (dark) 0.22f else 0.14f),
            content = status,
            textStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
    // Same pill, neutral: a faint ink tint so it reads as a chip on the panel without a hue.
    val methodPill: @Composable () -> Unit = {
        Pill(
            text = method.uppercase(),
            container = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            textStyle = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
    val url = request?.url
    val urlText: @Composable (Modifier) -> Unit = { urlModifier ->
        SelectionContainer(urlModifier) {
            Text(
                if (url.isNullOrBlank()) AnnotatedString("(no URL)") else urlAnnotated(url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (maxWidth < HeaderStackWidth) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    statusPill()
                    methodPill()
                    Spacer(Modifier.weight(1f))
                    CloseButton(onClose, contentDescription = "Close detail")
                }
                urlText(Modifier.fillMaxWidth())
            }
        } else {
            // Top-aligned so the chips stay beside the URL's first line when a long URL wraps, rather
            // than floating in the vertical center of a tall multi-line block. The chips carry 4.dp of
            // vertical padding, so the same top inset drops the URL's first line onto the chips' text.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                statusPill()
                methodPill()
                urlText(Modifier.weight(1f).padding(top = 4.dp))
                CloseButton(onClose, contentDescription = "Close detail")
            }
        }
    }
}

// A small, inert stadium pill (no click/ripple) — a filled tint conveys meaning through color alone.
@Composable
private fun Pill(text: String, container: Color, content: Color, textStyle: TextStyle) {
    Box(
        Modifier.clip(CircleShape).background(container).padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = textStyle, color = content, maxLines = 1)
    }
}

/**
 * Syntax-highlights the URL so the eye lands on the parts that matter: the host is the strong ink,
 * path/query/fragment each take a hue (blue/green/amber), and the scheme and punctuation recede. Hues
 * reuse the app's semantic tokens rather than introducing a new palette; the weakest-contrast one
 * (amber) rides on the rarest part (fragment).
 */
@Composable
internal fun urlAnnotated(url: String): AnnotatedString {
    val wailo = LocalWailoColors.current
    return buildAnnotatedString {
        urlSegments(url).forEach { segment ->
            val color = when (segment.part) {
                UrlPart.Scheme -> MaterialTheme.colorScheme.onSurfaceVariant
                UrlPart.Separator -> MaterialTheme.colorScheme.onSurfaceVariant
                UrlPart.Host -> MaterialTheme.colorScheme.onSurface
                UrlPart.Port -> MaterialTheme.colorScheme.onSurfaceVariant
                UrlPart.Path -> wailo.info
                UrlPart.Query -> wailo.success
                UrlPart.Fragment -> wailo.warning
            }
            withStyle(SpanStyle(color = color)) { append(segment.text) }
        }
    }
}

/**
 * The request and response, laid out to fit the width. Wide enough, they sit side by side divided by a
 * draggable handle (the split is stored as a fraction so it survives window resizes and stays put while
 * stepping through rows); once the panel is dragged too narrow for two readable columns, they collapse
 * to a single pane at a time behind a Request/Response toggle so each keeps the full width. Both the
 * split fraction and the toggle choice live across that switch, so widening back restores the drag.
 */
@Composable
private fun RequestResponseSplit(
    exchange: HttpExchange,
    requestBody: BodyHandle?,
    responseBody: BodyHandle?,
    lockedHost: String?,
    proxy: ProxyState,
    onProxySetupAction: (ProxySetupAction) -> Unit,
    modifier: Modifier,
) {
    val request = exchange.request
    val response = exchange.response
    val method = request?.method?.ifEmpty { "?" } ?: "?"
    // Both panes name a saved body after the same request, since that is what the two are halves of.
    val fileBaseName = remember(request?.url) { bodyFileBaseName(request?.url.orEmpty()) }

    // Defined once and placed by whichever layout fits, so the side-by-side and the narrow single-pane
    // views can never drift apart in what they show.
    val requestPane: @Composable (Modifier) -> Unit = { paneModifier ->
        MessagePane(
            caption = "Request",
            present = request != null,
            startLine = "$method ${request?.url ?: ""}".trim(),
            headers = request?.headers ?: emptyList(),
            body = requestBody,
            declaredSize = request?.body_size ?: 0L,
            truncated = request?.body_truncated == true,
            fileBaseName = fileBaseName,
            notice = "No request captured.",
            showAuth = true,
            // What someone pulls out of a capture is what came back. A request body is usually the few
            // bytes they typed, and a second download here would cost the pane header on both sides to
            // serve the rarer half.
            offerDownload = false,
            modifier = paneModifier,
        )
    }
    val responsePane: @Composable (Modifier) -> Unit = { paneModifier ->
        MessagePane(
            caption = "Response",
            present = response != null,
            startLine = responseStartLine(response?.code, response?.message ?: ""),
            headers = response?.headers ?: emptyList(),
            body = responseBody,
            declaredSize = response?.body_size ?: 0L,
            truncated = response?.body_truncated == true,
            fileBaseName = fileBaseName,
            notice = if (exchange.error.isNotEmpty()) {
                "Request failed before a response: ${exchange.error}"
            } else {
                "No response captured yet."
            },
            // Auth is a request-side concern (credentials the client sends); the response only
            // echoes Set-Cookie/challenge headers, which read fine under Headers.
            showAuth = false,
            offerDownload = true,
            lockedResponse = lockedHost?.let { host ->
                LockedResponseState(
                    host = host,
                    certificateExists = proxy.caInstalled,
                    exactHostUnlocked = host in proxy.decryptHosts,
                )
            },
            onCreateCertificate = {
                onProxySetupAction(ProxySetupAction.InstallCertificate)
            },
            onUnlockHost = { host ->
                onProxySetupAction(
                    ProxySetupAction.SetDecryptHosts(proxy.decryptHosts.toggleExactHost(host)),
                )
            },
            modifier = paneModifier,
        )
    }

    // Held above the width switch so a dragged split and the Request/Response choice both survive
    // crossing the breakpoint (and stepping through rows).
    var leftFraction by remember { mutableStateOf(0.5f) }
    var side by remember { mutableStateOf(DetailSide.Response) }
    BoxWithConstraints(modifier) {
        if (maxWidth < SideBySideMinWidth) {
            Column(Modifier.fillMaxSize()) {
                RequestResponseToggle(
                    selected = side,
                    onSelect = { side = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (side) {
                        DetailSide.Request -> requestPane(Modifier.fillMaxSize())
                        DetailSide.Response -> responsePane(Modifier.fillMaxSize())
                    }
                }
            }
        } else {
            val totalPx = constraints.maxWidth.toFloat()
            Row(Modifier.fillMaxSize()) {
                requestPane(Modifier.weight(leftFraction).fillMaxHeight())
                PaneResizeHandle { deltaPx ->
                    if (totalPx > 0f) leftFraction = (leftFraction + deltaPx / totalPx).coerceIn(0.2f, 0.8f)
                }
                responsePane(Modifier.weight(1f - leftFraction).fillMaxHeight())
            }
        }
    }
}

// Below this the two panes would each be a squeezed sliver, so the split collapses to one toggled pane.
// Sized so each side-by-side pane stays wide enough for its tabs and a readable key/value column.
private val SideBySideMinWidth = 520.dp

private enum class DetailSide(val label: String) {
    Request("Request"),
    Response("Response"),
}

// The narrow-layout switch between the request and response panes: a full-width segmented control
// styled like the body previewer's switch (BodyPreview), so the app's toggles read consistently — and
// deliberately distinct from the UnderlineTabs below it, which pick the view *within* a pane.
@Composable
private fun RequestResponseToggle(
    selected: DetailSide,
    onSelect: (DetailSide) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DetailSide.entries.forEach { detailSide ->
            val isSelected = detailSide == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(detailSide) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    detailSide.label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun responseStartLine(code: Int?, message: String): String =
    listOfNotNull(code?.takeIf { it > 0 }?.toString(), message.ifBlank { null }).joinToString(" ")

// A static hairline seam with a wide, invisible grab strip. The resize cursor on hover is the only
// affordance — no hover emphasis on the line itself, keeping the split quiet.
@Composable
private fun PaneResizeHandle(onDragDelta: (Float) -> Unit) {
    Box(
        Modifier.fillMaxHeight()
            .width(11.dp)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { onDragDelta(it) },
            )
            .resizeCursor(ResizeAxis.Horizontal),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

private enum class MessageTab(val label: String) {
    Headers("Headers"),
    Body("Body"),
    Auth("Auth"),
    Raw("Raw"),
}

private data class LockedResponseState(
    val host: String,
    val certificateExists: Boolean,
    val exactHostUnlocked: Boolean,
)

@Composable
private fun MessagePane(
    caption: String,
    present: Boolean,
    startLine: String,
    headers: List<Header>,
    body: BodyHandle?,
    declaredSize: Long,
    truncated: Boolean,
    fileBaseName: String,
    notice: String,
    showAuth: Boolean,
    offerDownload: Boolean,
    lockedResponse: LockedResponseState? = null,
    onCreateCertificate: () -> Unit = {},
    onUnlockHost: (String) -> Unit = {},
    modifier: Modifier,
) {
    Column(modifier) {
        if (!present && lockedResponse == null) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        val tabs = remember(showAuth) {
            MessageTab.entries.filter { showAuth || it != MessageTab.Auth }
        }
        var tab by remember { mutableStateOf(MessageTab.Headers) }
        // Fetched only for the two tabs that show bytes: opening a row on Headers should cost headers.
        // Switching away and back re-reads, which is a local socket and a decrypt, not a network trip.
        val shownBody = body.takeIf {
            lockedResponse == null && (tab == MessageTab.Body || tab == MessageTab.Raw)
        }
        val bytes = rememberBodyBytes(shownBody)
        val capturedSize = body?.size ?: 0L
        // Where the host can write files, a body can leave Studio; where it can't there is no control at
        // all, rather than one nothing comes of. Offered only where bytes are actually on screen, so the
        // Headers and Auth tabs don't carry an action that means nothing there.
        val saver = LocalBodySaver.current
        var saveFailure by remember(bytes) { mutableStateOf("") }
        UnderlineTabs(
            items = tabs.map { TabItem(it, it.label) },
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth(),
            trailingLabel = caption.uppercase(),
            trailing = if (offerDownload && saver != null && bytes != null && bytes.size > 0) {
                {
                    SaveBodyButton(
                        body = bytes,
                        handle = shownBody,
                        fileName = bodyFileName(
                            base = fileBaseName,
                            body = bytes,
                            contentType = headers.contentType(),
                            truncated = truncated,
                            contentEncoding = headers.contentEncoding(),
                        ),
                        saver = saver,
                        onFailure = { saveFailure = it },
                    )
                }
            } else {
                null
            },
        )
        if (saveFailure.isNotEmpty()) {
            MutedText(saveFailure, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The Body tab owns its own scrolling: its previewers include a lazy hex dump and a
            // centered image, neither of which can live inside the shared vertical scroll the
            // text-based tabs use.
            when {
                lockedResponse != null -> {
                    LockedResponseContent(
                        state = lockedResponse,
                        onCreateCertificate = onCreateCertificate,
                        onUnlockHost = onUnlockHost,
                    )
                }
                tab == MessageTab.Body -> {
                    BodyPreview(
                        body = bytes,
                        capturedSize = capturedSize,
                        contentType = headers.contentType(),
                        declaredSize = declaredSize,
                        truncated = truncated,
                        contentEncoding = headers.contentEncoding(),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> {
                    SelectionContainer {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            when (tab) {
                                MessageTab.Headers -> HeadersContent(headers)
                                MessageTab.Auth -> AuthContent(headers)
                                MessageTab.Raw ->
                                    RawContent(startLine, headers, bytes, capturedSize, declaredSize, truncated)
                                MessageTab.Body -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LockedResponseContent(
    state: LockedResponseState,
    onCreateCertificate: () -> Unit,
    onUnlockHost: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            when {
                !state.certificateExists ->
                    "This tunnel was captured encrypted. Create and trust the local root, unlock ${state.host}, " +
                        "then send a new request. This historical row cannot be decrypted."
                state.exactHostUnlocked ->
                    "${state.host} is unlocked now. This historical tunnel remains encrypted; send a " +
                        "new request to capture its headers and body."
                else ->
                    "This tunnel was captured encrypted. Unlock ${state.host}, then send a new request. " +
                        "This historical row cannot be decrypted."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        when {
            !state.certificateExists -> {
                Button(onClick = onCreateCertificate) {
                    Text("Create certificate")
                }
            }
            !state.exactHostUnlocked -> {
                Button(onClick = { onUnlockHost(state.host) }) {
                    Text("Unlock host")
                }
            }
        }
    }
}

@Composable
private fun HeadersContent(headers: List<Header>) {
    if (headers.isEmpty()) {
        MutedText("No headers")
    } else {
        headers.forEach { KeyValueRow(it.name, it.value_) }
    }
}

private val AuthHeaderNames = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "set-cookie",
    "www-authenticate",
    "proxy-authenticate",
)

@Composable
private fun AuthContent(headers: List<Header>) {
    val auth = headers.filter { it.name.lowercase() in AuthHeaderNames }
    if (auth.isEmpty()) {
        MutedText("No authentication headers")
        return
    }
    auth.forEach { header ->
        KeyValueRow(header.name, header.value_)
        basicAuthDecoded(header.name, header.value_)?.let { decoded ->
            KeyValueRow("↳ decoded", decoded)
        }
    }
}

@Composable
private fun RawContent(
    startLine: String,
    headers: List<Header>,
    body: ByteString?,
    capturedSize: Long,
    declaredSize: Long,
    truncated: Boolean,
) {
    val contentType = headers.contentType()
    val text = remember(startLine, headers, body, contentType) {
        buildString {
            if (startLine.isNotBlank()) appendLine(startLine)
            headers.forEach { appendLine("${it.name}: ${it.value_}") }
            when (val content = body?.let { bodyContent(it, contentType) }) {
                null, BodyContent.Empty -> Unit
                is BodyContent.Binary -> {
                    appendLine()
                    append("⟨ binary • ${formatBytes(content.size.toLong())} ⟩")
                }
                is BodyContent.Text -> {
                    appendLine()
                    append(content.text)
                }
            }
        }
    }
    // The start line and headers are already in hand and render immediately, so the notice has to name
    // which half of the document is still outstanding.
    if (body == null) {
        BodyLoadingNotice()
        Spacer(Modifier.height(4.dp))
    }
    if (truncated) {
        MutedText("(truncated during capture • declared ${formatBytes(declaredSize)})")
        Spacer(Modifier.height(4.dp))
    }
    if (body != null && capturedSize > body.size) {
        MutedText("(showing the first ${formatBytes(body.size.toLong())} of ${formatBytes(capturedSize)})")
        Spacer(Modifier.height(4.dp))
    }
    Text(
        text,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        style = monoSmall(),
        color = MaterialTheme.colorScheme.onSurface,
        softWrap = false,
    )
}
