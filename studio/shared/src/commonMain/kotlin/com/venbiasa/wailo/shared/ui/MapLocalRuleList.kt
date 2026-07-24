package com.venbiasa.wailo.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.text.style.TextOverflow
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_note_add

/**
 * The Map Local list page: the shared grouped, drag-orderable rule list ([GroupedRuleListPage]) filled
 * with Map Local's row content and copy (ADR-0026). `shared` stays stateless — structural changes are
 * handed to [onNodesChange]; the host persists them and recompiles the device match-set. Top-to-bottom
 * order is the match priority. [onAddRule]/[onEditRule] navigate to the editor (a rule isn't committed
 * until Save).
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
    GroupedRuleListPage(
        title = "Map Local",
        description = "Answer matching requests with a local response instead of hitting the network.",
        emptyText = "No rules yet. Add a rule, or a group to organize rules you can toggle together.",
        addRuleIcon = Res.drawable.ic_note_add,
        addRuleTooltip = "New mapping rule",
        nodes = nodes,
        collapsedGroupIds = collapsedGroupIds,
        onAddRule = onAddRule,
        onEditRule = onEditRule,
        onNodesChange = onNodesChange,
        onClose = onClose,
    ) { rule -> MapLocalRuleContent(rule) }
}

// A Map Local rule row's label (rendered in the shared row scaffold's clickable column): the rule's name
// over a "METHOD → url" summary.
@Composable
private fun MapLocalRuleContent(rule: MapLocalRuleDef) {
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
