package com.venbiasa.wailo.shared.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.GroupNode
import com.venbiasa.wailo.shared.InGroupAt
import com.venbiasa.wailo.shared.MapLocalDropTarget
import com.venbiasa.wailo.shared.MapLocalGroup
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.RuleNode
import com.venbiasa.wailo.shared.TopLevelAt
import com.venbiasa.wailo.shared.addGroup
import com.venbiasa.wailo.shared.findRule
import com.venbiasa.wailo.shared.moveGroup
import com.venbiasa.wailo.shared.moveRule
import com.venbiasa.wailo.shared.removeGroup
import com.venbiasa.wailo.shared.removeRule
import com.venbiasa.wailo.shared.renameGroup
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import com.venbiasa.wailo.shared.resources.ic_create_new_folder
import com.venbiasa.wailo.shared.resources.ic_delete
import com.venbiasa.wailo.shared.resources.ic_note_add
import com.venbiasa.wailo.shared.setGroupEnabled
import com.venbiasa.wailo.shared.setRuleEnabled
import org.jetbrains.compose.resources.vectorResource

// Inset of a grouped rule's content. Sized so the rule's switch lands directly under the group header's
// switch: the header puts a 28.dp collapse chevron before its switch where a rule row has only a 4.dp gap
// (both share the same 24.dp drag handle), so the child starts 28 - 4 = 24.dp past the header's 4.dp start
// → 28.dp. Also drives the drop indicator's in-group inset. This indent, with the header bar above the
// rows, is the *only* cue that a rule belongs to a group — no rail/connector and no fill difference.
private val GroupChildIndent = 28.dp

/**
 * The Map Local list page: an interleaved, drag-orderable list of groups and loose rules (ADR-0026).
 * `shared` stays stateless — every structural change is computed with the pure layout ops and handed to
 * [onNodesChange]; the host persists it and recompiles the device match-set. Top-to-bottom order is the
 * match priority. [onAddRule]/[onEditRule] navigate to the editor (a rule isn't committed until Save).
 */
@Composable
internal fun RuleListPage(
    nodes: List<MapLocalNode>,
    onAddRule: () -> Unit,
    onEditRule: (MapLocalRuleDef) -> Unit,
    onNodesChange: (List<MapLocalNode>) -> Unit,
    collapsedGroupIds: SnapshotStateList<String>,
    onClose: () -> Unit,
) {
    // A group delete takes its rules with it (ADR-0026), so a non-empty group confirms first.
    var pendingDeleteGroup by remember { mutableStateOf<MapLocalGroup?>(null) }
    // A just-created group opens straight into inline rename (so it can be named without a second click).
    // Holds that group's id until its editor is dismissed, then cleared.
    var autoEditGroupId by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth()
                    .height(TopBarHeight)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Map Local", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                // Add actions are icon-only header buttons whose tooltips name them: a "file" (new mapping
                // rule) and a "folder" (new group). Creating a group opens it straight into inline rename.
                HoverTooltip("New mapping rule") {
                    IconButton(onClick = onAddRule, modifier = Modifier.size(36.dp)) {
                        Icon(
                            vectorResource(Res.drawable.ic_note_add),
                            contentDescription = "New mapping rule",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                HoverTooltip("New group") {
                    IconButton(
                        onClick = {
                            val id = MapLocalGroup.newId()
                            onNodesChange(nodes.addGroup(MapLocalGroup(id)))
                            autoEditGroupId = id
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            vectorResource(Res.drawable.ic_create_new_folder),
                            contentDescription = "New group",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                CloseButton(onClose, contentDescription = "Close panel")
            }
            RowDivider()
            if (nodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No rules yet. Add a rule, or a group to organize rules you can toggle together.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                DraggableNodeList(
                    nodes = nodes,
                    collapsedGroupIds = collapsedGroupIds,
                    autoEditGroupId = autoEditGroupId,
                    onAutoEditConsumed = { autoEditGroupId = null },
                    onEditRule = onEditRule,
                    onToggleRule = { id -> onNodesChange(nodes.setRuleEnabled(id, !(nodes.findRule(id)?.enabled ?: true))) },
                    onToggleGroup = { g -> onNodesChange(nodes.setGroupEnabled(g.id, !g.enabled)) },
                    onRenameGroup = { id, name -> onNodesChange(nodes.renameGroup(id, name)) },
                    onDeleteRule = { id -> onNodesChange(nodes.removeRule(id)) },
                    onDeleteGroup = { g ->
                        // An empty group deletes with no prompt; a group with rules confirms first,
                        // since delete takes its rules with it (ADR-0026).
                        val hasRules = nodes.any { it is GroupNode && it.group.id == g.id && it.rules.isNotEmpty() }
                        if (hasRules) pendingDeleteGroup = g else onNodesChange(nodes.removeGroup(g.id))
                    },
                    onNodesChange = onNodesChange,
                )
            }
        }
    }

    pendingDeleteGroup?.let { group ->
        val count = nodes.firstOrNull { it is GroupNode && it.group.id == group.id }
            ?.let { (it as GroupNode).rules.size } ?: 0
        AlertDialog(
            onDismissRequest = { pendingDeleteGroup = null },
            title = { Text("Delete group?") },
            text = {
                Text(
                    "\u201c${group.name.ifBlank { "New group" }}\u201d and its $count " +
                        (if (count == 1) "rule" else "rules") + " will be deleted. This can\u2019t be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onNodesChange(nodes.removeGroup(group.id))
                    pendingDeleteGroup = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteGroup = null }) { Text("Cancel") } },
        )
    }
}

// --- Draggable list ---------------------------------------------------------------------------------

/** Live drag state, hoisted so the row handles can mutate it and the list can read it for the indicator. */
private class ReorderState {
    var draggingId by mutableStateOf<String?>(null)
    var isGroup by mutableStateOf(false)
    var startCenterY by mutableStateOf(0f)
    var dy by mutableStateOf(0f)
    val pointerY: Float get() = startCenterY + dy

    fun clear() {
        draggingId = null
        isGroup = false
        startCenterY = 0f
        dy = 0f
    }
}

@Composable
private fun DraggableNodeList(
    nodes: List<MapLocalNode>,
    collapsedGroupIds: SnapshotStateList<String>,
    autoEditGroupId: String?,
    onAutoEditConsumed: () -> Unit,
    onEditRule: (MapLocalRuleDef) -> Unit,
    onToggleRule: (String) -> Unit,
    onToggleGroup: (MapLocalGroup) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onDeleteRule: (String) -> Unit,
    onDeleteGroup: (MapLocalGroup) -> Unit,
    onNodesChange: (List<MapLocalNode>) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorder = remember { ReorderState() }
    // Read the latest inputs from inside the long-lived drag gesture (its lambdas are captured once).
    val currentNodes by rememberUpdatedState(nodes)
    val currentOnChange by rememberUpdatedState(onNodesChange)

    val rows = remember(nodes, collapsedGroupIds.toList()) { nodes.toDispRows(collapsedGroupIds.toSet()) }

    // Commit a finished drag: resolve the pointer against the layout minus the dragged item, then move.
    fun commitDrop() {
        val id = reorder.draggingId ?: return
        val info = listState.layoutInfo
        val centerOf: (String) -> Float? = { key ->
            info.visibleItemsInfo.firstOrNull { it.key == key }?.let { it.offset + it.size / 2f }
        }
        val next = if (reorder.isGroup) {
            currentNodes.moveGroup(id, resolveGroupDropIndex(currentNodes.filterNot { it.id == id }, centerOf, reorder.pointerY))
        } else {
            val target = resolveRuleDropTarget(currentNodes.removeRule(id), centerOf, reorder.pointerY, collapsedGroupIds.toSet())
            // Dropping a rule into a collapsed group expands it, so the rule can't silently vanish.
            if (target is InGroupAt) collapsedGroupIds.remove(target.groupId)
            currentNodes.moveRule(id, target)
        }
        if (next != currentNodes) currentOnChange(next)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // A little breathing room past the last row (and drop room below the final group's footer).
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                when (row) {
                    is HeaderDisp -> GroupHeaderRow(
                        group = row.group,
                        ruleCount = row.ruleCount,
                        collapsed = row.group.id in collapsedGroupIds,
                        dragging = reorder.draggingId == row.group.id,
                        autoEdit = row.group.id == autoEditGroupId,
                        onAutoEditConsumed = onAutoEditConsumed,
                        onToggleCollapsed = {
                            if (row.group.id in collapsedGroupIds) collapsedGroupIds.remove(row.group.id)
                            else collapsedGroupIds.add(row.group.id)
                        },
                        onToggle = { onToggleGroup(row.group) },
                        onRename = { onRenameGroup(row.group.id, it) },
                        onDelete = { onDeleteGroup(row.group) },
                        handleModifier = dragHandle(reorder, listState, row.key, isGroup = true, onDrop = ::commitDrop),
                    )
                    is RuleDisp -> RuleRow(
                        rule = row.rule,
                        grouped = row.groupId != null,
                        groupEnabled = row.groupEnabled,
                        dragging = reorder.draggingId == row.rule.id,
                        onEdit = { onEditRule(row.rule) },
                        onToggle = { onToggleRule(row.rule.id) },
                        onDelete = { onDeleteRule(row.rule.id) },
                        handleModifier = dragHandle(reorder, listState, row.key, isGroup = false, onDrop = ::commitDrop),
                    )
                    is FooterDisp -> GroupFooter()
                }
            }
        }
        DropIndicator(nodes = nodes, reorder = reorder, listState = listState, collapsedGroupIds = collapsedGroupIds.toSet())
    }
}

// Builds the Modifier for a row's drag handle: starts a drag (recording the row's on-screen center so
// the pointer position can be tracked from the accumulated delta), accumulates vertical movement, and
// commits on release. Horizontal movement is ignored — nesting is decided purely by vertical position
// against the group containers (ADR-0026).
private fun dragHandle(
    reorder: ReorderState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    key: String,
    isGroup: Boolean,
    onDrop: () -> Unit,
): Modifier = Modifier.pointerInput(key) {
    detectDragGestures(
        onDragStart = {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
            reorder.draggingId = key.removePrefix("h:")
            reorder.isGroup = isGroup
            reorder.startCenterY = info?.let { it.offset + it.size / 2f } ?: 0f
            reorder.dy = 0f
        },
        onDrag = { change, dragAmount ->
            change.consume()
            reorder.dy += dragAmount.y
        },
        onDragEnd = {
            onDrop()
            reorder.clear()
        },
        onDragCancel = { reorder.clear() },
    )
}

@Composable
private fun DropIndicator(
    nodes: List<MapLocalNode>,
    reorder: ReorderState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    collapsedGroupIds: Set<String>,
) {
    val id = reorder.draggingId ?: return
    val info = listState.layoutInfo
    val topOf: (String) -> Float? = { key -> info.visibleItemsInfo.firstOrNull { it.key == key }?.offset?.toFloat() }
    val bottomOf: (String) -> Float? = { key -> info.visibleItemsInfo.firstOrNull { it.key == key }?.let { (it.offset + it.size).toFloat() } }
    val centerOf: (String) -> Float? = { key -> info.visibleItemsInfo.firstOrNull { it.key == key }?.let { it.offset + it.size / 2f } }

    val spec: Pair<Float, Dp>? = if (reorder.isGroup) {
        val reduced = nodes.filterNot { it.id == id }
        val idx = resolveGroupDropIndex(reduced, centerOf, reorder.pointerY)
        topLevelGapY(reduced, idx, topOf, bottomOf)?.let { it to 0.dp }
    } else {
        val reduced = nodes.removeRule(id)
        val rows = reduced.toDispRows(collapsedGroupIds)
        val gap = rows.count { (centerOf(it.key) ?: Float.NEGATIVE_INFINITY) < reorder.pointerY }.coerceIn(0, rows.size)
        val target = targetForGap(rows, gap)
        val y = if (gap < rows.size) topOf(rows[gap].key) else rows.lastOrNull()?.let { bottomOf(it.key) }
        y?.let { it to (if (target is InGroupAt) GroupChildIndent else 0.dp) }
    }
    spec?.let { (y, indent) ->
        Box(
            Modifier
                .offset { IntOffset(0, y.toInt()) }
                .padding(start = indent, end = 12.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun GroupHeaderRow(
    group: MapLocalGroup,
    ruleCount: Int,
    collapsed: Boolean,
    dragging: Boolean,
    autoEdit: Boolean,
    onAutoEditConsumed: () -> Unit,
    onToggleCollapsed: () -> Unit,
    onToggle: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    handleModifier: Modifier,
) {
    // Rename is click-to-edit: the name reads as a plain title until clicked (or, for a just-created
    // group, [autoEdit] opens it immediately). Keeping the idle state a label — not a permanent input
    // box — is what makes a saved name look saved; the field then commits on Enter or click-away.
    var editing by remember(group.id) { mutableStateOf(autoEdit) }
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (dragging) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DragHandleDots(handleModifier)
        // The caret points down when open, right when collapsed; tapping it hides/shows the group's rules.
        val chevron by animateFloatAsState(if (collapsed) -90f else 0f, label = "groupChevron")
        IconButton(onClick = onToggleCollapsed, modifier = Modifier.size(28.dp)) {
            Icon(
                vectorResource(Res.drawable.ic_arrow_drop_down),
                contentDescription = if (collapsed) "Expand group" else "Collapse group",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(chevron),
            )
        }
        CompactSwitch(checked = group.enabled, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (editing) {
                GroupNameEditor(
                    initial = group.name,
                    onCommit = { name ->
                        editing = false
                        onAutoEditConsumed()
                        if (name != group.name) onRename(name)
                    },
                    onCancel = {
                        editing = false
                        onAutoEditConsumed()
                    },
                )
            } else {
                Text(
                    group.name.ifBlank { "New group" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { editing = true }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                )
            }
        }
        // When collapsed, surface how many rules are tucked away (a rule row never shows a count, so this
        // doubles as a group-vs-rule cue).
        if (collapsed) {
            Text(
                "$ruleCount ${if (ruleCount == 1) "rule" else "rules"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                vectorResource(Res.drawable.ic_delete),
                contentDescription = "Delete group",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    RowDivider()
}

// Inline rename field for a group, shown only while renaming (the idle header is a plain title, not a
// permanent box). Autofocuses with the text selected; commits on Enter or when focus leaves (clicking
// elsewhere), and reverts on Esc. A blank name coerces to "New group" on commit (a group name is
// required, ADR-0026).
@Composable
private fun GroupNameEditor(
    initial: String,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    // Enter, blur, and Esc can each end the edit; the first wins so a blur right after Enter can't
    // double-commit and a blur right after Esc can't override the cancel.
    var finished by remember { mutableStateOf(false) }
    var hasFocused by remember { mutableStateOf(false) }
    fun finish(save: Boolean) {
        if (finished) return
        finished = true
        if (save) onCommit(field.text.trim().ifBlank { "New group" }) else onCancel()
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isBlank = field.text.isBlank()
    BasicTextField(
        value = field,
        onValueChange = { field = it },
        modifier = Modifier.fillMaxWidth()
            .focusRequester(focusRequester)
            // Ignore the initial unfocused callback before requestFocus lands; only a real blur (after the
            // field has held focus) commits — that's the "click outside to save".
            .onFocusChanged { state -> if (state.isFocused) hasFocused = true else if (hasFocused) finish(save = true) }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        finish(save = true)
                        true
                    }
                    Key.Escape -> {
                        finish(save = false)
                        true
                    }
                    else -> false
                }
            },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(if (isBlank) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        decorationBox = { inner ->
            CompactFieldDecoration(
                value = field.text,
                interactionSource = interactionSource,
                placeholder = "New group",
                isError = isBlank,
                innerTextField = inner,
            )
        },
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

// A thin strip that closes a group: it is the drop zone that appends a dragged rule to the end of the
// group (versus dropping below the group at the top level).
@Composable
private fun GroupFooter() {
    Box(Modifier.fillMaxWidth().height(8.dp))
    RowDivider()
}

@Composable
private fun RuleRow(
    rule: MapLocalRuleDef,
    grouped: Boolean,
    groupEnabled: Boolean,
    dragging: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    handleModifier: Modifier,
) {
    Row(
        Modifier.fillMaxWidth()
            // Rules sit on the base surface whether loose or grouped; a grouped rule is set apart only by
            // its indent (and the group header bar above it), never by a fill or connector.
            .then(if (dragging) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            .padding(start = if (grouped) GroupChildIndent else 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DragHandleDots(handleModifier)
        Spacer(Modifier.width(4.dp))
        // A rule in an off group reads disabled but keeps its own remembered state (ADR-0026).
        CompactSwitch(checked = rule.enabled, onCheckedChange = { onToggle() }, enabled = !grouped || groupEnabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable { onEdit() }) {
            Text(
                rule.name.ifBlank { "Untitled" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val method = rule.method.ifBlank { "ANY" }
            Text(
                "$method  \u2192  ${rule.urlPattern.ifBlank { "(no pattern)" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                vectorResource(Res.drawable.ic_delete),
                contentDescription = "Delete rule",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    RowDivider()
}

// A 2x3 grip of dots — the drag affordance. Drawn (not a drawable) so it stays tint-driven for light/dark
// without adding an icon resource; the [handle] modifier carries the drag gesture.
@Composable
private fun DragHandleDots(handle: Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(handle.size(width = 24.dp, height = 36.dp).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(width = 10.dp, height = 16.dp)) {
            val r = size.minDimension * 0.09f
            val xs = listOf(size.width * 0.25f, size.width * 0.75f)
            val ys = listOf(size.height * 0.2f, size.height * 0.5f, size.height * 0.8f)
            for (x in xs) for (y in ys) drawCircle(color = color, radius = r, center = androidx.compose.ui.geometry.Offset(x, y))
        }
    }
}

// --- Display-row model + pure drop resolution (kept simple and vertical-only) -----------------------

private sealed interface Disp {
    val key: String
}

private data class HeaderDisp(val group: MapLocalGroup, val topIndex: Int, val ruleCount: Int) : Disp {
    override val key: String get() = "h:${group.id}"
}

private data class RuleDisp(
    val rule: MapLocalRuleDef,
    val groupId: String?,
    val groupEnabled: Boolean,
    val topIndex: Int,
    val childIndex: Int,
) : Disp {
    override val key: String get() = rule.id
}

private data class FooterDisp(val group: MapLocalGroup, val topIndex: Int) : Disp {
    override val key: String get() = "f:${group.id}"
}

// Flattens the layout into the rows the list renders, one row per LazyColumn item (so item index ==
// row index). A group contributes a header, its rules, then a footer strip; a loose rule is one row.
private fun List<MapLocalNode>.toDispRows(collapsedGroupIds: Set<String>): List<Disp> = buildList {
    var top = 0
    this@toDispRows.forEach { node ->
        when (node) {
            is RuleNode -> {
                add(RuleDisp(node.rule, groupId = null, groupEnabled = true, topIndex = top, childIndex = 0))
                top++
            }
            is GroupNode -> {
                add(HeaderDisp(node.group, top, node.rules.size))
                // A collapsed group contributes only its header — its rules and footer drop out of both
                // the rendered list and the drop hit-testing (which share this one row model).
                if (node.group.id !in collapsedGroupIds) {
                    node.rules.forEachIndexed { ci, r ->
                        add(RuleDisp(r, node.group.id, node.group.enabled, top, ci))
                    }
                    add(FooterDisp(node.group, top))
                }
                top++
            }
        }
    }
}

// The drop target for gap [gap] (0..rows.size) among [rows] — the display of [reducedNodes] (the layout
// with the dragged rule already removed). Unambiguous because the footer row gives a distinct "append to
// group" gap versus "top level after the group". Only the row *before* the gap is needed: a grouped rule
// keeps you in its group; a header puts you at the group's first slot; a footer or loose rule is top level.
private fun targetForGap(rows: List<Disp>, gap: Int): MapLocalDropTarget =
    when (val prev = rows.getOrNull(gap - 1)) {
        null -> TopLevelAt(0)
        is HeaderDisp -> InGroupAt(prev.group.id, 0)
        is RuleDisp -> if (prev.groupId != null) InGroupAt(prev.groupId, prev.childIndex + 1) else TopLevelAt(prev.topIndex + 1)
        is FooterDisp -> TopLevelAt(prev.topIndex + 1)
    }

private fun resolveRuleDropTarget(
    reducedNodes: List<MapLocalNode>,
    centerOf: (String) -> Float?,
    pointerY: Float,
    collapsedGroupIds: Set<String>,
): MapLocalDropTarget {
    val rows = reducedNodes.toDispRows(collapsedGroupIds)
    val gap = rows.count { (centerOf(it.key) ?: Float.NEGATIVE_INFINITY) < pointerY }.coerceIn(0, rows.size)
    return targetForGap(rows, gap)
}

// For a group drag: the top-level index to drop at = how many top-level nodes sit entirely above the
// pointer (measured by each node's last row — a loose rule's row, or a group's footer).
private fun resolveGroupDropIndex(
    reducedNodes: List<MapLocalNode>,
    centerOf: (String) -> Float?,
    pointerY: Float,
): Int {
    var idx = 0
    reducedNodes.forEachIndexed { i, node ->
        val lastKey = when (node) {
            is RuleNode -> node.rule.id
            is GroupNode -> "f:${node.group.id}"
        }
        if ((centerOf(lastKey) ?: Float.NEGATIVE_INFINITY) < pointerY) idx = i + 1
    }
    return idx
}

// The Y (viewport px) of the drop indicator for a top-level insertion at [idx]: the top of the idx-th
// node's first row, or the bottom of the last row when appending at the end.
private fun topLevelGapY(
    reducedNodes: List<MapLocalNode>,
    idx: Int,
    topOf: (String) -> Float?,
    bottomOf: (String) -> Float?,
): Float? {
    fun firstKey(node: MapLocalNode) = when (node) {
        is RuleNode -> node.rule.id
        is GroupNode -> "h:${node.group.id}"
    }
    fun lastKey(node: MapLocalNode) = when (node) {
        is RuleNode -> node.rule.id
        is GroupNode -> "f:${node.group.id}"
    }
    return if (idx < reducedNodes.size) topOf(firstKey(reducedNodes[idx])) else reducedNodes.lastOrNull()?.let { bottomOf(lastKey(it)) }
}
