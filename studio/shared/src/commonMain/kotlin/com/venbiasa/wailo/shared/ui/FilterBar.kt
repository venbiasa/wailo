package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
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
import com.venbiasa.wailo.shared.FilterMatcher
import com.venbiasa.wailo.shared.MatcherOption
import com.venbiasa.wailo.shared.QueryCompletion
import com.venbiasa.wailo.shared.TrafficFilter
import com.venbiasa.wailo.shared.acceptsMultipleValues
import com.venbiasa.wailo.shared.completionAt
import com.venbiasa.wailo.shared.defaultMatcher
import com.venbiasa.wailo.shared.label
import com.venbiasa.wailo.shared.matcherOptions
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_add
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import com.venbiasa.wailo.shared.resources.ic_close
import com.venbiasa.wailo.shared.resources.ic_help
import com.venbiasa.wailo.shared.resources.ic_search
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.vectorResource

// How long the value field must sit idle before its autocomplete list recomputes, so fast typing doesn't
// refilter on every keystroke. Short enough to feel instant once the user pauses.
private const val AutocompleteDebounceMillis = 180L

// The query box edits a local copy and only publishes the filter once typing pauses: every published
// query is re-parsed and re-run over the whole capture, so a per-keystroke push would refilter the list
// several times for one word. Slightly longer than the autocomplete debounce — this one costs a full pass.
private const val KeywordDebounceMillis = 250L

// Don't offer suggestions in the modal until the user has typed enough to be discriminating — a 1-2 char
// prefix would match most of a busy capture, so wait for a real stem. No field is exempt: Edited used to
// show its two values unprompted, which meant one field popped a list open the moment it was picked.
private const val MinSuggestionChars = 3

// A hard cap so a broad stem on a large capture can't build thousands of rows behind the scroll.
private const val MaxSuggestions = 50

/**
 * The traffic list's view-filter bar (Kibana-style), shown above the list only while open (Cmd/Ctrl+F
 * opens it, like the code editor's find bar). Row 1 is the query box — debounced, so a word costs one
 * pass over the capture rather than one per keystroke — a `+ Add filter` entry point, and a close
 * button; structured filters appear only as dismissible pills on row 2 once added. The bar is stateless
 * over the [filter] — the viewer owns it (transient view state) — and defers the add-filter modal to the
 * viewer (via [onRequestAddFilter]) so its scrim can dim only the left pane.
 *
 * The box and the pills are two independent surfaces AND-ed by the same evaluator (ADR-0054), so neither
 * has to render into the other: typing never rewrites a pill, and adding a pill never rewrites the box.
 *
 * The box completes as you type — field names first, then that field's values out of [suggestions] — so
 * the language is discoverable by using it rather than only from the syntax card. ↑/↓ walk the list,
 * Enter takes the highlighted one, Esc puts it away.
 *
 * [onClose] clears every filter and hides the bar (so a hidden bar never leaves a list silently filtered);
 * it is wired to both the close button and Escape while the query field has focus. [focusSignal] pulls
 * the caret into the query field on open and on every later Cmd+F (same contract as the find bar).
 */
@Composable
internal fun FilterBar(
    filter: TrafficFilter,
    onFilterChange: (TrafficFilter) -> Unit,
    suggestions: (FilterKey, FilterMatcher) -> List<String>,
    onRequestAddFilter: () -> Unit,
    onClose: () -> Unit,
    focusSignal: Int,
) {
    val density = LocalDensity.current
    val keywordFocus = remember { FocusRequester() }
    // Runs on first composition (the bar exists only while open, so opening focuses the query box) and
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

    // Completion reads the live text rather than the debounced copy: it describes where the caret is, so
    // a lagging copy would splice an accepted suggestion into a string that has already moved on.
    val completion = remember(keyword) { completionAt(keyword) }
    val pool = completion?.key?.let { suggestions(it, completion.matcher) }
    val completions = remember(completion, pool) { completionsFor(completion, pool) }
    // Taking a suggestion fills the term it was offered for, leaving the list open over its own exact
    // match; it stays shut until the next keystroke.
    var completionsDismissed by remember { mutableStateOf(false) }
    val completionsOpen = completions.isNotEmpty() && !completionsDismissed
    var highlighted by remember { mutableStateOf(0) }
    LaunchedEffect(completions) { highlighted = 0 }
    // Lazy (rather than the modal's scrolling column) purely so the keyboard can drag the viewport along
    // with a highlight that has walked past the bottom of it.
    val completionScroll = rememberLazyListState()
    LaunchedEffect(highlighted, completionsOpen) {
        // Coerced because a list that shrank under the highlight is reset by a *sibling* effect, and
        // nothing orders the two.
        if (completionsOpen) completionScroll.animateScrollToItem(highlighted.coerceAtMost(completions.lastIndex))
    }
    fun accept(suggestion: String) {
        val at = completion ?: return
        // A value with a space in it has to come back quoted, or the scanner reads it as two terms.
        keyword = keyword.take(at.start) +
            if (suggestion.any { it.isWhitespace() }) "\"$suggestion\"" else suggestion
        completionsDismissed = true
    }
    // The query field's measured bounds, so the completion popup can sit under it and match its width.
    var queryFieldSize by remember { mutableStateOf(IntSize.Zero) }

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
            Box(Modifier.weight(1f).onSizeChanged { queryFieldSize = it }) {
                CompactOutlinedTextField(
                    value = keyword,
                    onValueChange = {
                        keyword = it
                        completionsDismissed = false
                    },
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(keywordFocus)
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type != KeyEventType.KeyDown -> false
                                // Esc puts the completion list away first and only closes the bar once
                                // there is none, so dismissing a menu can't also discard the query behind
                                // it. With no list up it clears every filter and closes (req: same as the
                                // close button).
                                event.key == Key.Escape -> {
                                    if (completionsOpen) completionsDismissed = true else onClose()
                                    true
                                }
                                // Everything below belongs to the list; without one they fall through to
                                // the field's own editing.
                                !completionsOpen -> false
                                event.key == Key.DirectionDown -> {
                                    highlighted = (highlighted + 1) % completions.size
                                    true
                                }
                                event.key == Key.DirectionUp -> {
                                    highlighted = (highlighted + completions.size - 1) % completions.size
                                    true
                                }
                                event.key == Key.Enter -> {
                                    completions.getOrNull(highlighted)?.let { accept(it) }
                                    true
                                }
                                else -> false
                            }
                        },
                    placeholder = "Search or query\u2026",
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
                    // The query language isn't guessable from an empty box, so its reference lives at the
                    // far end of the very field it describes.
                    trailingIcon = { QuerySyntaxHelp() },
                )
                if (completionsOpen) {
                    // Non-focusable so typing carries on in the field behind it, and floating so a long
                    // list never pushes the traffic list down the window.
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, queryFieldSize.height + with(density) { 4.dp.roundToPx() }),
                        properties = PopupProperties(focusable = false),
                        onDismissRequest = {},
                    ) {
                        Surface(
                            Modifier.width(with(density) { queryFieldSize.width.toDp() }),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 3.dp,
                            shadowElevation = 8.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            LazyColumn(Modifier.heightIn(max = 220.dp), state = completionScroll) {
                                itemsIndexed(completions) { index, suggestion ->
                                    Text(
                                        suggestion,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth()
                                            .background(
                                                if (index == highlighted) {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                } else {
                                                    Color.Transparent
                                                },
                                            )
                                            .clickable { accept(suggestion) }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
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
                        text = clause.label(),
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
 * What the query box offers for [completion]: field names while one is still being named, otherwise the
 * values actually seen for the field already named ([pool]).
 *
 * Values have no minimum-prefix gate, unlike the modal's — naming a field has already narrowed the pool
 * to one column, so all of it is worth seeing the moment the operator lands. Field names do wait for a
 * first character, or a menu would spring open after every word the user finishes.
 */
private fun completionsFor(completion: QueryCompletion?, pool: List<String>?): List<String> = when {
    completion == null -> emptyList()
    completion.key == null ->
        if (completion.prefix.isBlank()) {
            emptyList()
        } else {
            FilterKey.entries.map { "${it.token}:" }
                .filter { it.startsWith(completion.prefix, ignoreCase = true) }
        }
    else -> pool.orEmpty().asSequence()
        .filter { completion.prefix.isBlank() || it.contains(completion.prefix, ignoreCase = true) }
        .take(MaxSuggestions)
        .toList()
}

// The query box's reference card, one example per thing the language can do. Examples are the whole
// point — the grammar is small enough that a worked line teaches it faster than a description would.
private val QuerySyntaxExamples = listOf(
    "checkout" to "Any word: searches URL, method and code",
    "url:orders" to "Field contains",
    "method = GET" to "Is exactly",
    "-url:analytics" to "Not (or method != GET)",
    "status >= 400" to "Compare (also >, <, <=)",
    "url = https://api.example.com/*" to "Wildcard",
    "url:/orders\\/\\d+/" to "Regular expression",
    "method:(GET or POST)" to "Any of",
)

/** The `?` at the end of the query box, opening the syntax reference under it. */
@Composable
private fun QuerySyntaxHelp() {
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Box(Modifier.padding(end = 10.dp)) {
        HoverTooltip("Query syntax") {
            Box(
                Modifier.size(20.dp).clip(CircleShape).clickable { open = !open },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_help),
                    contentDescription = "Query syntax",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (open) {
            // Anchored to the icon's end so a card far wider than its 20.dp anchor opens inwards, over
            // the list, instead of off the right edge of the window.
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(density) { 26.dp.roundToPx() }),
                properties = PopupProperties(focusable = true),
                onDismissRequest = { open = false },
            ) {
                Surface(
                    Modifier.width(390.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Terms are combined with AND",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        QuerySyntaxExamples.forEach { (example, description) ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    example,
                                    Modifier.width(180.dp),
                                    style = monoSmall(),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                MutedText(description, Modifier.weight(1f))
                            }
                        }
                        MutedText(
                            "Fields: " + FilterKey.entries.joinToString { it.token },
                            Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The `+ Add filter` modal body. Rendered by the viewer over a scrim that dims only the left pane, so it
 * is a standalone card here (the viewer owns placement + the scrim). A field dropdown — opening on no
 * selection, so nothing is filtered under a default the user never picked — then a matcher dropdown
 * carrying that field's comparisons, and only then the value, which appears once there is a comparison
 * for it to belong to.
 *
 * The value field's autocomplete is built from [suggestions]: the distinct values actually seen for that
 * field *under that matcher*, since a substring test and an exact one want different pools. It is
 * debounced and withheld until [MinSuggestionChars] have been typed, so it opens on a real stem rather
 * than on the first keystroke. That list is a floating [Popup] anchored under the value field, so it
 * never resizes the card and scrolls past a bounded height, and picking from it only fills the field. A
 * comma turns what's typed into a value chip, which is how one clause comes to match any of several
 * values. Committing stays explicit: [onAdd] runs on Add or Enter and appends the clause; [onCancel]
 * dismisses, and is what Escape does from anywhere in the card.
 */
@Composable
internal fun AddFilterCard(
    suggestions: (FilterKey, FilterMatcher) -> List<String>,
    onAdd: (FilterClause) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // No field is preselected: the modal opens asking which one, so nothing is added under a default the
    // user never chose. Null renders the dropdown's placeholder and leaves Add disabled.
    var key by remember { mutableStateOf<FilterKey?>(null) }
    var option by remember { mutableStateOf<MatcherOption?>(null) }
    // Values already committed with a comma; the field below holds the one still being typed.
    var chips by remember { mutableStateOf(emptyList<String>()) }
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
    // The value field's measured bounds, so the floating suggestion popup can sit right under it and match
    // its width.
    var valueFieldSize by remember { mutableStateOf(IntSize.Zero) }
    val suggestionScroll = rememberScrollState()

    val selectedKey = key
    val matcher = option?.matcher
    // A value is meaningless without something to compare it to, so it doesn't exist until the comparison
    // does: the card opens as one question (which field?) instead of a form with a box you can't yet fill.
    val valueVisible = selectedKey != null && matcher != null
    // Only a text comparison has a set to be "one of": a number or a boolean takes exactly one value.
    val multiValued = selectedKey?.acceptsMultipleValues == true && matcher?.isNumeric == false
    val pending = value.trim()
    val allValues = if (pending.isEmpty()) chips else chips + pending

    val cardFocus = remember { FocusRequester() }
    val valueFocus = remember { FocusRequester() }
    // Nothing in the card is typeable until a field is picked, so the card itself takes focus on open.
    // Otherwise focus stays in the query box behind the scrim, where Escape would close the whole filter
    // bar out from under the modal and every keystroke would land in the query the scrim is dimming.
    LaunchedEffect(Unit) { cardFocus.requestFocus() }
    // Focus can only be pulled into the value field *after* the composition that adds it, so this can't be
    // a requestFocus() inside the pickers' onSelect — there is no field yet at that point. The counter
    // re-arms the same effect for the later picks, where the field is already on screen.
    var valueFocusRequests by remember { mutableStateOf(0) }
    LaunchedEffect(valueVisible, valueFocusRequests) {
        if (valueVisible) valueFocus.requestFocus()
    }

    val pool = if (selectedKey != null && matcher != null) suggestions(selectedKey, matcher) else emptyList()
    // Filtered off [debounced], so a fast typist refilters the pool once at the end of a word rather than
    // once per keystroke, and gated on [MinSuggestionChars] so the list can't open on the first one.
    val visibleSuggestions = remember(key, matcher, debounced, pool) {
        val q = debounced.trim()
        if (q.length < MinSuggestionChars) {
            emptyList()
        } else {
            pool.filter { it.contains(q, ignoreCase = true) }.take(MaxSuggestions)
        }
    }
    // Each matcher only accepts what it can actually compare: a comparison needs a number, a regex needs
    // to compile. Catching it here means an unusable clause can't be committed and then silently match
    // nothing from a pill that looks fine.
    val canAdd = when {
        selectedKey == null || matcher == null || allValues.isEmpty() -> false
        selectedKey.isBoolean ->
            allValues.size == 1 && allValues.first().lowercase() in setOf("true", "false")
        matcher.isNumeric -> allValues.size == 1 && allValues.first().toIntOrNull() != null
        matcher == FilterMatcher.Regex -> allValues.all { runCatching { Regex(it) }.isSuccess }
        else -> true
    }
    fun commit() {
        val picked = option ?: return
        if (selectedKey == null || !canAdd) return
        onAdd(FilterClause(selectedKey, picked.matcher, allValues, picked.negated))
    }

    Surface(
        // Escape is owned by the card, not by the value field: the field isn't there when the modal opens,
        // and a preview handler above every child catches the key wherever focus has since travelled.
        modifier = modifier.width(400.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onCancel()
                    true
                } else {
                    false
                }
            }
            .focusRequester(cardFocus)
            .focusable(),
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
            // Both pickers wrap their own current label instead of sharing the row's width, so they sit
            // against each other and read as one phrase — field, then comparison. The cost is that the
            // matcher shifts when a longer field is chosen; that's accepted, because stretched to fill
            // they read as two unrelated controls at opposite ends of a gap.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PickerField(
                    label = key?.label,
                    placeholder = "Field\u2026",
                    options = FilterKey.entries,
                    optionLabel = { it.label },
                    onSelect = { picked ->
                        // A value valid for one field rarely fits another (and Edited only takes a boolean),
                        // so *switching* fields clears the value. Choosing the first one doesn't: there's no
                        // other field's value to be stale, and wiping it would throw away what was just typed.
                        if (key != null && key != picked) {
                            value = ""
                            debounced = ""
                            chips = emptyList()
                        }
                        key = picked
                        option = picked.defaultMatcher
                        suggestionsDismissed = false
                        valueFocusRequests += 1
                    },
                )
                PickerField(
                    label = option?.label,
                    placeholder = "Matcher\u2026",
                    options = key?.matcherOptions.orEmpty(),
                    optionLabel = { it.label },
                    onSelect = { picked ->
                        // Narrowing to a single-value matcher would otherwise leave chips behind that the
                        // new comparison can't use, and Add would sit disabled with no visible reason.
                        if (picked.matcher.isNumeric) chips = emptyList()
                        option = picked
                        suggestionsDismissed = false
                        valueFocusRequests += 1
                    },
                    // Which comparisons exist depends on the field, so there is nothing to offer until
                    // one is chosen.
                    enabled = key != null,
                )
            }

            // The value waits for its comparison: until both dropdowns are answered there is nothing here
            // to type into, and an empty box under two unanswered questions only reads as a dead control.
            if (valueVisible) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (chips.isNotEmpty()) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            chips.forEachIndexed { index, chip ->
                                ValueChip(
                                    text = chip,
                                    onRemove = { chips = chips.filterIndexed { i, _ -> i != index } },
                                )
                            }
                        }
                    }
                    Box(Modifier.onSizeChanged { valueFieldSize = it }) {
                        CompactOutlinedTextField(
                            value = value,
                            onValueChange = { input ->
                                // A comma banks what precedes it as a value and leaves the rest being
                                // typed, so several values are entered without stealing Enter from Add.
                                if (multiValued && input.contains(',')) {
                                    val parts = input.split(',')
                                    chips = chips +
                                        parts.dropLast(1).map { it.trim() }.filter { it.isNotEmpty() }
                                    value = parts.last()
                                } else {
                                    value = input
                                }
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
                                        // Backspace at the start of an empty field takes back the last
                                        // banked value, the way a token field anywhere else does.
                                        event.key == Key.Backspace && value.isEmpty() &&
                                            chips.isNotEmpty() -> {
                                            chips = chips.dropLast(1)
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            placeholder = valuePlaceholder(key, matcher),
                        )
                        if (visibleSuggestions.isNotEmpty() && !suggestionsDismissed) {
                            // A non-focusable popup so typing continues in the field. It floats in its own
                            // layer (above the card), so it never grows the modal; the inner column caps
                            // its height and scrolls.
                            Popup(
                                alignment = Alignment.TopStart,
                                offset = IntOffset(
                                    0,
                                    valueFieldSize.height + with(density) { 4.dp.roundToPx() },
                                ),
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
                                    Column(
                                        Modifier.heightIn(max = 180.dp).verticalScroll(suggestionScroll),
                                    ) {
                                        visibleSuggestions.forEach { suggestion ->
                                            Text(
                                                suggestion,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.fillMaxWidth()
                                                    // Only fills the field — adding stays an explicit
                                                    // Add/Enter, so a mis-click costs a correction rather
                                                    // than a committed clause.
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
                    if (multiValued) {
                        MutedText("Separate with commas to match any of them.")
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

// What the value field asks for, which is the only hint that a matcher wants a pattern rather than a
// literal — a wildcard field that just said "Value" would invite a plain string that then matches nothing.
private fun valuePlaceholder(key: FilterKey?, matcher: FilterMatcher?): String = when {
    key?.isBoolean == true -> "true or false"
    matcher == null -> "Value"
    matcher.isNumeric -> "Number"
    matcher == FilterMatcher.Wildcard -> "Pattern with *"
    matcher == FilterMatcher.Regex -> "Regular expression"
    else -> "Value"
}

// A read-only compact field + [DropdownMenu], mirroring the method pickers elsewhere so it lines up with
// the field beside it by construction (one shared decoration). A null [label] hands the decoration an
// empty value, so it renders the placeholder in its muted style — the same treatment an empty text field
// gets, and "nothing chosen yet" reads the same across the whole modal.
@Composable
private fun <T> PickerField(
    label: String?,
    placeholder: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier) {
        Box(
            Modifier.clip(RoundedCornerShape(4.dp))
                .clickable(interactionSource = interactionSource, indication = null, enabled = enabled) {
                    expanded = true
                },
        ) {
            CompactFieldDecoration(
                value = label.orEmpty(),
                interactionSource = interactionSource,
                enabled = enabled,
                placeholder = placeholder,
                trailingIcon = {
                    Icon(
                        vectorResource(Res.drawable.ic_arrow_drop_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                innerTextField = {
                    if (label != null) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(optionLabel(entry), style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        onSelect(entry)
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
 * One banked value inside the add-filter modal. Quiet on purpose: unlike a [FilterPill] it isn't filtering
 * anything yet, so it reads as part of the form rather than as an applied filter.
 */
@Composable
private fun ValueChip(text: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 240.dp),
        )
        Box(
            Modifier.size(16.dp).clip(CircleShape).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_close),
                contentDescription = "Remove value",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(11.dp),
            )
        }
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
