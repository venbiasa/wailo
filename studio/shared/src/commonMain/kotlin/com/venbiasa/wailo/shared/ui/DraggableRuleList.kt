package com.venbiasa.wailo.shared.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.GroupNode
import com.venbiasa.wailo.shared.InGroupAt
import com.venbiasa.wailo.shared.LayoutDropTarget
import com.venbiasa.wailo.shared.LayoutNode
import com.venbiasa.wailo.shared.LayoutRule
import com.venbiasa.wailo.shared.RuleGroup
import com.venbiasa.wailo.shared.RuleNode
import com.venbiasa.wailo.shared.TopLevelAt
import com.venbiasa.wailo.shared.addGroup
import com.venbiasa.wailo.shared.findRule
import com.venbiasa.wailo.shared.groupNode
import com.venbiasa.wailo.shared.moveGroup
import com.venbiasa.wailo.shared.moveRule
import com.venbiasa.wailo.shared.removeGroup
import com.venbiasa.wailo.shared.removeRule
import com.venbiasa.wailo.shared.renameGroup
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import com.venbiasa.wailo.shared.resources.ic_create_new_folder
import com.venbiasa.wailo.shared.resources.ic_delete
import com.venbiasa.wailo.shared.setGroupEnabled
import com.venbiasa.wailo.shared.setRuleEnabled
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

// Inset of a grouped rule's content. Sized so the rule's switch lands directly under the group header's
// switch: the header puts a 28.dp collapse chevron before its switch where a rule row has only a 4.dp gap
// (both share the same leading drag handle, present or not), so the child starts 28 - 4 = 24.dp past the
// header's 4.dp start → 28.dp. Also drives the drop indicator's in-group inset. This indent, with the
// header bar above the rows, is the *only* cue that a rule belongs to a group — no rail/connector and no
// fill difference.
private val GroupChildIndent = 28.dp

// How close to a viewport edge a drag's pointer must come before the list starts scrolling under it, and
// the top speed it reaches at the edge itself. The band is about one and a half rows: any narrower and the
// only way to enter it is to overshoot the panel entirely.
private val AutoScrollBand = 64.dp
private const val AutoScrollMaxPxPerSecond = 1500f

/**
 * A grouped, drag-orderable rule panel: an interleaved list of groups and loose rules (ADR-0026),
 * generic over the rule type [T] so Map Local and the breakpoints panel share one implementation
 * (ADR-0027). `shared` stays stateless — every structural change is computed with the pure layout ops
 * and handed to [onNodesChange]; the host persists it. Top-to-bottom order is the match priority.
 *
 * The header carries a title, then — heading the right-side actions — the feature master switch
 * ([featureEnabled]/[onFeatureEnabledChange]), any feature-specific [headerActions], an add-rule button
 * ([addRuleIcon]/[addRuleTooltip] → [onAddRule]), a new-group button, [overflowActions] as a three-dot
 * menu, and Close; [description] is a one-line caption under it. The overflow is placed here rather than
 * left to the caller so it always lands where a three-dot menu belongs — last, against Close — however
 * many panels grow one. The master is the single feature on/off (ADR-0030):
 * off dims the list and disables every rule/group switch (their remembered state kept), and the host
 * pushes no rules — so the whole feature goes inert without erasing what's configured, one level above
 * group-gating. [onEditRule] opens a rule (the caller navigates to its editor); a rule isn't committed
 * until that editor saves. [ruleContent] renders the middle of a rule row (the clickable label area) —
 * the only per-feature difference; the drag handle, enabled switch, and delete affordance are shared.
 * [rowActions] optionally gives a rule row a right-click menu (Duplicate, plus Map Local's "Seed…",
 * ADR-0041); returning an empty list — the default — leaves the row without one.
 *
 * [reorderable] drops the drag handles for a list whose order must not read as adjustable. No panel passes
 * false today — the breakpoints list keeps its grips even though its order is arrangement rather than match
 * priority (ADR-0098) — so this is the switch for the next one that needs it. Note what it also costs: the
 * drag is the only gesture that files a rule into a group, so a grip-free list can create groups it cannot
 * fill.
 */
@Composable
internal fun <T : LayoutRule<T>> GroupedRuleListPage(
    title: String,
    description: String,
    emptyText: String,
    addRuleIcon: DrawableResource,
    addRuleTooltip: String,
    nodes: List<LayoutNode<T>>,
    featureEnabled: Boolean,
    onFeatureEnabledChange: (Boolean) -> Unit,
    collapsedGroupIds: SnapshotStateList<String>,
    onAddRule: () -> Unit,
    onEditRule: (T) -> Unit,
    onNodesChange: (List<LayoutNode<T>>) -> Unit,
    onClose: () -> Unit,
    headerActions: @Composable () -> Unit = {},
    overflowActions: List<ContextMenuAction> = emptyList(),
    notice: String = "",
    rowActions: (T) -> List<ContextMenuAction> = { emptyList() },
    reorderable: Boolean = true,
    ruleContent: @Composable (T) -> Unit,
) {
    // A group delete takes its rules with it (ADR-0026), so a non-empty group confirms first.
    var pendingDeleteGroup by remember { mutableStateOf<RuleGroup?>(null) }
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
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                // The feature master heads the right-side cluster, before the add/group/close actions: one
                // switch turns the whole feature on/off, above the per-group and per-rule switches. Off
                // pushes no rules and disables the switches below, their remembered state kept, so it never
                // erases what's configured (ADR-0030).
                HoverTooltip(if (featureEnabled) "On" else "Off") {
                    CompactSwitch(checked = featureEnabled, onCheckedChange = onFeatureEnabledChange)
                }
                Spacer(Modifier.width(4.dp))
                headerActions()
                // Add actions are icon-only header buttons whose tooltips name them: an add-rule icon and
                // a "folder" (new group). Creating a group opens it straight into inline rename.
                HoverTooltip(addRuleTooltip) {
                    IconButton(onClick = onAddRule, modifier = Modifier.size(36.dp)) {
                        Icon(
                            vectorResource(addRuleIcon),
                            contentDescription = addRuleTooltip,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                HoverTooltip("New group") {
                    IconButton(
                        onClick = {
                            val id = RuleGroup.newId()
                            onNodesChange(nodes.addGroup(RuleGroup(id)))
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
                if (overflowActions.isNotEmpty()) HeaderOverflowMenu(overflowActions)
                CloseButton(onClose, contentDescription = "Close panel")
            }
            RowDivider()
            if (description.isNotBlank()) {
                MutedText(description, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
                RowDivider()
            }
            // Sits where the caption does rather than over the list, so the rules an import just added
            // stay visible behind the sentence describing them.
            if (notice.isNotBlank()) {
                Text(
                    notice,
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                RowDivider()
            }
            if (nodes.isEmpty()) {
                // Empty hint sits at the top (left-aligned under the description caption), not floating in
                // the vertical center of the panel. Uses the same muted caption style as the description
                // above it (bodySmall + onSurfaceVariant) so it no longer out-weighs it — onSurfaceDisabled
                // was tried but is too low-contrast to read against the near-black dark-mode panel.
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                    Text(
                        emptyText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                DraggableNodeList(
                    nodes = nodes,
                    featureEnabled = featureEnabled,
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
                        val hasRules = nodes.groupNode(g.id)?.rules?.isNotEmpty() == true
                        if (hasRules) pendingDeleteGroup = g else onNodesChange(nodes.removeGroup(g.id))
                    },
                    onNodesChange = onNodesChange,
                    rowActions = rowActions,
                    reorderable = reorderable,
                    ruleContent = ruleContent,
                )
            }
        }
    }

    pendingDeleteGroup?.let { group ->
        val count = nodes.groupNode(group.id)?.rules?.size ?: 0
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

    // The gesture lives on the dragged row's own handle, so a LazyColumn disposing that row mid-drag
    // cancels the reorder. Autoscrolling makes that the normal case rather than a corner: the row carried
    // off the far edge is usually the dragged one, since it stays in its old slot until the drop commits.
    // Pinning keeps the item composed — and its pointer input alive — until then.
    private var pin: PinnableContainer.PinnedHandle? = null

    fun start(id: String, isGroup: Boolean, centerY: Float, pinnable: PinnableContainer?) {
        pin?.release()
        pin = pinnable?.pin()
        draggingId = id
        this.isGroup = isGroup
        startCenterY = centerY
        dy = 0f
    }

    fun clear() {
        pin?.release()
        pin = null
        draggingId = null
        isGroup = false
        startCenterY = 0f
        dy = 0f
    }
}

@Composable
private fun <T : LayoutRule<T>> DraggableNodeList(
    nodes: List<LayoutNode<T>>,
    featureEnabled: Boolean,
    collapsedGroupIds: SnapshotStateList<String>,
    autoEditGroupId: String?,
    onAutoEditConsumed: () -> Unit,
    onEditRule: (T) -> Unit,
    onToggleRule: (String) -> Unit,
    onToggleGroup: (RuleGroup) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onDeleteRule: (String) -> Unit,
    onDeleteGroup: (RuleGroup) -> Unit,
    onNodesChange: (List<LayoutNode<T>>) -> Unit,
    rowActions: (T) -> List<ContextMenuAction>,
    reorderable: Boolean,
    ruleContent: @Composable (T) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorder = remember { ReorderState() }
    // Read the latest inputs from inside the long-lived drag gesture (its lambdas are captured once).
    val currentNodes by rememberUpdatedState(nodes)
    val currentOnChange by rememberUpdatedState(onNodesChange)

    val rows = remember(nodes, collapsedGroupIds.toList()) { nodes.toDispRows(collapsedGroupIds.toSet()) }

    // Scroll the list under a drag that reaches a viewport edge, so reordering across a list taller than
    // the panel is one gesture instead of drop, scroll, grab again. Frame-paced rather than animated,
    // because the speed has to track how deep into the band the pointer is *right now* and the pointer can
    // hold that position for seconds. The pointer is tracked in viewport coordinates, so scrolling the
    // content beneath a still finger leaves it — and every drop resolution below — correct as-is.
    val autoScrollBandPx = with(LocalDensity.current) { AutoScrollBand.toPx() }
    LaunchedEffect(reorder.draggingId) {
        if (reorder.draggingId == null) return@LaunchedEffect
        var previousFrame = 0L
        while (true) {
            val frame = withFrameNanos { it }
            // A frame the app spent elsewhere must not cash out as one long jump.
            val elapsed = if (previousFrame == 0L) 0f else ((frame - previousFrame) / 1e9f).coerceAtMost(0.05f)
            previousFrame = frame
            val info = listState.layoutInfo
            val speed = dragAutoScrollSpeed(
                pointerY = reorder.pointerY,
                viewportStart = info.viewportStartOffset.toFloat(),
                viewportEnd = info.viewportEndOffset.toFloat(),
                bandPx = autoScrollBandPx,
                maxPxPerSecond = AutoScrollMaxPxPerSecond,
            )
            if (speed != 0f && elapsed > 0f) listState.scrollBy(speed * elapsed)
        }
    }

    // Commit a finished drag: resolve the pointer against the layout minus the dragged item, then move.
    fun commitDrop() {
        val id = reorder.draggingId ?: return
        val info = listState.layoutInfo
        val centerOf: (String) -> Float? = { key ->
            info.visibleItemsInfo.firstOrNull { it.key == key }?.let { it.offset + it.size / 2f }
        }
        val next = if (reorder.isGroup) {
            val spans = currentNodes.filterNot { it.id == id }.topLevelSpans(collapsedGroupIds.toSet())
            currentNodes.moveGroup(id, resolveGroupDropIndex(spans, centerOf, reorder.pointerY))
        } else {
            val target = resolveRuleDropTarget(currentNodes.removeRule(id), centerOf, reorder.pointerY, collapsedGroupIds.toSet())
            // Dropping a rule into a collapsed group expands it, so the rule can't silently vanish.
            if (target is InGroupAt) collapsedGroupIds.remove(target.groupId)
            currentNodes.moveRule(id, target)
        }
        if (next != currentNodes) currentOnChange(next)
    }

    // While the feature master is off the list stays visible but reads plainly inert: dim the whole
    // list and hand each row a disabled switch (see [RuleRow]/[GroupHeaderRow]), keeping the remembered
    // per-rule/group state so flipping the master back on restores it.
    Box(Modifier.fillMaxSize().alpha(if (featureEnabled) 1f else DisabledFeatureAlpha)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // A little breathing room past the last row (and drop room below the final group's footer).
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(
                rows,
                key = { _, row -> row.key },
                contentType = { _, row -> row.contentType },
            ) { _, row ->
                val pinnable = LocalPinnableContainer.current
                when (row) {
                    is HeaderDisp -> GroupHeaderRow(
                        group = row.group,
                        featureEnabled = featureEnabled,
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
                        handleModifier = if (reorderable) {
                            dragHandle(reorder, listState, row.key, isGroup = true, pinnable = pinnable, onDrop = ::commitDrop)
                        } else {
                            null
                        },
                    )
                    is RuleDisp -> RuleRow(
                        rule = row.rule,
                        grouped = row.groupId != null,
                        groupEnabled = row.groupEnabled,
                        featureEnabled = featureEnabled,
                        dragging = reorder.draggingId == row.rule.id,
                        onEdit = { onEditRule(row.rule) },
                        onToggle = { onToggleRule(row.rule.id) },
                        onDelete = { onDeleteRule(row.rule.id) },
                        handleModifier = if (reorderable) {
                            dragHandle(reorder, listState, row.key, isGroup = false, pinnable = pinnable, onDrop = ::commitDrop)
                        } else {
                            null
                        },
                        actions = rowActions(row.rule),
                        content = ruleContent,
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
// against the group containers (ADR-0026). [pinnable] is the row's own lazy-item pin, held for the drag so
// autoscrolling the row out of view cannot dispose the gesture that is driving it.
private fun dragHandle(
    reorder: ReorderState,
    listState: LazyListState,
    key: String,
    isGroup: Boolean,
    pinnable: PinnableContainer?,
    onDrop: () -> Unit,
): Modifier = Modifier.pointerInput(key) {
    detectDragGestures(
        onDragStart = {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
            reorder.start(
                id = key.removePrefix("h:"),
                isGroup = isGroup,
                centerY = info?.let { it.offset + it.size / 2f } ?: 0f,
                pinnable = pinnable,
            )
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
private fun <T : LayoutRule<T>> DropIndicator(
    nodes: List<LayoutNode<T>>,
    reorder: ReorderState,
    listState: LazyListState,
    collapsedGroupIds: Set<String>,
) {
    val id = reorder.draggingId ?: return
    val info = listState.layoutInfo
    val topOf: (String) -> Float? = { key -> info.visibleItemsInfo.firstOrNull { it.key == key }?.offset?.toFloat() }
    val bottomOf: (String) -> Float? = { key -> info.visibleItemsInfo.firstOrNull { it.key == key }?.let { (it.offset + it.size).toFloat() } }
    val centerOf: (String) -> Float? = { key -> info.visibleItemsInfo.firstOrNull { it.key == key }?.let { it.offset + it.size / 2f } }

    val spec: Pair<Float, Dp>? = if (reorder.isGroup) {
        val spans = nodes.filterNot { it.id == id }.topLevelSpans(collapsedGroupIds)
        val idx = resolveGroupDropIndex(spans, centerOf, reorder.pointerY)
        topLevelGapY(spans, idx, topOf, bottomOf)?.let { it to 0.dp }
    } else {
        val rows = nodes.removeRule(id).toDispRows(collapsedGroupIds)
        val gap = countRowsAbove(rows.size, { centerOf(rows[it].key) }, reorder.pointerY)
        val target = targetForGap(rows, gap)
        // The line sits on the boundary between the rows either side of the gap; whichever of the two is on
        // screen fixes it — at the list's ends, and mid-autoscroll, only one of them is.
        val y = rows.getOrNull(gap)?.let { topOf(it.key) } ?: rows.getOrNull(gap - 1)?.let { bottomOf(it.key) }
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
    group: RuleGroup,
    featureEnabled: Boolean,
    collapsed: Boolean,
    dragging: Boolean,
    autoEdit: Boolean,
    onAutoEditConsumed: () -> Unit,
    onToggleCollapsed: () -> Unit,
    onToggle: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    handleModifier: Modifier?,
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
        handleModifier?.let { DragHandleDots(it) }
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
        // Non-interactive while the feature master is off (the group keeps its remembered state).
        CompactSwitch(checked = group.enabled, onCheckedChange = { onToggle() }, enabled = featureEnabled)
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
private fun <T : LayoutRule<T>> RuleRow(
    rule: T,
    grouped: Boolean,
    groupEnabled: Boolean,
    featureEnabled: Boolean,
    dragging: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    handleModifier: Modifier?,
    actions: List<ContextMenuAction>,
    content: @Composable (T) -> Unit,
) {
    // Only the row itself gets the menu, not the divider below it, so the hit area matches what's
    // highlighted. An empty [actions] passes straight through without a menu.
    ContextMenuHost(actions) {
        Row(
            Modifier.fillMaxWidth()
                // Rules sit on the base surface whether loose or grouped; a grouped rule is set apart only
                // by its indent (and the group header bar above it), never by a fill or connector.
                .then(if (dragging) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
                .padding(start = if (grouped) GroupChildIndent else 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            handleModifier?.let { DragHandleDots(it) }
            Spacer(Modifier.width(4.dp))
            // A rule in an off group — or under an off feature master — reads disabled but keeps its own
            // remembered state (ADR-0026/0030).
            CompactSwitch(
                checked = rule.enabled,
                onCheckedChange = { onToggle() },
                enabled = featureEnabled && (!grouped || groupEnabled),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable { onEdit() }) { content(rule) }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    vectorResource(Res.drawable.ic_delete),
                    contentDescription = "Delete rule",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
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
            for (x in xs) for (y in ys) drawCircle(color = color, radius = r, center = Offset(x, y))
        }
    }
}

// --- Display-row model + pure drop resolution (kept simple and vertical-only) -----------------------

private sealed interface Disp<T : LayoutRule<T>> {
    val key: String

    // What a pooled LazyColumn slot must match before its composition may be reused for this row. The
    // three row shapes differ enough that swapping one for another rebuilds most of the subtree anyway;
    // nothing here folds in row *state*, because a slot arriving with the wrong state is the widget's
    // problem to survive (see [CompactSwitch]) rather than something to dodge by fragmenting the pool.
    val contentType: Any
}

private data class HeaderDisp<T : LayoutRule<T>>(val group: RuleGroup, val topIndex: Int) : Disp<T> {
    override val key: String get() = "h:${group.id}"
    override val contentType: Any get() = "h"
}

private data class RuleDisp<T : LayoutRule<T>>(
    val rule: T,
    val groupId: String?,
    val groupEnabled: Boolean,
    val topIndex: Int,
    val childIndex: Int,
) : Disp<T> {
    override val key: String get() = rule.id
    override val contentType: Any get() = "r"
}

private data class FooterDisp<T : LayoutRule<T>>(val group: RuleGroup, val topIndex: Int) : Disp<T> {
    override val key: String get() = "f:${group.id}"
    override val contentType: Any get() = "f"
}

// Flattens the layout into the rows the list renders, one row per LazyColumn item (so item index ==
// row index). A group contributes a header, its rules, then a footer strip; a loose rule is one row.
private fun <T : LayoutRule<T>> List<LayoutNode<T>>.toDispRows(collapsedGroupIds: Set<String>): List<Disp<T>> = buildList {
    var top = 0
    this@toDispRows.forEach { node ->
        when (node) {
            is RuleNode -> {
                add(RuleDisp(node.rule, groupId = null, groupEnabled = true, topIndex = top, childIndex = 0))
                top++
            }
            is GroupNode -> {
                add(HeaderDisp(node.group, top))
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
private fun <T : LayoutRule<T>> targetForGap(rows: List<Disp<T>>, gap: Int): LayoutDropTarget =
    when (val prev = rows.getOrNull(gap - 1)) {
        null -> TopLevelAt(0)
        is HeaderDisp -> InGroupAt(prev.group.id, 0)
        is RuleDisp -> if (prev.groupId != null) InGroupAt(prev.groupId, prev.childIndex + 1) else TopLevelAt(prev.topIndex + 1)
        is FooterDisp -> TopLevelAt(prev.topIndex + 1)
    }

private fun <T : LayoutRule<T>> resolveRuleDropTarget(
    reducedNodes: List<LayoutNode<T>>,
    centerOf: (String) -> Float?,
    pointerY: Float,
    collapsedGroupIds: Set<String>,
): LayoutDropTarget {
    val rows = reducedNodes.toDispRows(collapsedGroupIds)
    return targetForGap(rows, countRowsAbove(rows.size, { centerOf(rows[it].key) }, pointerY))
}

/**
 * How many of [size] top-to-bottom entries sit above [pointerY], from only the positions the list has on
 * screen — [centerAt] is null for anything the LazyColumn has scrolled away and therefore not measured.
 *
 * A list taller than its panel is measured with entries missing on *both* sides, so a missing position has
 * to be read from where it falls: before the visible window it is above the pointer, and the first one after
 * that window ends the walk, since centers only increase downward. Treating every missing entry as above
 * (a plain `?: -Infinity`) counted the whole unmeasured tail, sending any drop in a scrollable list to the
 * very end — which autoscrolling would otherwise make the common case rather than a corner.
 */
internal fun countRowsAbove(size: Int, centerAt: (Int) -> Float?, pointerY: Float): Int {
    var above = 0
    var sawMeasured = false
    for (i in 0 until size) {
        val center = centerAt(i)
        if (center == null) {
            if (sawMeasured) break
            above = i + 1
        } else {
            sawMeasured = true
            if (center >= pointerY) break
            above = i + 1
        }
    }
    return above
}

// The display-row keys bounding each top-level node: the span a group drag is hit-tested against and the
// drop indicator drawn from. A collapsed group contributes neither rules nor footer, so its span is the
// header alone — asking for its footer would find nothing and read as "scrolled away".
private fun <T : LayoutRule<T>> List<LayoutNode<T>>.topLevelSpans(
    collapsedGroupIds: Set<String>,
): List<Pair<String, String>> = map { node ->
    when (node) {
        is RuleNode -> node.rule.id to node.rule.id
        is GroupNode -> {
            val header = "h:${node.group.id}"
            header to if (node.group.id in collapsedGroupIds) header else "f:${node.group.id}"
        }
    }
}

// For a group drag: the top-level index to drop at = how many top-level nodes sit entirely above the
// pointer, measured by each node's last row (a loose rule's row, or a group's footer).
private fun resolveGroupDropIndex(
    spans: List<Pair<String, String>>,
    centerOf: (String) -> Float?,
    pointerY: Float,
): Int = countRowsAbove(spans.size, { centerOf(spans[it].second) }, pointerY)

// The Y (viewport px) of the drop indicator for a top-level insertion at [idx]: the top of the idx-th node,
// or the bottom of the one before it — whichever is on screen.
private fun topLevelGapY(
    spans: List<Pair<String, String>>,
    idx: Int,
    topOf: (String) -> Float?,
    bottomOf: (String) -> Float?,
): Float? = spans.getOrNull(idx)?.let { topOf(it.first) } ?: spans.getOrNull(idx - 1)?.let { bottomOf(it.second) }

/**
 * Pixels per second the list should scroll under a drag whose pointer sits at [pointerY], given the
 * viewport's [viewportStart]/[viewportEnd] in the same coordinate space as the lazy items' offsets.
 * Negative scrolls toward the start of the list; zero outside the [bandPx] edge bands.
 *
 * The ramp is quadratic in how deep the pointer is into a band so the shallow end stays precise: a linear
 * one is already crossing several rows a second the instant the band is entered, which makes dropping a rule
 * *near* an edge a matter of luck. Past the viewport edge the speed just holds at [maxPxPerSecond]. The
 * nearer edge wins, so a panel shorter than two bands can still scroll both ways.
 */
internal fun dragAutoScrollSpeed(
    pointerY: Float,
    viewportStart: Float,
    viewportEnd: Float,
    bandPx: Float,
    maxPxPerSecond: Float,
): Float {
    if (bandPx <= 0f) return 0f
    fun ramp(depth: Float): Float = (depth / bandPx).coerceIn(0f, 1f).let { it * it } * maxPxPerSecond
    val fromStart = pointerY - viewportStart
    val fromEnd = viewportEnd - pointerY
    return when {
        fromStart <= fromEnd -> if (fromStart < bandPx) -ramp(bandPx - fromStart) else 0f
        else -> if (fromEnd < bandPx) ramp(bandPx - fromEnd) else 0f
    }
}
