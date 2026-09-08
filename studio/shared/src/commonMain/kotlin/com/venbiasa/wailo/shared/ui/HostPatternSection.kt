package com.venbiasa.wailo.shared.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.format.isValidHostPattern
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_add
import com.venbiasa.wailo.shared.resources.ic_arrow_drop_down
import com.venbiasa.wailo.shared.resources.ic_delete
import org.jetbrains.compose.resources.vectorResource

private val SectionItemIndent = 16.dp

@Composable
internal fun HostPatternSection(
    title: String,
    hosts: List<String>,
    emptyText: String,
    addTooltip: String,
    onAddHost: (String) -> Unit,
    onRemoveHost: (String) -> Unit,
    showEnabledSwitch: Boolean = false,
    enabled: Boolean = true,
    armable: Boolean = true,
    onToggleEnabled: (Boolean) -> Unit = {},
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
            val chevron by animateFloatAsState(if (collapsed) -90f else 0f, label = "sectionChevron")
            IconButton(onClick = { collapsed = !collapsed }, modifier = Modifier.size(28.dp)) {
                Icon(
                    vectorResource(Res.drawable.ic_arrow_drop_down),
                    contentDescription = if (collapsed) "Expand $title" else "Collapse $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).rotate(chevron),
                )
            }
            if (showEnabledSwitch) {
                HoverTooltip(if (hosts.isEmpty()) "Add a host to enable" else if (enabled) "On" else "Off") {
                    CompactSwitch(
                        checked = enabled,
                        onCheckedChange = onToggleEnabled,
                        enabled = armable && hosts.isNotEmpty(),
                    )
                }
                Spacer(Modifier.width(12.dp))
            } else {
                Spacer(Modifier.width(8.dp))
            }
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
                MutedText(
                    emptyText,
                    Modifier.fillMaxWidth()
                        .padding(start = SectionItemIndent, end = 8.dp, top = 10.dp, bottom = 10.dp),
                )
            } else {
                hosts.forEach { host ->
                    HostPatternRow(host = host, onRemove = { onRemoveHost(host) })
                    RowDivider()
                }
            }
        }
    }
}

@Composable
private fun HostPatternRow(host: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = SectionItemIndent, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                host,
                modifier = Modifier.fillMaxWidth(),
                style = monoSmall(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
        Modifier.fillMaxWidth()
            .padding(start = SectionItemIndent, end = 8.dp, top = 6.dp, bottom = 6.dp),
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
                .onFocusChanged { state ->
                    if (state.isFocused) hasFocused = true else if (hasFocused) onClose()
                }
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
