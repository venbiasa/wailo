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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.ScriptHookAvailability
import com.venbiasa.wailo.shared.ScriptIssue
import com.venbiasa.wailo.shared.ScriptNode
import com.venbiasa.wailo.shared.ScriptRuleDef
import com.venbiasa.wailo.shared.duplicateRuleName
import com.venbiasa.wailo.shared.findRule
import com.venbiasa.wailo.shared.insertRuleAfter
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_arrow_back
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import com.venbiasa.wailo.shared.resources.ic_note_add
import com.venbiasa.wailo.shared.setRuleEnabled
import com.venbiasa.wailo.shared.upsertRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource

@Composable
fun ScriptManager(
    nodes: List<ScriptNode>,
    onLayoutChange: (List<ScriptNode>) -> Unit,
    issues: List<ScriptIssue>,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onValidate: suspend (String) -> ScriptHookAvailability?,
    onClose: () -> Unit,
    onExportRules: suspend () -> String = { "" },
    onImportRules: suspend () -> String = { "" },
) {
    var editedId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<ScriptRuleDef?>(null) }
    val collapsedGroups = remember { mutableStateListOf<String>() }
    val liveNodes = rememberUpdatedState(nodes)
    val scope = rememberCoroutineScope()
    var archiveNotice by remember { mutableStateOf("") }
    LaunchedEffect(archiveNotice) {
        if (archiveNotice.isNotBlank()) {
            delay(8_000)
            archiveNotice = ""
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val editing = draft ?: editedId?.let(nodes::findRule)
        if (editing == null) {
            GroupedRuleListPage(
                title = "Scripts",
                description = "Transform matching requests and responses with JavaScript.",
                emptyText = "No scripts yet",
                addRuleIcon = Res.drawable.ic_note_add,
                addRuleTooltip = "New script",
                nodes = nodes,
                featureEnabled = enabled,
                onFeatureEnabledChange = onEnabledChange,
                collapsedGroupIds = collapsedGroups,
                onAddRule = {
                    draft = ScriptRuleDef(id = ScriptRuleDef.newId())
                    editedId = draft?.id
                },
                onEditRule = { editedId = it.id },
                onNodesChange = onLayoutChange,
                onClose = onClose,
                notice = archiveNotice,
                overflowActions = listOf(
                    ContextMenuAction("Export all rules\u2026") {
                        scope.launch { archiveNotice = onExportRules() }
                    },
                    ContextMenuAction("Import rules\u2026") {
                        scope.launch { archiveNotice = onImportRules() }
                    },
                ),
                rowActions = { rule ->
                    listOf(
                        ContextMenuAction("Duplicate") {
                            onLayoutChange(
                                liveNodes.value.insertRuleAfter(
                                    rule.id,
                                    rule.copy(
                                        id = ScriptRuleDef.newId(),
                                        name = duplicateRuleName(rule.name),
                                    ),
                                ),
                            )
                        },
                    )
                },
            ) { rule ->
                val issue = issues.firstOrNull { it.ruleId == rule.id }
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        rule.name.ifBlank { rule.id },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(rule.method.ifBlank { "Any method" })
                            append("  ·  ")
                            append(
                                listOfNotNull(
                                    "Request".takeIf { rule.onRequest },
                                    "Response".takeIf { rule.onResponse },
                                ).joinToString(" + "),
                            )
                            append("  ·  ")
                            append(rule.urlPattern)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (issue == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (issue != null) {
                        Text(
                            issue.code.lowercase().replace('_', ' '),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            ScriptEditor(
                initial = editing,
                persisted = nodes.findRule(editing.id) != null,
                onValidate = onValidate,
                onToggleEnabled = { next ->
                    if (nodes.findRule(editing.id) != null) {
                        onLayoutChange(liveNodes.value.setRuleEnabled(editing.id, next))
                    } else {
                        draft = editing.copy(enabled = next)
                    }
                },
                onSave = { rule ->
                    onLayoutChange(liveNodes.value.upsertRule(rule))
                    draft = null
                    editedId = rule.id
                },
                onBack = {
                    draft = null
                    editedId = null
                },
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun ScriptEditor(
    initial: ScriptRuleDef,
    persisted: Boolean,
    onValidate: suspend (String) -> ScriptHookAvailability?,
    onToggleEnabled: (Boolean) -> Unit,
    onSave: (ScriptRuleDef) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var pattern by remember(initial.id) { mutableStateOf(initial.urlPattern) }
    var method by remember(initial.id) { mutableStateOf(initial.method) }
    var enabled by remember(initial.id) { mutableStateOf(initial.enabled) }
    val editor = rememberCodeEditorState(initial.source)
    var validating by remember(initial.id) { mutableStateOf(true) }
    var validatedSource by remember(initial.id) { mutableStateOf<String?>(null) }
    var hooks by remember(initial.id) { mutableStateOf<ScriptHookAvailability?>(null) }
    editor.version
    val source = editor.currentText()

    LaunchedEffect(initial.id, editor.revision) {
        validating = true
        validatedSource = null
        hooks = null
        delay(350)
        if (source.toByteArray().size <= MaxScriptSourceBytes) {
            hooks = runCatching { onValidate(source) }.getOrNull()
        }
        validatedSource = source
        validating = false
    }

    val sourceTooLarge = source.toByteArray().size > MaxScriptSourceBytes
    val valid = validatedSource == source && hooks != null
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .height(TopBarHeight)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    vectorResource(Res.drawable.ic_arrow_back),
                    contentDescription = "Back to scripts",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Script",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            HoverTooltip(if (enabled) "Enabled" else "Disabled") {
                CompactSwitch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        if (persisted) onToggleEnabled(it)
                    },
                )
            }
            CloseButton(onClose, contentDescription = "Close panel")
        }
        RowDivider()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text("URL pattern") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column {
                Text(
                    "Method",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ScriptMethodDropdown(method = method, onSelect = { method = it })
            }
        }
        Text(
            when {
                sourceTooLarge -> "Source must be 64 KiB or smaller."
                validating -> "Validating\u2026"
                hooks == null -> "Define a synchronous onRequest or onResponse function."
                else -> listOfNotNull(
                    "Request".takeIf { hooks?.onRequest == true },
                    "Response".takeIf { hooks?.onResponse == true },
                ).joinToString(" + ", prefix = "Hooks: ")
            },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (!valid && !validating) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CodeEditor(
            state = editor,
            language = CodeLanguage.JavaScript,
            readOnly = false,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                enabled = valid && name.isNotBlank() && pattern.isNotBlank(),
                onClick = {
                    val availability = hooks ?: return@Button
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            enabled = enabled,
                            urlPattern = pattern.trim(),
                            method = method.trim(),
                            source = source,
                            onRequest = availability.onRequest,
                            onResponse = availability.onResponse,
                        ),
                    )
                },
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun ScriptMethodDropdown(method: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Box {
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(interactionSource = interactionSource, indication = null) { expanded = true },
        ) {
            CompactFieldDecoration(
                value = method.ifBlank { "Any" },
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
                        method.ifBlank { "Any" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ScriptMethodOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.ifBlank { "Any" }) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private val ScriptMethodOptions = listOf("", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

private const val MaxScriptSourceBytes = 64 * 1024
