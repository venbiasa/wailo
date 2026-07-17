package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_add
import com.venbiasa.wailo.shared.resources.ic_delete
import org.jetbrains.compose.resources.vectorResource

/**
 * The Map Local rules window: a list of rules that fall through to an add/edit form. Stateless over
 * its inputs — the host owns the rule list and its persistence; this only renders and calls back.
 *
 * [initialDraft] seeds the editor: non-null opens straight into the form (used when launched from a
 * traffic row so the URL is pre-filled), null shows the list. [onUpsert] adds or replaces a rule by
 * id; [onRemove] deletes by id; [onPickFile] opens the host's native file chooser and returns the
 * chosen path (or null if cancelled).
 */
@Composable
fun MapLocalManager(
    rules: List<MapLocalRuleDef>,
    initialDraft: MapLocalRuleDef?,
    onUpsert: (MapLocalRuleDef) -> Unit,
    onRemove: (String) -> Unit,
    onPickFile: () -> String?,
) {
    // Editor target: null = list. Seeded from initialDraft and re-seeded whenever the host hands over
    // a new one (a fresh draft object per launch), so opening from a row lands in the form each time.
    var editing by remember(initialDraft) { mutableStateOf(initialDraft) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val current = editing
        if (current == null) {
            RuleList(
                rules = rules,
                onAdd = { editing = MapLocalRuleDef(id = MapLocalRuleDef.newId()) },
                onEdit = { editing = it },
                onToggle = { rule -> onUpsert(rule.copy(enabled = !rule.enabled)) },
                onRemove = onRemove,
            )
        } else {
            // Key on the rule id so switching which rule is edited resets the form's field state.
            key(current.id) {
                RuleEditor(
                    initial = current,
                    onPickFile = onPickFile,
                    onSave = {
                        onUpsert(it)
                        editing = null
                    },
                    onCancel = { editing = null },
                )
            }
        }
    }
}

@Composable
private fun RuleList(
    rules: List<MapLocalRuleDef>,
    onAdd: () -> Unit,
    onEdit: (MapLocalRuleDef) -> Unit,
    onToggle: (MapLocalRuleDef) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Map Local", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Button(onClick = onAdd) {
                Icon(vectorResource(Res.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add rule")
            }
        }
        RowDivider()
        if (rules.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No rules yet. Add one to answer a request with a local file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rules, key = { it.id }) { rule ->
                    RuleRow(rule = rule, onEdit = onEdit, onToggle = onToggle, onRemove = onRemove)
                    RowDivider()
                }
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: MapLocalRuleDef,
    onEdit: (MapLocalRuleDef) -> Unit,
    onToggle: (MapLocalRuleDef) -> Unit,
    onRemove: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onEdit(rule) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = rule.enabled, onCheckedChange = { onToggle(rule) })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                rule.urlPattern.ifBlank { "(no pattern)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val methods = if (rule.methods.isEmpty()) "ANY" else rule.methods.joinToString(", ")
            Text(
                "$methods  \u2192  ${fileName(rule.filePath).ifBlank { "(no file)" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onRemove(rule.id) }, modifier = Modifier.size(36.dp)) {
            Icon(
                vectorResource(Res.drawable.ic_delete),
                contentDescription = "Delete rule",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RuleEditor(
    initial: MapLocalRuleDef,
    onPickFile: () -> String?,
    onSave: (MapLocalRuleDef) -> Unit,
    onCancel: () -> Unit,
) {
    var urlPattern by remember { mutableStateOf(initial.urlPattern) }
    var filePath by remember { mutableStateOf(initial.filePath) }
    var methods by remember { mutableStateOf(initial.methods.joinToString(", ")) }
    var statusCode by remember { mutableStateOf(initial.statusCode.toString()) }
    var contentType by remember { mutableStateOf(initial.contentType) }
    var enabled by remember { mutableStateOf(initial.enabled) }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Map Local rule",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
        RowDivider()
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LabeledField("URL pattern") {
                OutlinedTextField(
                    value = urlPattern,
                    onValueChange = { urlPattern = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://api.example.com/v1/*") },
                )
            }
            LabeledField("Local file") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = filePath,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Choose a file\u2026") },
                    )
                    Button(onClick = { onPickFile()?.let { filePath = it } }) { Text("Choose\u2026") }
                }
            }
            LabeledField("Methods (comma-separated, blank = any)") {
                OutlinedTextField(
                    value = methods,
                    onValueChange = { methods = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("GET, POST") },
                )
            }
            LabeledField("Status code") {
                OutlinedTextField(
                    value = statusCode,
                    onValueChange = { next -> statusCode = next.filter { it.isDigit() }.take(3) },
                    singleLine = true,
                    modifier = Modifier.width(160.dp),
                    placeholder = { Text("200") },
                )
            }
            LabeledField("Content-Type (blank = auto from extension)") {
                OutlinedTextField(
                    value = contentType,
                    onValueChange = { contentType = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("application/json") },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enabled", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }
        RowDivider()
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            enabled = enabled,
                            urlPattern = urlPattern.trim(),
                            methods = methods.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                            filePath = filePath,
                            statusCode = statusCode.toIntOrNull() ?: 200,
                            contentType = contentType.trim(),
                        ),
                    )
                },
                enabled = urlPattern.isNotBlank() && filePath.isNotBlank(),
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

private fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
