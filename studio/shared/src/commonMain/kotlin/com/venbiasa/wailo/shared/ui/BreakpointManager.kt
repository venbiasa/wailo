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
import com.venbiasa.wailo.shared.findRule
import com.venbiasa.wailo.shared.groupOf
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
 * Unlike Map Local and Seed, the list isn't drag-orderable: every matching rule pauses the exchange, so
 * there is no first-match to prioritize and ordering would be a control that changes nothing.
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
            reorderable = false,
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
                enabled = (persisted ?: target).enabled,
                enabledToggleable = enabled && groupEnabled,
                onToggleEnabled = { next ->
                    if (nodes.findRule(target.id) != null) onLayoutChange(nodes.setRuleEnabled(target.id, next))
                    else editing = target.copy(enabled = next)
                },
                onSave = { rule ->
                    onLayoutChange(nodes.upsertRule(rule))
                    editing = null
                },
                onBack = { editing = null },
                onClose = onClose,
            )
        }
    }
}

// A breakpoint rule row's label (rendered in the shared row scaffold's clickable column): the URL pattern
// over a method + phase(s) summary.
@Composable
private fun BreakpointRuleContent(rule: BreakpointRuleDef) {
    Text(
        rule.urlPattern.ifBlank { "(no pattern)" },
        style = monoSmall(),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
    )
    Text(
        ruleSummary(rule),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The breakpoint rule editor page: the rule's match (URL pattern + method) and which phase(s) it pauses.
 * [enabled]/[onToggleEnabled] drive the header switch (committed by the host, gated by [enabledToggleable]
 * when the rule sits in an off group). Save is blocked until a URL pattern is set and at least one phase
 * is chosen — a rule that pauses on neither phase never fires.
 */
@Composable
private fun BreakpointRuleEditor(
    initial: BreakpointRuleDef,
    enabled: Boolean,
    enabledToggleable: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onSave: (BreakpointRuleDef) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var urlPattern by remember { mutableStateOf(initial.urlPattern) }
    var method by remember { mutableStateOf(initial.method) }
    var onRequest by remember { mutableStateOf(initial.onRequest) }
    var onResponse by remember { mutableStateOf(initial.onResponse) }
    var showError by remember { mutableStateOf(false) }

    // A rule fires only if it can pause at least one phase; enforce that (and a non-blank pattern) here.
    val canSave = urlPattern.isNotBlank() && (onRequest || onResponse)
    fun save() {
        val trimmed = urlPattern.trim()
        if (trimmed.isNotEmpty() && (onRequest || onResponse)) {
            onSave(initial.copy(enabled = enabled, urlPattern = trimmed, method = method, onRequest = onRequest, onResponse = onResponse))
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
                    isError = showError,
                )
            }
            EditorField("Method") {
                MethodDropdown(method = method, onSelect = { method = it })
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
            if (showError) {
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
private fun EditorField(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        content()
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

// One-line caption under a rule: the method it's scoped to and which phase(s) it pauses on.
private fun ruleSummary(rule: BreakpointRuleDef): String {
    val phases = when {
        rule.onRequest && rule.onResponse -> "Request & Response"
        rule.onRequest -> "Request"
        rule.onResponse -> "Response"
        else -> "Never"
    }
    return methodLabel(rule.method) + " \u00b7 " + phases
}

// A rule matches a single HTTP method; "Any" (blank) leaves the method unconstrained. Blank leads so it
// reads as the "no restriction" default, then the common methods in request-frequency order. Mirrors the
// Map Local editor's picker (kept file-local to avoid coupling the two panels).
private val MethodOptions = listOf("", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

private fun methodLabel(method: String): String = method.ifBlank { "Any" }

@Composable
private fun MethodDropdown(method: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Box {
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
