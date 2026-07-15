package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.format.ImageFormat
import com.venbiasa.wailo.shared.format.JsonNode
import com.venbiasa.wailo.shared.format.PreviewKind
import com.venbiasa.wailo.shared.format.analyzeBody
import com.venbiasa.wailo.shared.format.formatBytes
import com.venbiasa.wailo.shared.format.hexDumpLine
import com.venbiasa.wailo.shared.format.parseFormUrlEncoded
import com.venbiasa.wailo.shared.format.parseJson
import com.venbiasa.wailo.shared.format.prettyPrintJson
import com.venbiasa.wailo.shared.theme.LocalWailoColors
import com.venbiasa.wailo.shared.theme.WailoColors
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
                PreviewKind.Json -> JsonTreePreview(body)
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
 * A collapsible JSON tree: every object/array is a disclosure row whose children can be folded away,
 * with type-colored values. The visible rows are flattened into a lazy list so a large payload only
 * composes what's on screen, and toggling a node just rebuilds that flat list. If the body doesn't
 * parse (Content-Type lied, or it was truncated), it falls back to pretty/raw text.
 */
@Composable
private fun JsonTreePreview(body: ByteString) {
    val root = remember(body) { parseJson(body.decodedText()) }
    if (root == null) {
        val text = remember(body) {
            val raw = body.decodedText()
            prettyPrintJson(raw) ?: raw
        }
        ScrollableMonoText(text)
        return
    }
    val collapsed = remember(body) { mutableStateMapOf<String, Boolean>() }
    val lines by remember(root) { derivedStateOf { flattenJson(root, collapsed) } }
    val style = monoSmall()
    // Width of the number gutter tracks the highest visible line number; monospace keeps digits aligned.
    val gutterDigits = lines.size.coerceAtLeast(1).toString().length
    SelectionContainer(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
            itemsIndexed(lines, key = { _, line -> line.id }) { index, line ->
                JsonLineRow(line, index + 1, gutterDigits, style) { path ->
                    collapsed[path] = !(collapsed[path] ?: false)
                }
            }
        }
    }
}

@Composable
private fun JsonLineRow(
    line: JsonLine,
    lineNumber: Int,
    gutterDigits: Int,
    style: TextStyle,
    onToggle: (String) -> Unit,
) {
    val wailo = LocalWailoColors.current
    val punctuation = MaterialTheme.colorScheme.onSurfaceVariant
    val keyColor = MaterialTheme.colorScheme.onSurface
    val expanded = line.content is JsonLineContent.Open
    val expandable = expanded || line.content is JsonLineContent.Collapsed
    val togglePath = line.content.togglePath

    Row(
        Modifier.fillMaxWidth().padding(end = 16.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The number gutter and the fold arrow sit together on the left and are excluded from selection,
        // so copying a range yields only the JSON text (indentation included), never the line numbers.
        // The arrow is the sole toggle target; clicking the line itself does nothing, keeping text selectable.
        DisableSelection {
            // Pad with a non-breaking space (not a normal one): the shrink-wrapped gutter would trim
            // regular leading spaces, jagging the digits and the arrows beside them. NBSP is one
            // monospace cell wide and is never trimmed. It's out of selection, so it never gets copied.
            Text(
                lineNumber.toString().padStart(gutterDigits, '\u00A0'),
                Modifier.padding(start = 12.dp),
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val arrowModifier =
                if (togglePath != null) Modifier.fillMaxHeight().width(20.dp).clickable { onToggle(togglePath) }
                else Modifier.width(20.dp)
            Box(arrowModifier, contentAlignment = Alignment.Center) {
                if (expandable) DisclosureTriangle(expanded, punctuation)
            }
        }
        // weight(1f) gives the line a real width: Compose Desktop collapses a shrink-wrapped Text's
        // leading spaces during intrinsic measurement, so the indentation only shows once it fills width.
        Text(jsonLineText(line, keyColor, wailo, punctuation), Modifier.weight(1f), style = style)
    }
}

// A small filled triangle drawn (not an icon asset) so it always renders; rotates 90° when expanded.
@Composable
private fun DisclosureTriangle(expanded: Boolean, color: Color) {
    Canvas(Modifier.size(8.dp).rotate(if (expanded) 90f else 0f)) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, color)
    }
}

private class JsonLine(
    val id: String,
    val depth: Int,
    val key: String?,
    val content: JsonLineContent,
    val trailingComma: Boolean,
)

private sealed interface JsonLineContent {
    /** Non-null for rows that fold a container; clicking the row toggles this path. */
    val togglePath: String? get() = null

    data class Open(val bracket: Char, val path: String) : JsonLineContent {
        override val togglePath: String get() = path
    }

    data class Close(val bracket: Char) : JsonLineContent

    data class Collapsed(val open: Char, val close: Char, val path: String) : JsonLineContent {
        override val togglePath: String get() = path
    }

    data class EmptyContainer(val open: Char, val close: Char) : JsonLineContent

    data class Value(val node: JsonNode) : JsonLineContent
}

/** Flattens [root] into the rows currently visible given the [collapsed] set, in display order. */
private fun flattenJson(root: JsonNode, collapsed: Map<String, Boolean>): List<JsonLine> {
    val out = ArrayList<JsonLine>()
    fun walk(key: String?, node: JsonNode, depth: Int, path: String, trailingComma: Boolean) {
        when (node) {
            is JsonNode.Obj -> when {
                node.entries.isEmpty() ->
                    out.add(JsonLine(path, depth, key, JsonLineContent.EmptyContainer('{', '}'), trailingComma))
                collapsed[path] == true ->
                    out.add(JsonLine(path, depth, key, JsonLineContent.Collapsed('{', '}', path), trailingComma))
                else -> {
                    out.add(JsonLine("$path~o", depth, key, JsonLineContent.Open('{', path), false))
                    node.entries.forEachIndexed { idx, entry ->
                        walk(entry.key, entry.value, depth + 1, "$path/${idx}k", idx < node.entries.lastIndex)
                    }
                    out.add(JsonLine("$path~c", depth, null, JsonLineContent.Close('}'), trailingComma))
                }
            }
            is JsonNode.Arr -> when {
                node.items.isEmpty() ->
                    out.add(JsonLine(path, depth, key, JsonLineContent.EmptyContainer('[', ']'), trailingComma))
                collapsed[path] == true ->
                    out.add(JsonLine(path, depth, key, JsonLineContent.Collapsed('[', ']', path), trailingComma))
                else -> {
                    out.add(JsonLine("$path~o", depth, key, JsonLineContent.Open('[', path), false))
                    node.items.forEachIndexed { idx, item ->
                        walk(null, item, depth + 1, "$path/$idx", idx < node.items.lastIndex)
                    }
                    out.add(JsonLine("$path~c", depth, null, JsonLineContent.Close(']'), trailingComma))
                }
            }
            else -> out.add(JsonLine(path, depth, key, JsonLineContent.Value(node), trailingComma))
        }
    }
    walk(null, root, 0, "root", false)
    return out
}

private fun jsonLineText(
    line: JsonLine,
    keyColor: Color,
    wailo: WailoColors,
    punctuation: Color,
): AnnotatedString = buildAnnotatedString {
    // Indentation is real space characters (not layout padding) so a copied selection stays indented.
    if (line.depth > 0) append("  ".repeat(line.depth))
    if (line.key != null) {
        withStyle(SpanStyle(color = keyColor)) { append("\"${escapeForDisplay(line.key)}\"") }
        withStyle(SpanStyle(color = punctuation)) { append(": ") }
    }
    when (val content = line.content) {
        is JsonLineContent.Open -> withStyle(SpanStyle(color = punctuation)) { append(content.bracket.toString()) }
        is JsonLineContent.Close -> withStyle(SpanStyle(color = punctuation)) { append(content.bracket.toString()) }
        is JsonLineContent.EmptyContainer ->
            withStyle(SpanStyle(color = punctuation)) { append("${content.open}${content.close}") }
        is JsonLineContent.Collapsed ->
            withStyle(SpanStyle(color = punctuation)) { append("${content.open} … ${content.close}") }
        is JsonLineContent.Value -> appendJsonValue(content.node, wailo, punctuation)
    }
    if (line.trailingComma) withStyle(SpanStyle(color = punctuation)) { append(",") }
}

private fun AnnotatedString.Builder.appendJsonValue(node: JsonNode, wailo: WailoColors, punctuation: Color) {
    when (node) {
        is JsonNode.Str -> withStyle(SpanStyle(color = wailo.success)) { append("\"${escapeForDisplay(node.value)}\"") }
        is JsonNode.Num -> withStyle(SpanStyle(color = wailo.info)) { append(node.text) }
        is JsonNode.Bool -> withStyle(SpanStyle(color = wailo.warning)) { append(node.value.toString()) }
        JsonNode.Null -> withStyle(SpanStyle(color = wailo.warning)) { append("null") }
        // Containers are rendered as their own rows, never inline as a value.
        is JsonNode.Obj, is JsonNode.Arr -> withStyle(SpanStyle(color = punctuation)) { append("…") }
    }
}

private fun escapeForDisplay(s: String): String = buildString {
    for (ch in s) when (ch) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(ch)
    }
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
