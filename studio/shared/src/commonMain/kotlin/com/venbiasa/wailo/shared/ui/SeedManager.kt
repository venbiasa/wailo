package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.venbiasa.wailo.shared.PickedFile
import com.venbiasa.wailo.shared.ResponseHeader
import com.venbiasa.wailo.shared.SeedNode
import com.venbiasa.wailo.shared.SeedRuleDef
import com.venbiasa.wailo.shared.findRule
import com.venbiasa.wailo.shared.groupOf
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_note_add
import com.venbiasa.wailo.shared.setRuleEnabled
import com.venbiasa.wailo.shared.upsertRule

/**
 * The Seed panel: the canned responses that answer held exchanges in the breakpoint window (ADR-0041).
 * A grouped, drag-orderable rule list (the shared [GroupedRuleListPage], same as Map Local and
 * Breakpoints) that falls through to the shared [ResponseRuleEditor]. Stateless over its inputs — the
 * host owns [nodes] and their persistence; this renders them and hands back a new layout via
 * [onLayoutChange] for any structural change.
 *
 * Order is the point here in a way it isn't for the other two panels: filling the breakpoint window's
 * queue flattens the groups away and keeps only this top-to-bottom order, which is both the match
 * priority and the sequence repeated requests are answered in. Groups still organize the panel and gate
 * whole sets of seeds out of a fill.
 *
 * [enabled] is the feature master (ADR-0030): off dims the list, disables every switch, and stops seeds
 * from filling or matching, without erasing what's configured. Seeds never reach a device — the desktop
 * consumes them — so unlike Map Local nothing is pushed either way.
 *
 * [initialDraft] seeds the editor from a traffic row's "Seed…": non-null opens straight into the form
 * with that captured exchange's match and status, and [initialBodySeed] pre-fills its body with the bytes
 * that were actually observed. Null shows the list.
 */
@Composable
internal fun SeedManager(
    nodes: List<SeedNode>,
    onLayoutChange: (List<SeedNode>) -> Unit,
    initialDraft: SeedRuleDef? = null,
    initialBodySeed: ByteArray? = null,
    enabled: Boolean = true,
    onEnabledChange: (Boolean) -> Unit = {},
    onClose: () -> Unit = {},
    onLoadBody: suspend (SeedRuleDef) -> ByteArray = { ByteArray(0) },
    onSaveBody: suspend (SeedRuleDef, ByteArray) -> Unit = { _, _ -> },
    onPickFile: suspend () -> PickedFile? = { null },
) {
    // Which groups are collapsed — transient view state, hoisted so it survives entering the editor and
    // coming back; not persisted (relaunch shows every group expanded), mirroring the other panels.
    val collapsedGroups = remember { mutableStateListOf<String>() }
    // The rule loaded into the editor page; null shows the list. Two pages is the whole navigation this
    // panel needs, so one slot covers it — no Navigation 3 back stack like Map Local's.
    var editing by remember { mutableStateOf<SeedRuleDef?>(null) }
    // Captured bytes to open a draft's body on, keyed by rule id so a seed can't inherit another's:
    // only a row-seeded draft has an entry, and anything else falls through to its persisted body.
    val bodySeeds = remember { mutableStateMapOf<String, ByteArray?>() }

    // A row's "Seed…" hands over a fresh draft, possibly while the panel already shows another page;
    // load it so the panel lands on the editor, leaving Back pointing at the seed list.
    LaunchedEffect(initialDraft) {
        val draft = initialDraft ?: return@LaunchedEffect
        bodySeeds[draft.id] = initialBodySeed
        editing = draft
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val target = editing
        if (target == null) {
            GroupedRuleListPage(
                title = "Seed",
                description = "Canned responses that answer paused exchanges, in order, one seed per hit.",
                emptyText = "No seeds yet. Add one, or right-click a captured row or a Map Local rule and choose \u201cSeed\u2026\u201d.",
                addRuleIcon = Res.drawable.ic_note_add,
                addRuleTooltip = "New seed",
                nodes = nodes,
                featureEnabled = enabled,
                onFeatureEnabledChange = onEnabledChange,
                collapsedGroupIds = collapsedGroups,
                onAddRule = {
                    // Same default as a new Map Local rule: a JSON Content-Type, changeable on the
                    // Headers tab, since an authored response body is almost always JSON here.
                    editing = SeedRuleDef(
                        id = SeedRuleDef.newId(),
                        headers = listOf(ResponseHeader("Content-Type", "application/json")),
                    )
                },
                onEditRule = { editing = it },
                onNodesChange = onLayoutChange,
                onClose = onClose,
            ) { rule -> SeedRuleContent(rule) }
        } else {
            val persisted = nodes.findRule(target.id)
            val groupEnabled = nodes.groupOf(target.id)?.enabled ?: true
            val ruleEnabled = (persisted ?: target).enabled
            // Keyed on the rule so the form's remembered state is rebuilt when a different seed is loaded
            // into an already-open editor, rather than keeping the one it opened on.
            key(target.id) {
                ResponseRuleEditor(
                    title = "Seed",
                    initial = ResponseDraft(
                        urlPattern = target.urlPattern,
                        method = target.method,
                        statusCode = target.statusCode,
                        headers = target.headers,
                    ),
                    // A seed is identified by what it matches, so there is no name to author.
                    initialName = null,
                    enabled = ruleEnabled,
                    enabledToggleable = enabled && groupEnabled,
                    onToggleEnabled = { next ->
                        // The toggle is shared with the list row, so a persisted seed commits immediately
                        // rather than waiting for Save; an unsaved draft toggles its own state.
                        if (nodes.findRule(target.id) != null) onLayoutChange(nodes.setRuleEnabled(target.id, next))
                        else editing = target.copy(enabled = next)
                    },
                    bodySeed = bodySeeds[target.id],
                    onLoadBody = { onLoadBody(target) },
                    onPickFile = onPickFile,
                    onSave = { _, draft, bytes ->
                        val rule = target.copy(
                            enabled = ruleEnabled,
                            urlPattern = draft.urlPattern,
                            method = draft.method,
                            statusCode = draft.statusCode,
                            headers = draft.headers,
                        )
                        // Body first: the layout is what makes the seed reachable, so writing it last
                        // means a seed is never listed with a body that isn't on disk yet.
                        onSaveBody(rule, bytes)
                        onLayoutChange(nodes.upsertRule(rule))
                        // The stored body is the truth from here on, so retire the captured bytes —
                        // reopening this seed in the still-open panel must show what was saved, not what
                        // the row happened to carry.
                        bodySeeds.remove(rule.id)
                    },
                    onSaved = { editing = null },
                    onBack = { editing = null },
                    onClose = onClose,
                )
            }
        }
    }
}

// A seed row's label (rendered in the shared row scaffold's clickable column): the URL it matches over a
// "METHOD → status" caption, the same stack the breakpoint rows use. There's no name line — a seed doesn't
// have one — and the URL leads because it's both the longest part and what tells two seeds apart; sharing
// a line with the method and status left it too cramped to read.
@Composable
private fun SeedRuleContent(rule: SeedRuleDef) {
    Text(
        rule.urlPattern.ifBlank { "(no pattern)" },
        style = monoSmall(),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        "${rule.method.ifBlank { "ANY" }}  \u2192  ${rule.statusCode}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
