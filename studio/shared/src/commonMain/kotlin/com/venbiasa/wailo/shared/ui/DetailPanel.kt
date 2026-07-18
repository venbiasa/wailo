package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.format.BodyContent
import com.venbiasa.wailo.shared.format.UrlPart
import com.venbiasa.wailo.shared.format.basicAuthDecoded
import com.venbiasa.wailo.shared.format.bodyContent
import com.venbiasa.wailo.shared.format.contentType
import com.venbiasa.wailo.shared.format.formatBytes
import com.venbiasa.wailo.shared.format.statusChipText
import com.venbiasa.wailo.shared.format.statusKind
import com.venbiasa.wailo.shared.format.urlSegments
import com.venbiasa.wailo.shared.theme.LocalWailoColors
import okio.ByteString

@Composable
internal fun DetailPanel(
    entry: FlowEntry,
    modifier: Modifier,
    onClose: () -> Unit,
) {
    val exchange = entry.exchange
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        DetailHeader(exchange, onClose)
        RowDivider()
        RequestResponseSplit(exchange, Modifier.weight(1f).fillMaxWidth())
    }
}

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

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(
            text = statusChipText(response?.code, response?.message ?: "", hasError),
            container = status.copy(alpha = if (dark) 0.22f else 0.14f),
            content = status,
            textStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        // Same pill, neutral: a faint ink tint so it reads as a chip on the panel without a hue.
        Pill(
            text = method.uppercase(),
            container = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            textStyle = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        )
        val url = request?.url
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                if (url.isNullOrBlank()) AnnotatedString("(no URL)") else urlAnnotated(url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CloseButton(onClose, contentDescription = "Close detail")
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
private fun urlAnnotated(url: String): AnnotatedString {
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
 * Request on the left, response on the right, divided by a draggable handle. The split is stored as a
 * fraction so it survives window resizes and stays put while stepping through rows; each side keeps
 * its own tab selection.
 */
@Composable
private fun RequestResponseSplit(exchange: HttpExchange, modifier: Modifier) {
    val request = exchange.request
    val response = exchange.response
    val method = request?.method?.ifEmpty { "?" } ?: "?"
    BoxWithConstraints(modifier) {
        val totalPx = constraints.maxWidth.toFloat()
        var leftFraction by remember { mutableStateOf(0.5f) }
        Row(Modifier.fillMaxSize()) {
            MessagePane(
                caption = "Request",
                present = request != null,
                startLine = "$method ${request?.url ?: ""}".trim(),
                headers = request?.headers ?: emptyList(),
                body = request?.body ?: ByteString.EMPTY,
                declaredSize = request?.body_size ?: 0L,
                truncated = request?.body_truncated == true,
                notice = "No request captured.",
                showAuth = true,
                modifier = Modifier.weight(leftFraction).fillMaxHeight(),
            )
            PaneResizeHandle { deltaPx ->
                if (totalPx > 0f) leftFraction = (leftFraction + deltaPx / totalPx).coerceIn(0.2f, 0.8f)
            }
            MessagePane(
                caption = "Response",
                present = response != null,
                startLine = responseStartLine(response?.code, response?.message ?: ""),
                headers = response?.headers ?: emptyList(),
                body = response?.body ?: ByteString.EMPTY,
                declaredSize = response?.body_size ?: 0L,
                truncated = response?.body_truncated == true,
                notice = if (exchange.error.isNotEmpty()) {
                    "Request failed before a response: ${exchange.error}"
                } else {
                    "No response captured yet."
                },
                // Auth is a request-side concern (credentials the client sends); the response only
                // echoes Set-Cookie/challenge headers, which read fine under Headers.
                showAuth = false,
                modifier = Modifier.weight(1f - leftFraction).fillMaxHeight(),
            )
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

@Composable
private fun MessagePane(
    caption: String,
    present: Boolean,
    startLine: String,
    headers: List<Header>,
    body: ByteString,
    declaredSize: Long,
    truncated: Boolean,
    notice: String,
    showAuth: Boolean,
    modifier: Modifier,
) {
    Column(modifier) {
        if (!present) {
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
        UnderlineTabs(
            items = tabs.map { TabItem(it, it.label) },
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth(),
            trailingLabel = caption.uppercase(),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The Body tab owns its own scrolling: its previewers include a lazy hex dump and a
            // centered image, neither of which can live inside the shared vertical scroll the
            // text-based tabs use.
            if (tab == MessageTab.Body) {
                BodyPreview(body, headers.contentType(), declaredSize, truncated, Modifier.fillMaxSize())
            } else {
                SelectionContainer {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        when (tab) {
                            MessageTab.Headers -> HeadersContent(headers)
                            MessageTab.Auth -> AuthContent(headers)
                            MessageTab.Raw -> RawContent(startLine, headers, body, declaredSize, truncated)
                            MessageTab.Body -> Unit
                        }
                    }
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
    body: ByteString,
    declaredSize: Long,
    truncated: Boolean,
) {
    val contentType = headers.contentType()
    val text = remember(startLine, headers, body, contentType) {
        buildString {
            if (startLine.isNotBlank()) appendLine(startLine)
            headers.forEach { appendLine("${it.name}: ${it.value_}") }
            when (val content = bodyContent(body, contentType)) {
                BodyContent.Empty -> Unit
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
    if (truncated) {
        MutedText("(truncated during capture • declared ${formatBytes(declaredSize)})")
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
