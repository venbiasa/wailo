package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.venbiasa.wailo.shared.FilterClause
import com.venbiasa.wailo.shared.FilterKey
import com.venbiasa.wailo.shared.TrafficFilter
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_add
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import com.venbiasa.wailo.shared.resources.ic_close
import com.venbiasa.wailo.shared.resources.ic_search
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.vectorResource

// How long the value field must sit idle before its autocomplete list recomputes, so fast typing doesn't
// refilter on every keystroke. Short enough to feel instant once the user pauses.
private const val AutocompleteDebounceMillis = 180L

// The keyword box edits a local copy and only publishes the filter once typing pauses: every published
// query re-runs the predicate over the whole capture, so a per-keystroke push would refilter the list
// several times for one word. Slightly longer than the autocomplete debounce — this one costs a full pass.
private const val KeywordDebounceMillis = 250L

// Don't offer suggestions until the user has typed enough to be discriminating — a 1-2 char prefix would
// match most of a busy capture, so wait for a real stem (Edited is exempt: its whole value space is 2 items).
private const val MinSuggestionChars = 3

// A hard cap so a broad stem on a large capture can't build thousands of rows behind the scroll.
private const val MaxSuggestions = 50

/**
 * The traffic list's view-filter bar (Kibana-style), shown above the list only while open (Cmd/Ctrl+F
 * opens it, like the code editor's find bar). Row 1 is a plain-text keyword box — debounced, so a word
 * costs one pass over the capture rather than one per keystroke — a `+ Add filter` entry point, and a
 * close button; structured filters appear only as dismissible pills on row 2 once added. The bar is
 * stateless over the [filter] — the viewer owns it (transient view state) — and defers the add-filter
 * modal to the viewer (via [onRequestAddFilter]) so its scrim can dim only the left pane.
 *
 * [onClose] clears every filter and hides the bar (so a hidden bar never leaves a list silently filtered);
 * it is wired to both the close button and Escape while the keyword field has focus. [focusSignal] pulls
 * the caret into the keyword field on open and on every later Cmd+F (same contract as the find bar).
 */
@Composable
internal fun FilterBar(
    filter: TrafficFilter,
    onFilterChange: (TrafficFilter) -> Unit,
    onRequestAddFilter: () -> Unit,
    onClose: () -> Unit,
    focusSignal: Int,
) {
    val keywordFocus = remember { FocusRequester() }
    // Runs on first composition (the bar exists only while open, so opening focuses the keyword box) and
    // again on every later [focusSignal] bump, which is how a second Cmd+F pulls focus back to the field.
    LaunchedEffect(focusSignal) { keywordFocus.requestFocus() }

    // What the user sees while typing. The published filter trails it by [KeywordDebounceMillis]; seeded from
    // the filter so re-opening the bar on an active query shows it.
    var keyword by remember { mutableStateOf(filter.query) }
    // Read the live filter inside the effect: it isn't an effect key (a clause added mid-word must not
    // restart the debounce), so capturing it directly would let a stale copy overwrite the new clauses.
    val currentFilter by rememberUpdatedState(filter)
    LaunchedEffect(keyword) {
        if (keyword == currentFilter.query) return@LaunchedEffect
        delay(KeywordDebounceMillis)
        onFilterChange(currentFilter.copy(query = keyword))
    }

    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactOutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.weight(1f)
                    .focusRequester(keywordFocus)
                    .onPreviewKeyEvent { event ->
                        // Esc while typing here clears every filter and closes the bar (req: same as the
                        // close button). Everything else falls through to the field's own editing.
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onClose()
                            true
                        } else {
                            false
                        }
                    },
                // No query language yet, so the box is a plain keyword search over url/method/status.
                placeholder = "Keyword\u2026",
                containerColor = MaterialTheme.colorScheme.surface,
                leadingIcon = {
                    // The compact field opts out of M3's 48dp min-interactive icon box (which normally
                    // insets the glyph ~12dp), so recreate that start inset here; the decoration's
                    // contentPadding then adds the icon→text gap.
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_search),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp).size(18.dp),
                    )
                },
            )
            AddFilterChip(onClick = onRequestAddFilter)
            HoverTooltip("Close filter (Esc)") {
                CloseButton(onClose = onClose, contentDescription = "Close filter")
            }
        }

        // Row 2 exists only once structured clauses are present: one pill each, removable individually.
        if (filter.clauses.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                filter.clauses.forEachIndexed { index, clause ->
                    FilterPill(
                        text = "${clause.key.label}: ${clause.value}",
                        onRemove = {
                            onFilterChange(filter.copy(clauses = filter.clauses.filterIndexed { i, _ -> i != index }))
                        },
                    )
                }
            }
        }
    }
}

/**
 * The `+ Add filter` modal body. Rendered by the viewer over a scrim that dims only the left pane, so it
 * is a standalone card here (the viewer owns placement + the scrim). A field dropdown — opening on no
 * selection, so nothing is filtered under a default the user never picked — plus a "contains" value field
 * whose autocomplete list is built from [suggestions]: the distinct values actually seen for that field.
 * That list is a floating [Popup] anchored under the value field, so it never resizes the card and scrolls
 * past a bounded height, and picking from it only fills the field. Committing stays explicit: [onAdd] runs
 * on Add or Enter and appends the clause; [onCancel] dismisses.
 */
@Composable
internal fun AddFilterCard(
    suggestions: Map<FilterKey, List<String>>,
    onAdd: (FilterClause) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // No field is preselected: the modal opens asking which one, so nothing is added under a default the
    // user never chose. Null renders the dropdown's placeholder and leaves Add disabled.
    var key by remember { mutableStateOf<FilterKey?>(null) }
    var value by remember { mutableStateOf("") }
    // The value the suggestion list is filtered by; trails [value] by [AutocompleteDebounceMillis].
    var debounced by remember { mutableStateOf("") }
    LaunchedEffect(value) {
        delay(AutocompleteDebounceMillis)
        debounced = value
    }
    // Picking a suggestion fills the field, which would otherwise leave the list open over its own exact
    // match. Suppress it until the next keystroke re-opens it.
    var suggestionsDismissed by remember { mutableStateOf(false) }
    val valueFocus = remember { FocusRequester() }
    // The value is what the user acts on the instant the modal opens, so it takes focus, not the dropdown.
    LaunchedEffect(Unit) { valueFocus.requestFocus() }
    // The value field's measured bounds, so the floating suggestion popup can sit right under it and match
    // its width.
    var valueFieldSize by remember { mutableStateOf(IntSize.Zero) }
    val suggestionScroll = rememberScrollState()

    val pool = key?.let { suggestions[it] }.orEmpty()
    val visibleSuggestions = remember(key, debounced, pool) {
        val q = debounced.trim()
        when {
            // Edited's whole value space is {true,false}: always offer both, no typing required.
            key == FilterKey.Edited -> pool
            q.length < MinSuggestionChars -> emptyList()
            else -> pool.filter { it.contains(q, ignoreCase = true) }.take(MaxSuggestions)
        }
    }
    // Edited is a strict boolean; the rest just need something to match against.
    val canAdd = when (key) {
        null -> false
        FilterKey.Edited -> value.trim().lowercase() in setOf("true", "false")
        else -> value.isNotBlank()
    }
    fun commit() {
        val field = key ?: return
        val trimmed = value.trim()
        if (trimmed.isNotEmpty()) onAdd(FilterClause(field, trimmed))
    }

    Surface(
        modifier = modifier.width(320.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Add filter",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterKeyDropdown(
                    key = key,
                    onSelect = { picked ->
                        // A value valid for one field rarely fits another (and Edited only takes a boolean),
                        // so *switching* fields clears the value. Choosing the first one doesn't: there's no
                        // other field's value to be stale, and wiping it would throw away what was just typed.
                        if (key != null && key != picked) {
                            value = ""
                            debounced = ""
                        }
                        key = picked
                        suggestionsDismissed = false
                        valueFocus.requestFocus()
                    },
                )
                Box(Modifier.weight(1f).onSizeChanged { valueFieldSize = it }) {
                    CompactOutlinedTextField(
                        value = value,
                        onValueChange = {
                            value = it
                            suggestionsDismissed = false
                        },
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(valueFocus)
                            .onPreviewKeyEvent { event ->
                                when {
                                    event.type != KeyEventType.KeyDown -> false
                                    event.key == Key.Enter -> {
                                        commit()
                                        true
                                    }
                                    event.key == Key.Escape -> {
                                        onCancel()
                                        true
                                    }
                                    else -> false
                                }
                            },
                        placeholder = if (key == FilterKey.Edited) "true or false" else "Value (contains)",
                    )
                    if (visibleSuggestions.isNotEmpty() && !suggestionsDismissed) {
                        // A non-focusable popup so typing continues in the field. It floats in its own layer
                        // (above the card), so it never grows the modal; the inner column caps its height
                        // and scrolls.
                        Popup(
                            alignment = Alignment.TopStart,
                            offset = IntOffset(0, valueFieldSize.height + with(density) { 4.dp.roundToPx() }),
                            properties = PopupProperties(focusable = false),
                            onDismissRequest = {},
                        ) {
                            Surface(
                                Modifier.width(with(density) { valueFieldSize.width.toDp() }),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 3.dp,
                                shadowElevation = 8.dp,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(Modifier.heightIn(max = 180.dp).verticalScroll(suggestionScroll)) {
                                    visibleSuggestions.forEach { suggestion ->
                                        Text(
                                            suggestion,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth()
                                                // Only fills the field — adding stays an explicit Add/Enter,
                                                // so a mis-click costs a correction, not a committed clause.
                                                .clickable {
                                                    value = suggestion
                                                    debounced = suggestion
                                                    suggestionsDismissed = true
                                                    valueFocus.requestFocus()
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = { commit() }, enabled = canAdd) { Text("Add") }
            }
        }
    }
}

// A read-only compact field + [DropdownMenu] for the clause's target field, mirroring the method pickers
// elsewhere so it lines up with the value field beside it by construction (one shared decoration). A null
// [key] hands the decoration an empty value, so it renders the placeholder in its muted style — the same
// treatment an empty text field gets, and "nothing chosen yet" reads the same across the whole modal.
@Composable
private fun FilterKeyDropdown(key: FilterKey?, onSelect: (FilterKey) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Box {
        Box(
            Modifier.clip(RoundedCornerShape(4.dp))
                .clickable(interactionSource = interactionSource, indication = null) { expanded = true },
        ) {
            CompactFieldDecoration(
                value = key?.label.orEmpty(),
                interactionSource = interactionSource,
                placeholder = "Field\u2026",
                trailingIcon = {
                    Icon(
                        vectorResource(Res.drawable.ic_arrow_drop_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                innerTextField = {
                    if (key != null) {
                        Text(
                            key.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FilterKey.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

// An outline chip, matching the bookmark bar's quiet chip, that opens the add-filter modal.
@Composable
private fun AddFilterChip(onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = vectorResource(Res.drawable.ic_add),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            "Add filter",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One active-filter chip. It reads as a selected filter, so it borrows the bookmark bar's *selected* chip
 * styling exactly — filled with the accent, [onPrimary] content — rather than a quiet outline. The
 * trailing × clears just this clause.
 */
@Composable
private fun FilterPill(text: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp),
        )
        Box(
            Modifier.size(18.dp).clip(CircleShape).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_close),
                contentDescription = "Remove filter",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
