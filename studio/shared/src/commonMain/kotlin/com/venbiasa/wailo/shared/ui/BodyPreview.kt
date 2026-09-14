package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.BodyHandle
import com.venbiasa.wailo.shared.BodySaver
import com.venbiasa.wailo.shared.LocalBodyLoader
import com.venbiasa.wailo.shared.prefix
import com.venbiasa.wailo.shared.format.ImageFormat
import com.venbiasa.wailo.shared.format.PreviewKind
import com.venbiasa.wailo.shared.format.analyzeBody
import com.venbiasa.wailo.shared.format.formatBytes
import com.venbiasa.wailo.shared.format.hexDumpLine
import com.venbiasa.wailo.shared.format.parseFormUrlEncoded
import com.venbiasa.wailo.shared.format.prettyPrintJson
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_download
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.ByteString
import org.jetbrains.compose.resources.decodeToImageBitmap

// Text/hex are capped so a multi-megabyte payload can't stall the viewer; Raw/Hex note when more was
// captured than is shown.
private const val MaxTextChars = 500_000
private const val MaxHexBytes = 1_000_000

/** One fetch that satisfies both ceilings above, so switching previewers never goes back to the daemon. */
internal const val PreviewByteLimit = 2 * 1024 * 1024

/**
 * Fetch the part of a captured body a previewer can actually show, and recompose when it lands.
 *
 * A prefix, not the payload: a body is bounded by the disk it was spooled to (ADR-0069), and every
 * previewer below already refuses to render more than a couple of megabytes anyway.
 *
 * Null is "the read has not landed yet", which is a different claim from an empty body and must stay
 * distinguishable: bytes arrive from the daemon's spool a beat after the row that names them, so a
 * viewer that collapses the two announces that a response carried nothing and then contradicts itself.
 * A handle with nothing behind it — no body, or a tab not showing one — starts no fetch, so it resolves
 * to empty here rather than waiting on a read that will never run.
 */
@Composable
internal fun rememberBodyBytes(handle: BodyHandle?, limit: Int = PreviewByteLimit): ByteString? {
    val loader = LocalBodyLoader.current
    val nothingToFetch = handle == null || handle.size <= 0L || limit <= 0
    var bytes by remember(handle, limit) {
        mutableStateOf<ByteString?>(if (nothingToFetch) ByteString.EMPTY else null)
    }
    LaunchedEffect(handle, limit) {
        if (!nothingToFetch) bytes = loader.prefix(handle, limit)
    }
    return bytes
}

// A body read is a loopback socket and a decrypt, so most land within a frame or two — long enough to
// leave a pane blank, too short to read a message in. Withholding the notice for that window keeps the
// common case from flashing copy nobody could finish, while a genuinely large payload still says what it
// is waiting on instead of looking like an empty response.
private const val LoadingNoticeDelayMillis = 150L

/** The stand-in a body view shows while its bytes are still being read (see [rememberBodyBytes]). */
@Composable
internal fun BodyLoadingNotice(text: String = "Loading body…", modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LoadingNoticeDelayMillis)
        visible = true
    }
    if (visible) MutedText(text, modifier)
}

/**
 * Renders a request/response body with the previewer that fits it, chosen by [analyzeBody]. When more
 * than one previewer applies, a compact switch lets the user override (e.g. read an image's bytes as
 * hex, or a JSON body as plain text); the choice re-defaults whenever the body changes so stepping
 * through rows always opens the best view.
 *
 * [body] is the prefix that was fetched and [capturedSize] is how long the body actually is; they differ
 * once a payload is larger than a viewer should hold (ADR-0069), and the difference is shown rather than
 * hidden — "the rest is missing" and "the rest is not on screen yet" are not the same claim. A null
 * [body] is that same distinction one step earlier: nothing has been read yet, so the pane says so
 * instead of reporting the body absent.
 *
 * A [contentEncoding] the bytes still carry means the capture never decoded them, so they are shown as
 * the dump they are, under a notice naming the coding. Without it a brotli response is an unexplained hex
 * dump on a body the headers call JSON, which reads as Wailo having broken the response. The header only
 * raises the question; [analyzeBody] asks the bytes, because a client that decompressed for itself leaves
 * the header behind over plaintext.
 *
 * Saving a body is deliberately not here: it acts on the bytes rather than on a view of them, so it lives
 * in the pane header beside the tabs (see [SaveBodyButton]) and does not move as the user switches
 * previewers.
 */
@Composable
internal fun BodyPreview(
    body: ByteString?,
    capturedSize: Long,
    contentType: String?,
    declaredSize: Long,
    truncated: Boolean,
    contentEncoding: String? = null,
    modifier: Modifier = Modifier,
) {
    if (body == null) {
        Box(modifier.padding(16.dp)) { BodyLoadingNotice() }
        return
    }
    val analysis = remember(body, contentType, truncated, contentEncoding) {
        analyzeBody(body, contentType, truncated, contentEncoding)
    }
    if (analysis.isEmpty) {
        Box(modifier.padding(16.dp)) { MutedText("No body") }
        return
    }
    var selected by remember(body) { mutableStateOf(analysis.default) }
    val partial = capturedSize > body.size

    Column(modifier) {
        if (analysis.previewers.size > 1) {
            PreviewerSwitch(
                options = analysis.previewers,
                selected = selected,
                onSelect = { selected = it },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        // Names the coding rather than the tool's limits first: the reader's question is why this JSON
        // response is a hex dump, and the answer is that nobody has decompressed it yet.
        if (analysis.encoding != null) {
            MutedText(
                "(still ${analysis.encoding}-encoded • Wailo decodes only gzip and deflate)",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        // A truncated body is incomplete; say so once, above whichever text/hex previewer is showing.
        // (Image is never offered for a truncated body, so it needs no notice.)
        if (truncated && selected != PreviewKind.Image) {
            MutedText(
                "(truncated during capture • declared ${formatBytes(declaredSize)})",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        if (partial && selected != PreviewKind.Image) {
            MutedText(
                "(showing the first ${formatBytes(body.size.toLong())} of ${formatBytes(capturedSize)})",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selected) {
                PreviewKind.Json -> JsonCodePreview(body)
                PreviewKind.Html, PreviewKind.Xml, PreviewKind.Text -> TextPreview(body)
                PreviewKind.Form -> FormPreview(body)
                PreviewKind.Hex -> HexPreview(body)
                PreviewKind.Image -> ImagePreview(
                    body = body,
                    format = analysis.imageFormat,
                    declaredSize = declaredSize,
                    onUndecodable = { selected = PreviewKind.Hex },
                )
            }
        }
    }
}

@Composable
private fun TextPreview(body: ByteString) {
    val text = remember(body) { body.decodedText() }
    ScrollableMonoText(text)
}

/**
 * JSON shown in the read-only [CodeEditor] — the same syntax-highlighting, foldable, searchable editor the
 * Map Local panel edits with, but non-editable here because the detail panel views captured traffic and
 * never mutates it. The body is pretty-printed first (falling back to raw text when it doesn't reparse — a
 * truncated body, or a Content-Type that lied); the full raw bytes stay one click away on the Raw tab.
 */
@Composable
private fun JsonCodePreview(body: ByteString) {
    // CodeEditorState takes its document only at construction, so key the state on the body to re-seed it
    // as the user steps through rows.
    val editorState = remember(body) {
        val raw = body.decodedText()
        CodeEditorState(prettyPrintJson(raw) ?: raw)
    }
    CodeEditor(
        state = editorState,
        language = CodeLanguage.Json,
        readOnly = true,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun FormPreview(body: ByteString) {
    val fields = remember(body) { parseFormUrlEncoded(body.decodedText()) }
    if (fields.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp)) { MutedText("No fields") }
        return
    }
    SelectionContainer(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            fields.forEach { (name, value) -> KeyValueRow(name, value) }
        }
    }
}

@Composable
private fun HexPreview(body: ByteString) {
    val limit = minOf(body.size, MaxHexBytes)
    val lineCount = (limit + 15) / 16
    val style = monoSmall()
    // Windowed: a row's 16-byte line is formatted only when it scrolls into view, so a large body
    // doesn't build hundreds of thousands of strings up front.
    SelectionContainer(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(lineCount) { line ->
                Text(
                    hexDumpLine(body, line * 16, limit),
                    style = style,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                )
            }
            if (body.size > limit) {
                item { MutedText("… ${formatBytes((body.size - limit).toLong())} more not shown") }
            }
        }
    }
}

/**
 * Write a captured body to disk, reading it again in full so the file is the payload and not the preview.
 *
 * Lives in the pane header rather than in a previewer: what leaves is the bytes, and every previewer is
 * showing the same ones. Reports only failure, and through [onFailure] rather than its own text, so the
 * line can span the pane instead of squeezing the tab row it sits in — a file the user picked and a
 * dialog they dismissed both explain themselves.
 */
@Composable
internal fun SaveBodyButton(
    body: ByteString,
    handle: BodyHandle?,
    fileName: String,
    saver: BodySaver,
    onFailure: (String) -> Unit,
) {
    val loader = LocalBodyLoader.current
    val scope = rememberCoroutineScope()
    var saving by remember(body) { mutableStateOf(false) }
    PanelIconButton(
        icon = Res.drawable.ic_download,
        contentDescription = "Save body",
        enabled = !saving,
        onClick = {
            saving = true
            scope.launch {
                // On screen is only as much as a previewer needs (PreviewByteLimit), so the body is read
                // again in full rather than saving a file that stops where the view did. What was shown
                // is the fallback, for a body authored in memory (no handle) and for one the daemon has
                // since dropped (nothing to read).
                val full = handle?.let { loader.prefix(it) }
                val bytes = (if (full == null || full.size == 0) body else full).toByteArray()
                onFailure(saver.save(fileName, bytes))
                saving = false
            }
        },
    )
}

@Composable
private fun ImagePreview(
    body: ByteString,
    format: ImageFormat?,
    declaredSize: Long,
    onUndecodable: () -> Unit,
) {
    val bitmap = remember(body) { runCatching { body.toByteArray().decodeToImageBitmap() }.getOrNull() }
    if (bitmap == null) {
        // Sniffed as an image but the decoder refused it (unsupported/corrupt) — fall back to Hex so
        // the payload is never a dead end, even though the switch no longer offers it.
        LaunchedEffect(body) { onUndecodable() }
        Box(Modifier.fillMaxSize().padding(16.dp)) { MutedText("Couldn't decode image; showing raw bytes.") }
        return
    }
    Column(Modifier.fillMaxSize()) {
        val caption = buildString {
            append(format?.displayName() ?: "Image")
            append(" • ${bitmap.width}×${bitmap.height}")
            if (declaredSize > 0) append(" • ${formatBytes(declaredSize)}")
        }
        Text(
            caption,
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            Image(
                bitmap = bitmap,
                contentDescription = "Body image preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

// A quiet segmented control: a rounded track with a filled selection, sitting below the pane's tabs
// as a clearly secondary affordance (distinct from the top UnderlineTabs).
@Composable
private fun PreviewerSwitch(
    options: List<PreviewKind>,
    selected: PreviewKind,
    onSelect: (PreviewKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { kind ->
            val isSelected = kind == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(kind) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    kind.label(),
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

@Composable
private fun ScrollableMonoText(text: String) {
    SelectionContainer(Modifier.fillMaxSize()) {
        Text(
            text,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
            style = monoSmall(),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
        )
    }
}

private fun ByteString.decodedText(): String {
    val raw = utf8()
    return if (raw.length > MaxTextChars) raw.substring(0, MaxTextChars) else raw
}

private fun ImageFormat.displayName(): String = when (this) {
    ImageFormat.Png -> "PNG"
    ImageFormat.Jpeg -> "JPEG"
    ImageFormat.Gif -> "GIF"
    ImageFormat.Webp -> "WebP"
    ImageFormat.Bmp -> "BMP"
}

private fun PreviewKind.label(): String = when (this) {
    PreviewKind.Json -> "JSON"
    PreviewKind.Html -> "HTML"
    PreviewKind.Xml -> "XML"
    PreviewKind.Form -> "Form"
    PreviewKind.Text -> "Text"
    PreviewKind.Image -> "Image"
    PreviewKind.Hex -> "Hex"
}
