package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.PickedFile
import com.venbiasa.wailo.shared.ResponseHeader
import com.venbiasa.wailo.shared.format.formatBytes
import com.venbiasa.wailo.shared.format.jsonErrorMessage
import com.venbiasa.wailo.shared.format.prettyPrintJson
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_add
import com.venbiasa.wailo.shared.resources.ic_arrow_back
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import com.venbiasa.wailo.shared.resources.ic_check
import com.venbiasa.wailo.shared.resources.ic_delete
import com.venbiasa.wailo.shared.theme.LocalWailoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.vectorResource

/**
 * The response a rule editor authors, independent of which feature owns the rule: what it matches
 * ([urlPattern]/[method]) and what it answers with ([statusCode]/[delayMillis]/[headers], plus the body
 * the editor hands back separately as bytes). Map Local and Seed both fill this in; only Map Local adds
 * a name on top of it.
 */
internal data class ResponseDraft(
    val urlPattern: String,
    val method: String,
    val statusCode: Int,
    val delayMillis: Int = 0,
    val headers: List<ResponseHeader>,
)

/**
 * The shared editor for a rule that answers a request with an authored response — Map Local's mapping
 * rules and Seed's canned answers are the same form, so they are the same composable (the ADR-0028 move
 * that already made both features share one rule list, applied to the editor).
 *
 * [initialName] is the only structural difference: non-null shows a required Name field (Map Local),
 * null omits it entirely (a seed is identified by what it matches). [enabled]/[onToggleEnabled] drive the
 * header switch, gated by [enabledToggleable] when a group or the feature master already holds the rule
 * off. [bodySeed] pre-fills the body for a rule seeded from captured traffic; otherwise [onLoadBody]
 * supplies the persisted bytes, and [onPickFile] fills it from a file on disk. Everything commits through
 * [onSave] in one call — name, response fields, and body bytes together — so each feature owns the order
 * in which it writes its own body file and layout.
 *
 * Saving leaves the page open and reports itself in the footer rather than dismissing it: a rule is
 * usually saved to *try* it, and closing the editor takes the body's undo history with it — so an
 * experiment that turned out wrong could only be walked back by retyping it. [onBack] is the way out.
 * [persisted] says whether the rule is already in the layout, which is what tells a form that opened
 * matching the store (nothing to commit) from a new or row-seeded draft (everything to commit).
 */
@Composable
internal fun ResponseRuleEditor(
    title: String,
    initial: ResponseDraft,
    initialName: String?,
    persisted: Boolean,
    enabled: Boolean,
    enabledToggleable: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    bodySeed: ByteArray?,
    onLoadBody: suspend () -> ByteArray,
    onPickFile: suspend () -> PickedFile?,
    onSave: suspend (name: String, draft: ResponseDraft, body: ByteArray) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val named = initialName != null
    var name by remember { mutableStateOf(initialName.orEmpty()) }
    var urlPattern by remember { mutableStateOf(initial.urlPattern) }
    var method by remember { mutableStateOf(initial.method) }
    var statusCode by remember { mutableStateOf(initial.statusCode.toString()) }
    var delayMillis by remember { mutableStateOf(initial.delayMillis.toString()) }
    val headers = remember { initial.headers.toMutableStateList() }
    var editorTab by remember { mutableStateOf(EditorTab.Body) }
    var saving by remember { mutableStateOf(false) }

    // Validate on Save, not while typing: nothing flashes red until the user actually attempts a save.
    // [showErrors] flips on the first invalid attempt; from then on the offending fields reveal their
    // error live (computed instantly) so they clear as the user fixes them. A named rule needs a name to
    // identify it in the list; the status code must be a real HTTP status (100–599); delay is a
    // non-negative millisecond count; and a URL pattern is required to match against.
    var showErrors by remember { mutableStateOf(false) }
    val nameInvalid = named && name.isBlank()
    val statusCodeInvalid = statusCode.toIntOrNull().let { it == null || it !in VALID_STATUS_CODES }
    val delayInvalid = delayMillis.toIntOrNull().let { it == null || it < 0 }
    val urlInvalid = urlPattern.isBlank()
    val nameError = showErrors && nameInvalid
    val statusCodeError = showErrors && statusCodeInvalid
    val delayError = showErrors && delayInvalid
    val urlError = showErrors && urlInvalid

    val scope = rememberCoroutineScope()

    // A rule serves either a text/JSON body (authored in the code editor) or a binary image body (chosen
    // from a file, previewed here), decided live by the Content-Type header — so choosing a file (or
    // editing Content-Type on the Headers tab) flips the Body tab between the two. The two bodies live in
    // separate state: the code editor's document for text, in-memory [imageBytes] for the image; only the
    // one matching the current Content-Type is persisted on Save.
    val contentType = headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value
    val isImage = contentType.isImageContentType()
    // The seed can't be decoded both ways, so the code editor is only ever seeded for a rule that opened
    // as text; an image rule keeps its bytes for the preview instead.
    val startedAsImage = remember { initial.headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value.isImageContentType() }
    var imageBytes by remember { mutableStateOf(if (startedAsImage) bodySeed else null) }

    val editorState = rememberCodeEditorState("")

    // Whether the form opened already matching the store: a rule that is in the layout and is showing the
    // body stored for it. Captured once, because it describes the opening — a save later fills [saved] in
    // and makes it moot. Captured bytes are precisely what has *not* been written, so a row-seeded draft
    // is not clean however it was addressed.
    val openedClean = remember { persisted && bodySeed == null }

    // What is currently in the store, in the form's own terms — the rule as it opened, or as the last save
    // left it. Both the footer verdict and Save's enablement are a comparison against this.
    var saved by remember { mutableStateOf<SavedRule?>(null) }
    val saveState by remember {
        derivedStateOf {
            val last = saved
                // The body's revision isn't settled until the seed lands, so answer from the opening
                // condition until it does — the same answer each case settles on, so nothing flickers.
                ?: return@derivedStateOf if (openedClean) SaveState.Saved else SaveState.Fresh
            val differs = name != last.name || urlPattern != last.urlPattern || method != last.method ||
                statusCode != last.statusCode || delayMillis != last.delayMillis ||
                // Copied out before comparing, never compared in place: SnapshotStateList implements
                // MutableList without AbstractList's structural equals, so holding one up against a stored
                // copy is an identity check that can never hold.
                headers.toList() != last.headers ||
                // The body is compared by edit count, never by text: re-reading a multi-megabyte document
                // on every keystroke is the cost the debounced validation below exists to dodge, and this
                // is read on every one. So an undo back to the saved text still reads as unsaved — wrong
                // in the direction that only ever costs a redundant save.
                editorState.revision != last.bodyRevision || imageBytes !== last.imageBytes
            if (differs) SaveState.Unsaved else SaveState.Saved
        }
    }

    fun setContentType(value: String) {
        val idx = headers.indexOfFirst { it.name.equals("Content-Type", ignoreCase = true) }
        if (idx >= 0) headers[idx] = headers[idx].copy(value = value)
        else headers.add(ResponseHeader("Content-Type", value))
    }

    // Fill the code editor from JSON/text bytes, pretty-printing JSON off the main thread on the way in.
    suspend fun fillEditor(bytes: ByteArray) {
        val text = withContext(Dispatchers.Default) {
            val raw = bytes.decodeToString()
            prettyPrintJson(raw) ?: raw
        }
        editorState.setText(text)
        editorState.touch()
    }

    // Seed the body once: an image rule loads its bytes (the captured seed, or the host's stored file)
    // for the preview; a text rule fills the code editor. touch() re-runs validation after it's filled.
    LaunchedEffect(editorState) {
        if (startedAsImage) {
            if (bodySeed == null) imageBytes = onLoadBody()
        } else {
            fillEditor(bodySeed ?: onLoadBody())
        }
        editorState.touch()
        // Recorded here rather than at first composition because the body's revision is only settled once
        // the seed lands; [openedClean] is what decides whether there is a baseline to record at all. The
        // non-body fields come from the parameters, never from the live state: the load is a host round-trip
        // the user can type across, and reading the fields now would file that edit as already stored —
        // "Saved" over an unsaved change, behind a Save the edit itself just disabled.
        if (openedClean) {
            saved = SavedRule(
                name = initialName.orEmpty(),
                urlPattern = initial.urlPattern,
                method = initial.method,
                statusCode = initial.statusCode.toString(),
                delayMillis = initial.delayMillis.toString(),
                headers = initial.headers,
                bodyRevision = editorState.revision,
                imageBytes = imageBytes,
            )
        }
    }

    // Author the body from a file the host picks (any rule): adopt the file's Content-Type — which drives
    // the Body surface and what's served — then a JSON/text file loads into the code editor and an image
    // into the preview.
    fun chooseFile() {
        scope.launch {
            val picked = onPickFile() ?: return@launch
            setContentType(picked.contentType)
            if (picked.contentType.isImageContentType()) {
                imageBytes = picked.bytes
            } else {
                fillEditor(picked.bytes)
            }
        }
    }

    // Live JSON validity, debounced off the editor's edit ticks so a keystroke never forces a
    // recomposition of the form; the (potentially large) text is only read after typing settles.
    var validity by remember { mutableStateOf<BodyValidity>(BodyValidity.None) }
    LaunchedEffect(editorState) {
        snapshotFlow { editorState.revision }.collectLatest {
            delay(250)
            val text = editorState.currentText()
            validity = withContext(Dispatchers.Default) {
                when {
                    text.isBlank() -> BodyValidity.None
                    // Building a JSON tree for a multi-megabyte body would stutter the UI; defer to Save.
                    text.length > MAX_LIVE_VALIDATE_CHARS -> BodyValidity.TooLarge
                    else -> jsonErrorMessage(text)?.let { BodyValidity.Invalid(it) } ?: BodyValidity.Valid
                }
            }
        }
    }

    fun save() {
        if (saving) return
        // Validate on submit: reveal the field errors and abort if anything's off, otherwise persist.
        if (nameInvalid || statusCodeInvalid || delayInvalid || urlInvalid) {
            showErrors = true
            return
        }
        saving = true
        val draft = ResponseDraft(
            urlPattern = urlPattern.trim(),
            method = method,
            statusCode = statusCode.toIntOrNull() ?: 200,
            delayMillis = delayMillis.toIntOrNull() ?: 0,
            // Drop half-authored rows (no name); trim so stray spaces don't ride into the wire header.
            headers = headers.filter { it.name.isNotBlank() }.map { ResponseHeader(it.name.trim(), it.value.trim()) },
        )
        // Snapshot the body source now (off-composition reads in the coroutine would be stale/illegal).
        val imageMode = isImage
        val bodyImage = imageBytes
        // The fields as typed, not the draft's trimmed copies, so the baseline compares like with like and
        // a trailing space doesn't read straight back as an unsaved change.
        val typedName = name
        val typedUrl = urlPattern
        val typedMethod = method
        val typedStatus = statusCode
        val typedDelay = delayMillis
        val typedHeaders = headers.toList()
        scope.launch {
            try {
                // Read ahead of the text, so a keystroke that races the save leaves the editor unsaved
                // rather than claiming bytes it didn't write.
                val bodyRevision = editorState.revision
                val bytes = if (imageMode) {
                    bodyImage ?: ByteArray(0)
                } else {
                    // Pretty-print JSON on the way out; a body that won't parse is saved verbatim rather
                    // than blocking the save, and an over-large body skips formatting entirely.
                    val raw = editorState.currentText()
                    withContext(Dispatchers.Default) {
                        (if (raw.length <= MAX_FORMAT_CHARS) prettyPrintJson(raw) ?: raw else raw).encodeToByteArray()
                    }
                }
                onSave(typedName.trim(), draft, bytes)
                saved = SavedRule(
                    typedName,
                    typedUrl,
                    typedMethod,
                    typedStatus,
                    typedDelay,
                    typedHeaders,
                    bodyRevision,
                    bodyImage,
                )
            } finally {
                // The page outlives the save, so a failed one has to hand the button back.
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Fixed header: title, the enabled toggle (unlabeled — its tooltip names it), and close, so a
        // rule's on/off and the dismiss stay pinned above the payload area.
        Row(
            Modifier.fillMaxWidth()
                .height(TopBarHeight)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back returns to the rule list (the panel's other page); Close dismisses the whole panel.
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    vectorResource(Res.drawable.ic_arrow_back),
                    contentDescription = "Back to rules",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            HoverTooltip(if (enabled) "Enabled" else "Disabled") {
                CompactSwitch(checked = enabled, onCheckedChange = onToggleEnabled, enabled = enabledToggleable)
            }
            Spacer(Modifier.width(4.dp))
            CloseButton(onClose, contentDescription = "Close panel")
        }
        RowDivider()

        // Compact rule fields stay pinned at the top; the response payload (body + headers) fills the
        // rest through the tabs, so the editor takes the panel's remaining height and grows with the
        // window. The editor owns its own (viewport-virtualized) vertical scroll, so it fills a fixed
        // slot rather than being wrapped in an outer Compose scroll (ADR-0023).
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (named) {
                LabeledField(
                    "Name",
                    Modifier.fillMaxWidth(),
                    error = if (nameError) "Name can\u2019t be empty" else null,
                ) {
                    CompactOutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Untitled",
                        isError = nameError,
                    )
                }
            }
            LabeledField(
                "URL pattern",
                Modifier.fillMaxWidth(),
                error = if (urlError) "URL pattern can\u2019t be empty" else null,
            ) {
                CompactOutlinedTextField(
                    value = urlPattern,
                    onValueChange = { urlPattern = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "https://api.example.com/v1/*",
                    isError = urlError,
                )
            }
            // Compact fields hug their content at the start of the row rather than stretching across it.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledField("Method") {
                    MethodDropdown(method = method, onSelect = { method = it })
                }
                LabeledField(
                    "Status code",
                    error = if (statusCodeError) "100\u2013599" else null,
                ) {
                    CompactOutlinedTextField(
                        value = statusCode,
                        onValueChange = { next -> statusCode = next.filter { it.isDigit() }.take(3) },
                        modifier = Modifier.widthIn(min = 64.dp),
                        placeholder = "200",
                        isError = statusCodeError,
                    )
                }
                LabeledField(
                    "Delay (ms)",
                    error = if (delayError) "0 or more" else null,
                ) {
                    CompactOutlinedTextField(
                        value = delayMillis,
                        onValueChange = { next -> delayMillis = next.filter { it.isDigit() } },
                        modifier = Modifier.widthIn(min = 80.dp),
                        placeholder = "0",
                        isError = delayError,
                    )
                }
            }
        }

        // Headers lead the tab pair (an HTTP response is status + headers, then body); a single raw
        // status+headers+body buffer was rejected because it breaks the JSON editor's validation,
        // which assumes the buffer is only the body (ADR-0021).
        UnderlineTabs(
            items = listOf(
                TabItem(EditorTab.Headers, EditorTab.Headers.label),
                TabItem(EditorTab.Body, EditorTab.Body.label),
            ),
            selected = editorTab,
            onSelect = { editorTab = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (editorTab) {
                EditorTab.Body -> BodyTab(
                    isImage = isImage,
                    imageBytes = imageBytes,
                    editorState = editorState,
                    onChooseFile = ::chooseFile,
                )
                EditorTab.Headers -> HeadersTab(headers)
            }
        }

        RowDivider()
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The JSON verdict shares the footer with Save to save vertical space, taking the free width
            // to its left and wrapping (multiline) so an error stays readable. Backing out is the
            // header's arrow, so the footer carries just the commit action. An image body has no JSON to
            // judge, so the verdict is suppressed then.
            BodyVerdict(if (isImage) BodyValidity.None else validity, Modifier.weight(1f))
            SaveVerdict(saveState)
            Button(
                onClick = { save() },
                enabled = !saving && saveState != SaveState.Saved,
            ) {
                Text("Save")
            }
        }
    }
}

// Body-size ceilings (ADR-0023). Pretty-printing is a cheap single O(n) pass, so it runs up to ~10 MB;
// live validation builds a full JSON tree (memory-heavy), so it's capped lower and defers to the Save-time
// format/parse beyond it.
private const val MAX_FORMAT_CHARS = 10_000_000
private const val MAX_LIVE_VALIDATE_CHARS = 2_000_000

/** The form as the store holds it — the baseline both the footer verdict and Save's enablement compare against. */
private class SavedRule(
    val name: String,
    val urlPattern: String,
    val method: String,
    val statusCode: String,
    val delayMillis: String,
    val headers: List<ResponseHeader>,
    val bodyRevision: Int,
    val imageBytes: ByteArray?,
)

/**
 * How the form stands against the store: [Fresh] for a draft nothing has written yet, [Saved] when the two
 * match, [Unsaved] when they don't. Drives both the footer verdict and whether Save has anything to do.
 */
private enum class SaveState { Fresh, Saved, Unsaved }

/**
 * Says which of [SaveState] the form is in, beside the Save button it explains: a greyed-out Save with no
 * caption is indistinguishable from a broken one. A [SaveState.Fresh] draft says nothing — the live Save
 * button already does.
 */
@Composable
private fun SaveVerdict(state: SaveState) {
    when (state) {
        SaveState.Fresh -> Unit
        SaveState.Unsaved -> Text(
            "Unsaved changes",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SaveState.Saved -> {
            val success = LocalWailoColors.current.success
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    vectorResource(Res.drawable.ic_check),
                    contentDescription = null,
                    tint = success,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Saved", style = MaterialTheme.typography.labelMedium, color = success)
            }
        }
    }
}

/** Body validity for the footer verdict: no body, valid JSON, a parse error, or too large to check live. */
private sealed interface BodyValidity {
    data object None : BodyValidity
    data object Valid : BodyValidity
    data object TooLarge : BodyValidity
    data class Invalid(val message: String) : BodyValidity
}

// The live JSON verdict for the body, shown inline in the footer beside Cancel/Save (not its own row) to
// save vertical space. There's no Format button — the body is pretty-printed on Save — so this only
// reports whether the body will parse. It wraps (multiline) rather than truncating; blank when there's
// no body to judge, but it still holds its width so the buttons stay put.
@Composable
private fun BodyVerdict(validity: BodyValidity, modifier: Modifier = Modifier) {
    val wailo = LocalWailoColors.current
    when (validity) {
        BodyValidity.None -> Spacer(modifier)
        BodyValidity.Valid -> Text(
            "Valid JSON",
            style = MaterialTheme.typography.labelMedium,
            color = wailo.success,
            modifier = modifier,
        )
        is BodyValidity.Invalid -> Text(
            validity.message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
        BodyValidity.TooLarge -> Text(
            "Large body — checked on save",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

// Wraps its content by default, so a compact field (Method/Status) hugs what it holds; a field that
// should span the panel (URL pattern) opts in by passing Modifier.fillMaxWidth(). A non-null [error]
// caption renders under the field in the error color — the field itself carries the matching red
// outline (isError) so the failing input reads as invalid at a glance.
@Composable
private fun LabeledField(
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        content()
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

// HTTP status codes span 100–599; the editor blocks Save (and flags the field) on anything outside so
// a typo like an empty or out-of-range code can't ride through to a served response.
private val VALID_STATUS_CODES = 100..599

// A rule matches a single HTTP method; "Any" (blank) leaves the method unconstrained. The blank entry
// leads so it reads as the "no restriction" default, then the common methods in request-frequency order.
private val MethodOptions = listOf("", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

private fun methodLabel(method: String): String = method.ifBlank { "Any" }

/**
 * Single-select HTTP method picker. A plain field + [DropdownMenu] (not a text field) so the menu
 * anchors to it and the value can't be free-typed.
 */
@Composable
private fun MethodDropdown(method: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // Shared with the decoration so hovering/clicking the whole box animates the same outline a text
    // field shows — the picker reads as a peer of the Status code field beside it, not a bare border.
    val interactionSource = remember { MutableInteractionSource() }
    Box {
        // Render as a read-only compact field: the exact decoration the text fields use, so Method and
        // Status code are the same height by construction (not by re-tuned padding). The value can't be
        // free-typed; clicking anywhere in the field opens the menu. It wraps to the label width so the
        // field hugs the selected verb rather than stretching across the row.
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(interactionSource = interactionSource, indication = null) { expanded = true },
        ) {
            CompactFieldDecoration(
                value = methodLabel(method),
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
                        methodLabel(method),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MethodOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(methodLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private enum class EditorTab(val label: String) { Body("Body"), Headers("Headers") }

/**
 * The Body tab: a "Choose file…" action (fill the body from a file on disk — available to every rule)
 * above the body surface, which is the JSON code editor for a text/JSON body or an image preview for an
 * image body, decided by the rule's Content-Type. The editor's [CodeEditorState] is hoisted above the
 * tabs so edits and Save survive a tab switch or a flip between the two surfaces.
 */
@Composable
private fun BodyTab(
    isImage: Boolean,
    imageBytes: ByteArray?,
    editorState: CodeEditorState,
    onChooseFile: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedButton(onClick = onChooseFile, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Choose file\u2026")
        }
        // The body surface fills the rest of the tab: the editor owns its own (viewport-virtualized)
        // scroll and grows with the panel/window (ADR-0023); the image preview scales to fit.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (isImage) {
                ImagePreviewPane(imageBytes)
            } else {
                CodeEditor(
                    state = editorState,
                    language = CodeLanguage.Json,
                    readOnly = false,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Previews the image bytes a rule will serve. Bytes that don't decode (a format Skia can't render, e.g.
 * SVG) still serve as-is, so this only reports it rather than treating it as an error.
 */
@Composable
private fun ImagePreviewPane(bytes: ByteArray?) {
    val bitmap = remember(bytes) {
        bytes?.takeIf { it.isNotEmpty() }?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
    }
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            bytes == null || bytes.isEmpty() ->
                MutedText("No image yet — choose a file to serve as this response's body.")
            bitmap == null ->
                MutedText("Can't preview this file (${formatBytes(bytes.size.toLong())}); it will still be served as-is.")
            else -> Image(
                bitmap = bitmap,
                contentDescription = "Mapped image preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

// A rule serves an image when its Content-Type is image/* — the signal the editor uses to switch the
// Body tab between the JSON editor and the image preview, and the host uses to name the stored body.
private fun String?.isImageContentType(): Boolean =
    this?.trim()?.startsWith("image/", ignoreCase = true) == true

/**
 * The Headers tab: an editable name/value table for the headers the mocked response returns. Content-Type
 * folds in here (no separate field); Content-Length is host-managed and intentionally not editable.
 */
@Composable
private fun HeadersTab(headers: SnapshotStateList<ResponseHeader>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Headers the mocked response returns. Content-Length is set automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (headers.isEmpty()) MutedText("No headers yet.")
        headers.forEachIndexed { index, header ->
            HeaderRowEditor(
                header = header,
                onNameChange = { headers[index] = headers[index].copy(name = it) },
                onValueChange = { headers[index] = headers[index].copy(value = it) },
                onRemove = { headers.removeAt(index) },
            )
        }
        OutlinedButton(onClick = { headers.add(ResponseHeader("", "")) }) {
            Icon(vectorResource(Res.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add header")
        }
    }
}

@Composable
private fun HeaderRowEditor(
    header: ResponseHeader,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactOutlinedTextField(
            value = header.name,
            onValueChange = onNameChange,
            modifier = Modifier.weight(0.42f),
            placeholder = "Header",
        )
        CompactOutlinedTextField(
            value = header.value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(0.58f),
            placeholder = "Value",
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                vectorResource(Res.drawable.ic_delete),
                contentDescription = "Remove header",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
