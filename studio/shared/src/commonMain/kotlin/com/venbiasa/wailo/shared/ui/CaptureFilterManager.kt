package com.venbiasa.wailo.shared.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.CaptureFilterState
import com.venbiasa.wailo.shared.format.isValidHostPattern
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_add
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import com.venbiasa.wailo.shared.resources.ic_delete
import org.jetbrains.compose.resources.vectorResource

// Item rows and the add field sit indented under their section's header band, echoing how a Map Local
// group's rules are inset from the group header — the only cue that a host belongs to a section.
private val SectionItemIndent = 16.dp

/**
 * The capture-filter panel: two host-pattern lists — an allowlist and a blocklist — each with its own
 * on/off switch, that decide which traffic devices capture and stream (ADR-0029). Modeled on Map Local's
 * groups: each list is a header band (a collapse caret, its switch, and a `+` to add a host) over its item
 * rows (a host with a delete affordance), collapsible like a group but with no drag and no per-item switch
 * — the whole list is armed by its one switch.
 *
 * Stateless over its inputs: the host owns [filter] and its persistence and pushes it to devices; this
 * renders it and hands back a new [CaptureFilterState] for every change via [onFilterChange] (add/remove a
 * host, flip a list, or flip the whole feature). A list's switch is disabled while it has no entries, so an
 * empty list can't be armed. `*` matches any run of characters, so `*.example.com` covers a whole subdomain.
 * The header switch is the feature master (ADR-0030): off dims both lists and disables their switches while
 * keeping their hosts and armed state, and the host then captures everything. It fills whatever surface it's
 * given (the studio's right tool panel).
 */
@Composable
internal fun CaptureFilterManager(
    filter: CaptureFilterState,
    onFilterChange: (CaptureFilterState) -> Unit,
    onClose: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    // A background surface with a surfaceContainer header band (not a fully-tinted panel) so the top bar
    // matches the other panels exactly (same fill and the shared [TopBarHeight]). Tapping empty space
    // drops any open add-field's focus — the click-away counterpart to Esc; child controls hit-test first.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .height(TopBarHeight)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Capture Filter",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                // The feature master heads the right-side cluster, before Close: one switch arms/disarms the
                // whole filter, above the two per-list switches below (ADR-0030). Off keeps every host and
                // each list's armed state — it only pauses what the host pushes — so flipping it back on
                // restores what was armed.
                HoverTooltip(if (filter.masterEnabled) "On" else "Off") {
                    CompactSwitch(
                        checked = filter.masterEnabled,
                        onCheckedChange = { onFilterChange(filter.setMasterEnabled(it)) },
                    )
                }
                Spacer(Modifier.width(4.dp))
                CloseButton(onClose, contentDescription = "Close capture filter")
            }
            RowDivider()

            // Dim + disarm the lists while the master is off: the sections stay readable but plainly inert,
            // and their switches go non-interactive (each list keeps its own armed state underneath).
            Column(
                Modifier.fillMaxSize()
                    .alpha(if (filter.masterEnabled) 1f else DisabledFeatureAlpha)
                    .verticalScroll(rememberScrollState()),
            ) {
                MutedText(
                    "Choose which hosts devices capture: an allowlist captures only matches, a blocklist skips them.",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                )
                RowDivider()
                FilterSection(
                    title = "Allowlist",
                    enabled = filter.allowEnabled,
                    armable = filter.masterEnabled,
                    hosts = filter.allowHosts,
                    emptyText = "No hosts — add one to capture only those hosts.",
                    addTooltip = "Add to allowlist",
                    onToggleEnabled = { onFilterChange(filter.setAllowEnabled(it)) },
                    onAddHost = { onFilterChange(filter.addAllow(it)) },
                    onRemoveHost = { onFilterChange(filter.removeAllow(it)) },
                )
                FilterSection(
                    title = "Blocklist",
                    enabled = filter.blockEnabled,
                    armable = filter.masterEnabled,
                    hosts = filter.blockHosts,
                    emptyText = "No hosts — add one to skip capturing it.",
                    addTooltip = "Add to blocklist",
                    onToggleEnabled = { onFilterChange(filter.setBlockEnabled(it)) },
                    onAddHost = { onFilterChange(filter.addBlock(it)) },
                    onRemoveHost = { onFilterChange(filter.removeBlock(it)) },
                )
            }
        }
    }
}

// One list section: a header band (caret + switch + title + count + add) over its host rows, mirroring a
// Map Local group. The switch is disabled while the list is empty (an empty list can't be armed); the
// caret folds the host rows away (its count stays visible as the tucked-away cue); the `+` reveals an
// inline add field (and expands the section if it was collapsed, so the field is never added out of view).
// Both `adding` and `collapsed` are this section's own transient, per-session view state.
@Composable
private fun FilterSection(
    title: String,
    enabled: Boolean,
    armable: Boolean,
    hosts: List<String>,
    emptyText: String,
    addTooltip: String,
    onToggleEnabled: (Boolean) -> Unit,
    onAddHost: (String) -> Unit,
    onRemoveHost: (String) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The caret points down when open, right when collapsed; tapping it hides/shows the host rows.
            val chevron by animateFloatAsState(if (collapsed) -90f else 0f, label = "sectionChevron")
            IconButton(onClick = { collapsed = !collapsed }, modifier = Modifier.size(28.dp)) {
                Icon(
                    vectorResource(Res.drawable.ic_arrow_drop_down),
                    contentDescription = if (collapsed) "Expand $title" else "Collapse $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).rotate(chevron),
                )
            }
            // An empty list forces its switch off and disabled (see CaptureFilterState); a filled list's
            // switch reflects and toggles the list's armed state. [armable] is the feature master: while
            // it's off every list switch is non-interactive too, so the master truly disables the lists.
            HoverTooltip(if (hosts.isEmpty()) "Add a host to enable" else if (enabled) "On" else "Off") {
                CompactSwitch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled,
                    enabled = armable && hosts.isNotEmpty(),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (hosts.isNotEmpty()) {
                Text(
                    "${hosts.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            HoverTooltip(addTooltip) {
                IconButton(
                    // Adding while collapsed would drop the field out of view, so reveal the section too.
                    onClick = {
                        collapsed = false
                        adding = true
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        vectorResource(Res.drawable.ic_add),
                        contentDescription = addTooltip,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        RowDivider()

        if (!collapsed) {
            if (adding) {
                AddHostField(
                    onAdd = onAddHost,
                    onClose = { adding = false },
                )
                RowDivider()
            }
            if (hosts.isEmpty() && !adding) {
                MutedText(emptyText, Modifier.fillMaxWidth().padding(start = SectionItemIndent, end = 8.dp, top = 10.dp, bottom = 10.dp))
            } else {
                hosts.forEach { host ->
                    HostRow(host = host, onRemove = { onRemoveHost(host) })
                    RowDivider()
                }
            }
        }
    }
}

@Composable
private fun HostRow(host: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = SectionItemIndent, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            host,
            modifier = Modifier.weight(1f),
            style = monoSmall(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_delete),
                contentDescription = "Remove $host",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// The inline add affordance revealed by a section's `+`: an autofocused field that validates only on
// commit (Enter), so a partially-typed host can't flash red. A valid host is added and the field clears
// but keeps focus for rapid entry; Esc or clicking away closes it. Mirrors the group-name inline editor.
@Composable
private fun AddHostField(onAdd: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var hasFocused by remember { mutableStateOf(false) }
    val submit = {
        val trimmed = text.trim()
        if (isValidHostPattern(trimmed)) {
            onAdd(trimmed)
            text = ""
            showError = false
        } else {
            showError = true
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(start = SectionItemIndent, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CompactOutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                showError = false
            },
            modifier = Modifier.fillMaxWidth()
                .focusRequester(focusRequester)
                // Ignore the initial unfocused callback before requestFocus lands; only a real blur (after
                // the field has held focus) closes the add row — the "click away to dismiss".
                .onFocusChanged { state -> if (state.isFocused) hasFocused = true else if (hasFocused) onClose() }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            submit()
                            true
                        }
                        Key.Escape -> {
                            focusManager.clearFocus()
                            true
                        }
                        else -> false
                    }
                },
            placeholder = "host or *.example.com",
            isError = showError,
        )
        if (showError) {
            Text(
                "Enter a domain like example.com or *.example.com.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
