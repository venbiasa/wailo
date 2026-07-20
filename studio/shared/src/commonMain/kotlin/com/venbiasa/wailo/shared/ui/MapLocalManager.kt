package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.venbiasa.wailo.shared.MapLocalHeader
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.format.jsonErrorMessage
import com.venbiasa.wailo.shared.format.prettyPrintJson
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_add
import com.venbiasa.wailo.shared.resources.ic_arrow_back
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import com.venbiasa.wailo.shared.resources.ic_delete
import com.venbiasa.wailo.shared.theme.LocalWailoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.compose.resources.vectorResource

/**
 * The Map Local rules panel: a list of rules that falls through to an add/edit form. Stateless over
 * its inputs — the host owns the rule list and its persistence; this only renders and calls back. It
 * fills whatever surface it's given (the studio's right tool panel, ADR-0021).
 *
 * [initialDraft] seeds the editor: non-null opens straight into the form (used when launched from a
 * traffic row so the URL/method are pre-filled), null shows the list. [initialBodySeed] pre-fills the
 * inline body editor for that draft (e.g. the captured response, to map-and-tweak). [onUpsert] adds or
 * replaces a rule by id; [onRemove] deletes by id. [onLoadBody]/[onSaveBody] read and persist a rule's
 * authored body — the host owns all file IO (the body is an app-managed file), so `shared` still never
 * touches the filesystem. [onClose] dismisses the whole panel (the tool-panel close affordance).
 */
@Composable
fun MapLocalManager(
    rules: List<MapLocalRuleDef>,
    initialDraft: MapLocalRuleDef?,
    initialBodySeed: String? = null,
    onUpsert: (MapLocalRuleDef) -> Unit,
    onRemove: (String) -> Unit,
    onClose: () -> Unit = {},
    onLoadBody: suspend (MapLocalRuleDef) -> String = { "" },
    onSaveBody: suspend (MapLocalRuleDef, String) -> Unit = { _, _ -> },
) {
    // Navigation 3 owns the panel's page stack (rule list -> mapping-rule editor): the back stack is
    // the single source of truth for which page shows and for Back. The keys are @Serializable and the
    // stack is built with an explicit polymorphic config (not the reflection overload) so this nav
    // layer ports beyond JVM desktop to Android/iOS/web unchanged — reflection-based key serialization
    // is JVM/Android-only (ADR-0022).
    val backStack = rememberNavBackStack(MapLocalNavConfig, RuleListDestination)

    // The editor's inputs — the rule being authored and any captured body seed — are held out of the
    // nav key (which stays a small id) and resolved by the editor entry. A new (FAB) or row-seeded rule
    // isn't in `rules` yet, so it lives here until Save persists it. Transient session state, not
    // restored across process death (an interrupted draft is cheap to re-open).
    val drafts = remember { mutableStateMapOf<String, MapLocalRuleDef>() }
    val bodySeeds = remember { mutableStateMapOf<String, String?>() }

    fun openEditor(rule: MapLocalRuleDef, seed: String? = null) {
        drafts[rule.id] = rule
        bodySeeds[rule.id] = seed
        backStack.add(RuleEditorDestination(rule.id))
    }

    // A row's "Map Local…" hands over a fresh draft, possibly while the panel already shows a page;
    // reset to [list, editor] so Back returns to the rule list rather than a stale editor.
    LaunchedEffect(initialDraft) {
        val draft = initialDraft ?: return@LaunchedEffect
        drafts[draft.id] = draft
        bodySeeds[draft.id] = initialBodySeed
        backStack.clear()
        backStack.add(RuleListDestination)
        backStack.add(RuleEditorDestination(draft.id))
    }

    // NavDisplay caches each page's NavEntry keyed by the back stack, so the entry's content keeps the
    // `rules` value it captured when first shown and won't see later host edits (e.g. a toggle) until you
    // navigate. Reading the list through this stable State *inside* the entries makes NavEntry.Content —
    // itself a restart scope — recompose on every change, so the list and the editor's enabled switch
    // stay live and in sync instead of frozen until navigation.
    val liveRules = rememberUpdatedState(rules)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavDisplay(
            backStack = backStack,
            // Never pop the root list off the stack (an empty NavDisplay back stack is illegal); the
            // panel's Close affordance dismisses it instead.
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryProvider = { destination ->
                when (destination) {
                    is RuleListDestination -> NavEntry(destination) {
                        RuleList(
                            rules = liveRules.value,
                            onAdd = {
                                // New rules default to the inline editor (the common "author a body"
                                // path) and a JSON Content-Type header, changeable on the Headers tab.
                                openEditor(
                                    MapLocalRuleDef(
                                        id = MapLocalRuleDef.newId(),
                                        inline = true,
                                        headers = listOf(MapLocalHeader("Content-Type", "application/json")),
                                    ),
                                )
                            },
                            onEdit = { openEditor(it) },
                            onToggle = { rule -> onUpsert(rule.copy(enabled = !rule.enabled)) },
                            onRemove = onRemove,
                            onClose = onClose,
                        )
                    }
                    is RuleEditorDestination -> NavEntry(destination) {
                        // Read the live list here (not the captured `rules`) so this entry tracks host
                        // edits — see [liveRules].
                        val currentRules = liveRules.value
                        // Resolve from the pending draft (new/seeded) or the persisted list.
                        val initial = drafts[destination.ruleId]
                            ?: currentRules.firstOrNull { it.id == destination.ruleId }
                        if (initial == null) {
                            // The rule was removed out from under an open editor: fall back to the list.
                            LaunchedEffect(destination.ruleId) {
                                if (backStack.size > 1) backStack.removeLastOrNull()
                            }
                        } else {
                            // The enabled toggle is shared with the list row, so it commits immediately
                            // rather than waiting for Save — otherwise the editor and list switches drift.
                            // For an already-persisted rule, read and write `enabled` straight through the
                            // host list so both surfaces stay in lockstep; an unsaved draft keeps it local
                            // until Save (there's no list row to sync with yet).
                            val persisted = currentRules.firstOrNull { it.id == destination.ruleId }
                            RuleEditor(
                                initial = initial,
                                enabled = (persisted ?: initial).enabled,
                                onToggleEnabled = { next ->
                                    // Re-read the live rule at click time so committing the flag never
                                    // clobbers a concurrent edit with a stale snapshot.
                                    val live = liveRules.value.firstOrNull { it.id == destination.ruleId }
                                    if (live != null) {
                                        onUpsert(live.copy(enabled = next))
                                    } else {
                                        drafts[destination.ruleId]?.let { drafts[destination.ruleId] = it.copy(enabled = next) }
                                    }
                                },
                                bodySeed = bodySeeds[destination.ruleId],
                                onLoadBody = onLoadBody,
                                onSaveBody = onSaveBody,
                                onSaved = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onUpsert = onUpsert,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onClose = onClose,
                            )
                        }
                    }
                    // The stack only ever holds our own destinations; satisfy NavKey's open type.
                    else -> NavEntry(destination) {}
                }
            },
        )
    }
}

// The panel's two pages as a Navigation 3 back stack. @Serializable + NavKey so the stack persists
// portably; [MapLocalNavConfig] registers the polymorphism explicitly, which non-JVM targets require
// (reflection-based key serialization is JVM/Android-only). See ADR-0022.
@Serializable
private sealed interface MapLocalDestination : NavKey

@Serializable
private data object RuleListDestination : MapLocalDestination

@Serializable
private data class RuleEditorDestination(val ruleId: String) : MapLocalDestination

// Registers every destination under NavKey so rememberNavBackStack can (de)serialize the stack on all
// platforms, not just via JVM/Android reflection (ADR-0022).
private val MapLocalNavConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(RuleListDestination::class, RuleListDestination.serializer())
            subclass(RuleEditorDestination::class, RuleEditorDestination.serializer())
        }
    }
}

@Composable
private fun RuleList(
    rules: List<MapLocalRuleDef>,
    onAdd: () -> Unit,
    onEdit: (MapLocalRuleDef) -> Unit,
    onToggle: (MapLocalRuleDef) -> Unit,
    onRemove: (String) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Map Local", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                CloseButton(onClose, contentDescription = "Close panel")
            }
            RowDivider()
            if (rules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No rules yet. Add one to answer a request with an edited body or a local file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    // Bottom inset so the FAB never covers the last rule's toggle/delete controls.
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(rules, key = { it.id }) { rule ->
                        RuleRow(rule = rule, onEdit = onEdit, onToggle = onToggle, onRemove = onRemove)
                        RowDivider()
                    }
                }
            }
        }
        // Add is the panel's primary action, so it rides as an icon-only FAB in the bottom-right rather
        // than a labeled button in the header (which now holds just the title + close). The default
        // containerColor (primaryContainer) is themed to the accent in tokens.json, so no override.
        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(vectorResource(Res.drawable.ic_add), contentDescription = "Add rule", modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun RuleRow(
    rule: MapLocalRuleDef,
    onEdit: (MapLocalRuleDef) -> Unit,
    onToggle: (MapLocalRuleDef) -> Unit,
    onRemove: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onEdit(rule) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactSwitch(checked = rule.enabled, onCheckedChange = { onToggle(rule) })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                rule.urlPattern.ifBlank { "(no pattern)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val method = rule.method.ifBlank { "ANY" }
            val target = if (rule.inline) "inline body" else fileName(rule.filePath).ifBlank { "(no file)" }
            Text(
                "$method  \u2192  $target",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onRemove(rule.id) }, modifier = Modifier.size(36.dp)) {
            Icon(
                vectorResource(Res.drawable.ic_delete),
                contentDescription = "Delete rule",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RuleEditor(
    initial: MapLocalRuleDef,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    bodySeed: String?,
    onLoadBody: suspend (MapLocalRuleDef) -> String,
    onSaveBody: suspend (MapLocalRuleDef, String) -> Unit,
    onSaved: () -> Unit,
    onUpsert: (MapLocalRuleDef) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    var urlPattern by remember { mutableStateOf(initial.urlPattern) }
    var method by remember { mutableStateOf(initial.method) }
    var statusCode by remember { mutableStateOf(initial.statusCode.toString()) }
    val headers = remember { initial.headers.toMutableStateList() }
    var editorTab by remember { mutableStateOf(EditorTab.Body) }
    var saving by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val editorState = rememberCodeEditorState(bodySeed ?: "")

    // Seed the editor: from the captured body when launched from a row, else load the rule's stored
    // body from the host. touch() re-runs validation after either fills the document.
    LaunchedEffect(editorState) {
        if (bodySeed == null) {
            editorState.setText(onLoadBody(initial))
        }
        editorState.touch()
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

    fun buildRule(): MapLocalRuleDef = initial.copy(
        enabled = enabled,
        urlPattern = urlPattern.trim(),
        method = method,
        // Every rule is now inline: the body is authored here and persisted to the host's app-managed
        // file store (no user-chosen file path anymore).
        filePath = "",
        statusCode = statusCode.toIntOrNull() ?: 200,
        // Drop half-authored rows (no name); trim so stray spaces don't ride into the wire header.
        headers = headers.filter { it.name.isNotBlank() }.map { MapLocalHeader(it.name.trim(), it.value.trim()) },
        inline = true,
    )

    fun save() {
        if (saving) return
        saving = true
        val rule = buildRule()
        scope.launch {
            // Pretty-print on the way out (Map Local is JSON-focused); a body that won't parse as JSON is
            // saved verbatim rather than blocking the save, and an over-large body skips formatting entirely.
            val raw = editorState.currentText()
            val body = withContext(Dispatchers.Default) {
                if (raw.length <= MAX_FORMAT_CHARS) prettyPrintJson(raw) ?: raw else raw
            }
            onSaveBody(rule, body)
            onUpsert(rule)
            onSaved()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Fixed header: title, the enabled toggle (unlabeled — its tooltip names it), and close, so a
        // rule's on/off and the dismiss stay pinned above the payload area.
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
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
                "Mapping Rule",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            HoverTooltip(if (enabled) "Enabled" else "Disabled") {
                CompactSwitch(checked = enabled, onCheckedChange = onToggleEnabled)
            }
            Spacer(Modifier.width(4.dp))
            CloseButton(onClose, contentDescription = "Close panel")
        }
        RowDivider()

        // Compact rule fields stay pinned at the top; the response payload (body + headers) fills the
        // rest through the tabs, so the editor takes the panel's remaining height and grows with the
        // window. The Swing-backed editor deliberately isn't wrapped in a Compose scroll — a heavyweight
        // AWT component flickers as the scroll repositions it — so it fills a fixed slot and scrolls its
        // own content instead (ADR-0020/0021).
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LabeledField("URL pattern", Modifier.fillMaxWidth()) {
                CompactOutlinedTextField(
                    value = urlPattern,
                    onValueChange = { urlPattern = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "https://api.example.com/v1/*",
                )
            }
            // Both fields hug their content and sit together at the start of the row rather than
            // stretching across it — Method fits the selected verb, Status code fits three digits (a
            // min width keeps it from collapsing as you type).
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledField("Method") {
                    MethodDropdown(method = method, onSelect = { method = it })
                }
                LabeledField("Status code") {
                    CompactOutlinedTextField(
                        value = statusCode,
                        onValueChange = { next -> statusCode = next.filter { it.isDigit() }.take(3) },
                        modifier = Modifier.widthIn(min = 64.dp),
                        placeholder = "200",
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
                EditorTab.Body -> BodyTab(editorState = editorState)
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
            // header's arrow, so the footer carries just the commit action.
            BodyVerdict(validity, Modifier.weight(1f))
            Button(
                onClick = { save() },
                enabled = !saving && urlPattern.isNotBlank(),
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
// should span the panel (URL pattern) opts in by passing Modifier.fillMaxWidth().
@Composable
private fun LabeledField(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

// A rule matches a single HTTP method; "Any" (blank) leaves the method unconstrained. The blank entry
// leads so it reads as the "no restriction" default, then the common methods in request-frequency order.
private val MethodOptions = listOf("", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

private fun methodLabel(method: String): String = method.ifBlank { "Any" }

/**
 * Single-select HTTP method picker. A plain field + [DropdownMenu] (not a text field) so the menu
 * anchors to it and the value can't be free-typed. The menu is a Compose popup drawn over the inline
 * body editor's Swing panel, which works because the host enables interop blending (ADR-0021).
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
 * The Body tab: the JSON editor for the response body Map Local will serve. The editor is a Swing panel
 * that unmounts when the Headers tab shows, but [CodeEditorState] snapshots its text across that (see its
 * `detach`), so edits and Save survive a switch. Validity shows in the footer verdict, not here.
 */
@Composable
private fun BodyTab(editorState: CodeEditorState) {
    // Fill the tab area rather than wrapping the Swing editor in a Compose scroll: a heavyweight AWT
    // component flickers as an outer scroll repositions it. The editor grows with the panel/window and
    // scrolls its own content via RSyntaxTextArea's native scrollbars.
    CodeEditor(
        state = editorState,
        language = CodeLanguage.Json,
        readOnly = false,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * The Headers tab: an editable name/value table for the headers the mocked response returns. Content-Type
 * folds in here (no separate field); Content-Length is host-managed and intentionally not editable.
 */
@Composable
private fun HeadersTab(headers: SnapshotStateList<MapLocalHeader>) {
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
        OutlinedButton(onClick = { headers.add(MapLocalHeader("", "")) }) {
            Icon(vectorResource(Res.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add header")
        }
    }
}

@Composable
private fun HeaderRowEditor(
    header: MapLocalHeader,
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

private fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
