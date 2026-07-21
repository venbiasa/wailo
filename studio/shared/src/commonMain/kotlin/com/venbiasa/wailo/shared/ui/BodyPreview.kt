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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.format.ImageFormat
import com.venbiasa.wailo.shared.format.PreviewKind
import com.venbiasa.wailo.shared.format.analyzeBody
import com.venbiasa.wailo.shared.format.formatBytes
import com.venbiasa.wailo.shared.format.hexDumpLine
import com.venbiasa.wailo.shared.format.parseFormUrlEncoded
import com.venbiasa.wailo.shared.format.prettyPrintJson
import okio.ByteString
import org.jetbrains.compose.resources.decodeToImageBitmap

// Text/hex are capped so a multi-megabyte payload can't stall the viewer; the raw bytes are still
// on the exchange, and Raw/Hex note when more was captured than is shown.
private const val MaxTextChars = 500_000
private const val MaxHexBytes = 1_000_000

/**
 * Renders a request/response body with the previewer that fits it, chosen by [analyzeBody]. When more
 * than one previewer applies, a compact switch lets the user override (e.g. read an image's bytes as
 * hex, or a JSON body as plain text); the choice re-defaults whenever the body changes so stepping
 * through rows always opens the best view.
 */
@Composable
internal fun BodyPreview(
    body: ByteString,
    contentType: String?,
    declaredSize: Long,
    truncated: Boolean,
    modifier: Modifier = Modifier,
) {
    val analysis = remember(body, contentType, truncated) { analyzeBody(body, contentType, truncated) }
    if (analysis.isEmpty) {
        Box(modifier.padding(16.dp)) { MutedText("No body") }
        return
    }
    var selected by remember(body) { mutableStateOf(analysis.default) }

    Column(modifier) {
        if (analysis.previewers.size > 1) {
            PreviewerSwitch(
                options = analysis.previewers,
                selected = selected,
                onSelect = { selected = it },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
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
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selected) {
                PreviewKind.Json -> JsonCodePreview(body)
                PreviewKind.Html, PreviewKind.Xml, PreviewKind.Text -> TextPreview(body)
                PreviewKind.Form -> FormPreview(body)
                PreviewKind.Hex -> HexPreview(body)
                PreviewKind.Image -> ImagePreview(body, analysis.imageFormat, declaredSize) {
                    selected = PreviewKind.Hex
                }
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
        // the payload is never a dead end.
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
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
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
