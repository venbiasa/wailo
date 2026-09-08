package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.PickedFile
import com.venbiasa.wailo.shared.ResponseHeader
import com.venbiasa.wailo.shared.duplicateRuleName
import com.venbiasa.wailo.shared.findRule
import com.venbiasa.wailo.shared.groupOf
import com.venbiasa.wailo.shared.insertRuleAfter
import com.venbiasa.wailo.shared.setRuleEnabled
import com.venbiasa.wailo.shared.upsertRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

// Long enough to read a sentence naming what landed, short enough that it never becomes panel furniture.
private const val ArchiveNoticeMillis = 8_000L

/**
 * The Map Local panel: an interleaved list of groups + loose rules (ADR-0026) that falls through to an
 * add/edit form. Stateless over its inputs — the host owns [nodes] (the ordered layout) and its
 * persistence; this renders it and hands back a new layout via [onLayoutChange] for every structural
 * change (reorder, group toggle/rename/delete, rule add/edit/delete/move). Top-to-bottom order is the
 * match priority. It fills whatever surface it's given (the studio's right tool panel, ADR-0021).
 *
 * [enabled] is the feature master (ADR-0030): off dims the list and disables every rule/group switch (and
 * the editor's), their remembered state kept, while the host pushes no rules — so Map Local goes inert
 * without erasing what's configured. [onEnabledChange] flips it.
 *
 * [initialDraft] seeds the editor: non-null opens straight into the form (used when launched from a
 * traffic row so the URL/method are pre-filled), null shows the list. [initialBodySeed] pre-fills the
 * body for that draft with the captured response's raw bytes (decoded as JSON text, or previewed as an
 * image, depending on its Content-Type). [onLoadBody]/[onSaveBody] read and commit a rule's authored
 * body as bytes; where they live is the host's business (the daemon's, in practice — ADR-0085), so
 * `shared` still never touches the filesystem. [onPickFile] asks the host to open a file picker for a
 * body file (JSON/text or image) and hand back its bytes. [onSeedFromRule] copies a rule (right-click
 * "Seed…") into the Seed list, which is the host's job since it spans both layouts (ADR-0041).
 * [onClose] dismisses the whole panel (the tool-panel close affordance).
 */
@Composable
fun MapLocalManager(
    nodes: List<MapLocalNode>,
    initialDraft: MapLocalRuleDef?,
    initialBodySeed: ByteArray? = null,
    onLayoutChange: (List<MapLocalNode>) -> Unit,
    enabled: Boolean = true,
    onEnabledChange: (Boolean) -> Unit = {},
    onClose: () -> Unit = {},
    onLoadBody: suspend (MapLocalRuleDef) -> ByteArray = { ByteArray(0) },
    onSaveBody: suspend (MapLocalRuleDef, ByteArray) -> Unit = { _, _ -> },
    onPickFile: suspend () -> PickedFile? = { null },
    onSeedFromRule: (MapLocalRuleDef) -> Unit = {},
    onExportRules: suspend () -> String = { "" },
    onImportRules: suspend () -> String = { "" },
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
    val bodySeeds = remember { mutableStateMapOf<String, ByteArray?>() }

    fun openEditor(rule: MapLocalRuleDef, seed: ByteArray? = null) {
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
    // `nodes` value it captured when first shown and won't see later host edits (e.g. a toggle) until you
    // navigate. Reading the layout through this stable State *inside* the entries makes NavEntry.Content —
    // itself a restart scope — recompose on every change, so the list and the editor's enabled switch
    // stay live and in sync instead of frozen until navigation.
    val liveNodes = rememberUpdatedState(nodes)
    // The feature master has the same NavDisplay-caching hazard as [liveNodes]: passed as a plain value it
    // would stay stuck in the cached entry until the next navigation. Read through a stable State inside
    // the entries so toggling it recomposes the list (and the editor's toggle) at once (ADR-0030).
    val liveEnabled = rememberUpdatedState(enabled)
    // Which groups are collapsed — transient view state hoisted above the NavDisplay entries so it
    // survives navigating into the editor and back; not persisted (relaunch shows every group expanded).
    val collapsedGroups = remember { mutableStateListOf<String>() }

    // Export/import report one line and nothing else, so the result stays here instead of crossing the
    // host boundary as state: the host does the file work and returns what to say, blank if cancelled.
    var archiveNotice by remember { mutableStateOf("") }
    val panelScope = rememberCoroutineScope()
    LaunchedEffect(archiveNotice) {
        if (archiveNotice.isNotBlank()) {
            delay(ArchiveNoticeMillis)
            archiveNotice = ""
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavDisplay(
            backStack = backStack,
            // Never pop the root list off the stack (an empty NavDisplay back stack is illegal); the
            // panel's Close affordance dismisses it instead.
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryProvider = { destination ->
                when (destination) {
                    is RuleListDestination -> NavEntry(destination) {
                        RuleListPage(
                            nodes = liveNodes.value,
                            featureEnabled = liveEnabled.value,
                            onFeatureEnabledChange = onEnabledChange,
                            onAddRule = {
                                // New rules default to the inline editor (the common "author a body"
                                // path) and a JSON Content-Type header, changeable on the Headers tab.
                                openEditor(
                                    MapLocalRuleDef(
                                        id = MapLocalRuleDef.newId(),
                                        inline = true,
                                        headers = listOf(ResponseHeader("Content-Type", "application/json")),
                                    ),
                                )
                            },
                            onEditRule = { openEditor(it) },
                            onNodesChange = onLayoutChange,
                            onDuplicateRule = { rule ->
                                val copy = rule.copy(
                                    id = MapLocalRuleDef.newId(),
                                    name = duplicateRuleName(rule.name),
                                )
                                // The source's bytes are the host's, so the copy is a fetch: stage them
                                // before the layout goes out (the editor's Save order, ADR-0086), and
                                // re-read the layout after that round-trip rather than committing the
                                // one this was composed with.
                                panelScope.launch {
                                    onSaveBody(copy, onLoadBody(rule))
                                    onLayoutChange(liveNodes.value.insertRuleAfter(rule.id, copy))
                                }
                            },
                            onSeedFromRule = onSeedFromRule,
                            collapsedGroupIds = collapsedGroups,
                            onClose = onClose,
                            notice = archiveNotice,
                            archiveActions = listOf(
                                // "all rules" because the file carries every authored tool, not just this
                                // panel's — one backup rather than four to keep track of.
                                ContextMenuAction("Export all rules\u2026") {
                                    panelScope.launch { archiveNotice = onExportRules() }
                                },
                                ContextMenuAction("Import rules\u2026") {
                                    panelScope.launch { archiveNotice = onImportRules() }
                                },
                            ),
                        )
                    }
                    is RuleEditorDestination -> NavEntry(destination) {
                        // Read the live layout here (not the captured `nodes`) so this entry tracks host
                        // edits — see [liveNodes].
                        val currentNodes = liveNodes.value
                        // Resolve from the pending draft (new/seeded) or the persisted layout.
                        val initial = drafts[destination.ruleId] ?: currentNodes.findRule(destination.ruleId)
                        if (initial == null) {
                            // The rule was removed out from under an open editor: fall back to the list.
                            LaunchedEffect(destination.ruleId) {
                                if (backStack.size > 1) backStack.removeLastOrNull()
                            }
                        } else {
                            // The enabled toggle is shared with the list row, so it commits immediately
                            // rather than waiting for Save — otherwise the editor and list switches drift.
                            // A rule inside an off group — or under an off feature master — can't be toggled
                            // here (they gate it, its own state preserved); an ungated rule is toggleable.
                            val persisted = currentNodes.findRule(destination.ruleId)
                            val ruleEnabled = (persisted ?: initial).enabled
                            val groupEnabled = currentNodes.groupOf(destination.ruleId)?.enabled ?: true
                            ResponseRuleEditor(
                                title = "Mapping Rule",
                                initial = ResponseDraft(
                                    urlPattern = initial.urlPattern,
                                    method = initial.method,
                                    statusCode = initial.statusCode,
                                    delayMillis = initial.delayMillis,
                                    headers = initial.headers,
                                ),
                                initialName = initial.name,
                                persisted = persisted != null,
                                enabled = ruleEnabled,
                                enabledToggleable = liveEnabled.value && groupEnabled,
                                onToggleEnabled = { next ->
                                    // Re-read the live layout at click time so committing the flag never
                                    // clobbers a concurrent edit with a stale snapshot.
                                    if (liveNodes.value.findRule(destination.ruleId) != null) {
                                        onLayoutChange(liveNodes.value.setRuleEnabled(destination.ruleId, next))
                                    } else {
                                        drafts[destination.ruleId]?.let { drafts[destination.ruleId] = it.copy(enabled = next) }
                                    }
                                },
                                bodySeed = bodySeeds[destination.ruleId],
                                onLoadBody = { onLoadBody(initial) },
                                onPickFile = onPickFile,
                                onSave = { name, draft, bytes ->
                                    // Every rule is now inline: the body is authored here and persisted to
                                    // the host's app-managed file store (no user-chosen file path anymore).
                                    val rule = initial.copy(
                                        enabled = ruleEnabled,
                                        name = name,
                                        urlPattern = draft.urlPattern,
                                        method = draft.method,
                                        statusCode = draft.statusCode,
                                        delayMillis = draft.delayMillis,
                                        headers = draft.headers,
                                        filePath = "",
                                        inline = true,
                                    )
                                    onSaveBody(rule, bytes)
                                    onLayoutChange(liveNodes.value.upsertRule(rule))
                                },
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
