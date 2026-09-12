package com.venbiasa.wailo.shared.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.BreakpointNode
import com.venbiasa.wailo.shared.BreakpointRuleDef
import com.venbiasa.wailo.shared.RuleGroup
import com.venbiasa.wailo.shared.assignRuleToGroup
import com.venbiasa.wailo.shared.findRule
import com.venbiasa.wailo.shared.groupOf
import com.venbiasa.wailo.shared.groups
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_arrow_back
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import com.venbiasa.wailo.shared.resources.ic_note_add
import com.venbiasa.wailo.shared.resources.ic_open_in_new
import com.venbiasa.wailo.shared.setRuleEnabled
import com.venbiasa.wailo.shared.upsertRule
import org.jetbrains.compose.resources.vectorResource

/**
 * The breakpoints panel: the rules that pause matching traffic so it can be edited live in the paused
 * editor (ADR-0027). A grouped rule list (the shared [GroupedRuleListPage], same as Map Local, ADR-0026)
 * that falls through to a rule editor. Stateless over its inputs — the host owns [nodes] and its
 * persistence, and pushes the active rules to devices; this renders them and hands back a new layout via
 * [onLayoutChange] for any structural change. A rule matches on a URL wildcard (`*`) + optional method and
 * can break on the request (before it's sent), the response (before the app sees it), or both. It fills
 * whatever surface it's given (the studio's right tool panel).
 *
 * The list drags and groups exactly like Map Local's and Seed's, but its order is arrangement rather than
 * priority: every matching rule pauses the exchange, so there is no first match to promote (ADR-0098
 * keeps the grips anyway — a set you can arrange is easier to read than one you can't). A rule joins a
 * group by being dragged into it, or through the picker in its editor.
 *
 * [enabled] is the feature master (ADR-0030): off dims the list and disables every rule/group switch (and
 * the editor's), their remembered state kept, while the host pushes no rules — so breakpoints go inert
 * without erasing what's configured. [onEnabledChange] flips it.
 *
 * [initialDraft] seeds the editor: non-null opens straight into the form (used when launched from a traffic
 * row so the URL/method are pre-filled, mirroring Map Local), null shows the list.
 *
 * [onOpenWindow] raises the paused-exchange window from the list header. The window used to exist only
 * while something was held; it's now user-owned (ADR-0041), and this is where you open it with nothing
 * paused — to arm the seed queue before the traffic you want it to answer arrives.
 */
@Composable
internal fun BreakpointManager(
    nodes: List<BreakpointNode>,
    onLayoutChange: (List<BreakpointNode>) -> Unit,
    initialDraft: BreakpointRuleDef? = null,
    enabled: Boolean = true,
    onEnabledChange: (Boolean) -> Unit = {},
    onOpenWindow: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    // Which groups are collapsed — transient view state, hoisted so it survives entering the editor and
    // coming back; not persisted (relaunch shows every group expanded), mirroring Map Local.
    val collapsedGroups = remember { mutableStateListOf<String>() }
    // The rule loaded into the editor page; null shows the list. A new (add) or list-tapped rule lives
    // here until Save persists it into [nodes]. Transient session state.
    var editing by remember { mutableStateOf<BreakpointRuleDef?>(null) }

    // A row's "Breakpoints…" hands over a fresh draft, possibly while the panel already shows another page;
    // load it so the panel lands on the editor, leaving Back pointing at the rule list.
    LaunchedEffect(initialDraft) {
        if (initialDraft != null) editing = initialDraft
    }

    val target = editing
    if (target == null) {
        GroupedRuleListPage(
            title = "Breakpoints",
            description = "Pause matching requests or responses to inspect and edit them before they continue.",
            emptyText = "No breakpoints yet. Add a rule, or a group to organize rules you can toggle together.",
            addRuleIcon = Res.drawable.ic_note_add,
            addRuleTooltip = "New breakpoint",
            nodes = nodes,
            featureEnabled = enabled,
            onFeatureEnabledChange = onEnabledChange,
            collapsedGroupIds = collapsedGroups,
            onAddRule = { editing = BreakpointRuleDef(id = BreakpointRuleDef.newId()) },
            onEditRule = { editing = it },
            onNodesChange = onLayoutChange,
            onClose = onClose,
            headerActions = {
                PanelIconButton(
                    icon = Res.drawable.ic_open_in_new,
                    contentDescription = "Open breakpoint window",
                    onClick = onOpenWindow,
                )
            },
        ) { rule -> BreakpointRuleContent(rule) }
    } else {
        // The enabled toggle is shared with the list row, so for a persisted rule it commits immediately
        // (like Map Local) rather than waiting for Save; a not-yet-saved draft toggles its own state. A
        // rule inside an off group — or under an off feature master — can't be enabled here (they gate it,
        // its own state preserved).
        val persisted = nodes.findRule(target.id)
        val groupEnabled = nodes.groupOf(target.id)?.enabled ?: true
        // Keyed on the rule so its form state is rebuilt when a different rule is loaded into an already-open
        // editor (a row's "Breakpoints…" can do that): the fields remember [initial] on first composition, so
        // without this they'd keep showing the rule the editor opened on.
        key(target.id) {
            BreakpointRuleEditor(
                initial = target,
                groups = nodes.groups(),
                initialGroupId = nodes.groupOf(target.id)?.id,
                enabled = (persisted ?: target).enabled,
                enabledToggleable = enabled && groupEnabled,
                onToggleEnabled = { next ->
                    if (nodes.findRule(target.id) != null) onLayoutChange(nodes.setRuleEnabled(target.id, next))
                    else editing = target.copy(enabled = next)
                },
                onSave = { rule, groupId ->
                    onLayoutChange(nodes.upsertRule(rule).assignRuleToGroup(rule.id, groupId))
                    editing = null
                },
                onBack = { editing = null },
                onClose = onClose,
            )
        }
    }
}

// A breakpoint rule row's label (rendered in the shared row scaffold's clickable column): the rule's name
// over a method + phase(s) + URL summary — the same stack Map Local's rows use, now that a breakpoint rule
// carries a name of its own.
@Composable
private fun BreakpointRuleContent(rule: BreakpointRuleDef) {
    Text(
        rule.name.ifBlank { "Untitled" },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        ruleSummary(rule),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The breakpoint rule editor page: the rule's name and group, its match (URL pattern + method), and which
 * phase(s) it pauses. [enabled]/[onToggleEnabled] drive the header switch (committed by the host, gated by
 * [enabledToggleable] when the rule sits in an off group). Save is blocked until the rule has a name and a
 * URL pattern and at least one phase is chosen — a rule that pauses on neither phase never fires.
 *
 * [groups]/[initialGroupId] back the group picker, and [onSave] reports the chosen group beside the rule.
 * Filing a rule from here reaches a group that is scrolled far from the rule, or collapsed, without the
 * drag that would otherwise be the only way in; it lands the rule at the end of the group, since where
 * inside it the rule sits is what the drag is for.
 */
@Composable
private fun BreakpointRuleEditor(
    initial: BreakpointRuleDef,
    groups: List<RuleGroup>,
    initialGroupId: String?,
    enabled: Boolean,
    enabledToggleable: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onSave: (BreakpointRuleDef, String?) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var name by remember { mutableStateOf(initial.name) }
    var urlPattern by remember { mutableStateOf(initial.urlPattern) }
    var method by remember { mutableStateOf(initial.method) }
    var groupId by remember { mutableStateOf(initialGroupId) }
    var onRequest by remember { mutableStateOf(initial.onRequest) }
    var onResponse by remember { mutableStateOf(initial.onResponse) }
    var showError by remember { mutableStateOf(false) }

    // A rule fires only if it can pause at least one phase; enforce that (and a non-blank name and
    // pattern) here.
    val canSave = name.isNotBlank() && urlPattern.isNotBlank() && (onRequest || onResponse)
    fun save() {
        val trimmedName = name.trim()
        val trimmed = urlPattern.trim()
        if (trimmedName.isNotEmpty() && trimmed.isNotEmpty() && (onRequest || onResponse)) {
            onSave(
                initial.copy(
                    name = trimmedName,
                    enabled = enabled,
                    urlPattern = trimmed,
                    method = method,
                    onRequest = onRequest,
                    onResponse = onResponse,
                ),
                groupId,
            )
        } else {
            showError = true
        }
    }

    Column(Modifier.fillMaxSize()) {
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
                    contentDescription = "Back to breakpoints",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Breakpoint",
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

        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EditorField(
                "Name",
                error = if (showError && name.isBlank()) "Name can\u2019t be empty" else null,
            ) {
                CompactOutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        showError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Untitled",
                    isError = showError && name.isBlank(),
                )
            }
            EditorField("URL pattern") {
                CompactOutlinedTextField(
                    value = urlPattern,
                    onValueChange = {
                        urlPattern = it
                        showError = false
                    },
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                save()
                                true
                            }
                            Key.Escape -> {
                                focusManager.clearFocus()
                                true
                            }
                            else -> false
                        }
                    },
                    placeholder = "https://api.example.com/*",
                    isError = showError && urlPattern.isBlank(),
                )
            }
            // The method picker hugs its content; the group's name is free text, so it takes the rest.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EditorField("Method") {
                    MethodDropdown(method = method, onSelect = { method = it })
                }
                EditorField("Group", Modifier.weight(1f)) {
                    GroupDropdown(
                        groups = groups,
                        groupId = groupId,
                        onSelect = { groupId = it },
                    )
                }
            }
            EditorField("Pause on") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhaseToggle("Request", onRequest) {
                        onRequest = it
                        showError = false
                    }
                    PhaseToggle("Response", onResponse) {
                        onResponse = it
                        showError = false
                    }
                }
            }
            // One sentence for the match itself, since a pattern and a phase are the two halves of the
            // same "this rule can never fire" mistake. A missing name is its own field's error instead.
            if (showError && (urlPattern.isBlank() || !(onRequest || onResponse))) {
                Text(
                    "Enter a URL pattern and pause the request, the response, or both.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.weight(1f))
        RowDivider()
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { save() }, enabled = canSave) {
                Text("Save")
            }
        }
    }
}

// Label over its field, matching the compact tool-panel form spacing used elsewhere.
@Composable
private fun EditorField(
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

@Composable
private fun PhaseToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactSwitch(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// One-line caption under a rule: the method it's scoped to, which phase(s) it pauses on, and the URL it
// matches — the URL last (and end-truncated) because the name above it is what identifies the rule now.
private fun ruleSummary(rule: BreakpointRuleDef): String {
    val phases = when {
        rule.onRequest && rule.onResponse -> "Request & Response"
        rule.onRequest -> "Request"
        rule.onResponse -> "Response"
        else -> "Never"
    }
    return methodLabel(rule.method) + "  \u00b7  " + phases + "  \u2192  " + rule.urlPattern.ifBlank { "(no pattern)" }
}

// A rule matches a single HTTP method; "Any" (blank) leaves the method unconstrained. Blank leads so it
// reads as the "no restriction" default, then the common methods in request-frequency order. Mirrors the
// Map Local editor's picker (kept file-local to avoid coupling the two panels).
private val MethodOptions = listOf("", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

private fun methodLabel(method: String): String = method.ifBlank { "Any" }

@Composable
private fun MethodDropdown(method: String, onSelect: (String) -> Unit) {
    CompactPicker(
        selectedLabel = methodLabel(method),
        options = MethodOptions.map { it to methodLabel(it) },
        onSelect = onSelect,
    )
}

// The group a rule is filed into, chosen rather than dragged. A null id is the top level, which leads the
// menu as the "not in a group" default the way a blank method does.
@Composable
private fun GroupDropdown(
    groups: List<RuleGroup>,
    groupId: String?,
    onSelect: (String?) -> Unit,
) {
    val selected = groups.firstOrNull { it.id == groupId }
    CompactPicker(
        selectedLabel = if (selected == null) NoGroupLabel else groupLabel(selected),
        options = listOf<Pair<String?, String>>(null to NoGroupLabel) + groups.map { it.id to groupLabel(it) },
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val NoGroupLabel = "None"

private fun groupLabel(group: RuleGroup): String = group.name.ifBlank { "New group" }

// The compact read-only picker behind both the method and the group field: a field-shaped click target
// that opens a menu of value-to-label [options]. Both go through one composable so they keep the same
// height and hit behaviour as the text fields beside them (see CompactFieldDecoration).
@Composable
private fun <T> CompactPicker(
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier) {
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(interactionSource = interactionSource, indication = null) { expanded = true },
        ) {
            CompactFieldDecoration(
                value = selectedLabel,
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
                        selectedLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}
