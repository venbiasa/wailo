package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.format.BodyContent
import com.venbiasa.wailo.shared.format.bodyContent
import com.venbiasa.wailo.shared.format.contentType
import com.venbiasa.wailo.shared.format.formatBytes
import com.venbiasa.wailo.shared.format.formatClockTime
import com.venbiasa.wailo.shared.format.methodKind
import com.venbiasa.wailo.shared.format.statusKind
import com.venbiasa.wailo.shared.format.statusLabel
import com.venbiasa.wailo.protocol.Header
import okio.ByteString

@Composable
internal fun DetailPanel(
    entry: FlowEntry,
    modifier: Modifier,
    zoneOffsetMillis: Int,
    onClose: () -> Unit,
) {
    val exchange = entry.exchange
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        DetailHeader(entry, zoneOffsetMillis, onClose)
        RowDivider()

        var tab by remember { mutableStateOf(0) }
        SecondaryTabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Request", style = MaterialTheme.typography.labelLarge) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Response", style = MaterialTheme.typography.labelLarge) })
        }

        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> {
                    val request = exchange.request
                    MessagePane(
                        headers = request?.headers ?: emptyList(),
                        body = request?.body ?: ByteString.EMPTY,
                        declaredSize = request?.body_size ?: 0L,
                        truncated = request?.body_truncated == true,
                        notice = if (request == null) "No request captured." else null,
                    )
                }
                else -> {
                    val response = exchange.response
                    val notice = when {
                        response != null -> null
                        exchange.error.isNotEmpty() -> "Request failed before a response: ${exchange.error}"
                        else -> "No response captured yet."
                    }
                    MessagePane(
                        headers = response?.headers ?: emptyList(),
                        body = response?.body ?: ByteString.EMPTY,
                        declaredSize = response?.body_size ?: 0L,
                        truncated = response?.body_truncated == true,
                        notice = notice,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(entry: FlowEntry, zoneOffsetMillis: Int, onClose: () -> Unit) {
    val exchange = entry.exchange
    val request = exchange.request
    val response = exchange.response
    val method = request?.method?.ifEmpty { "?" } ?: "?"
    val code = response?.code
    val hasError = exchange.error.isNotEmpty()

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    method.uppercase(),
                    style = monoSmall(),
                    color = methodColor(methodKind(method)),
                )
                StatusPill(code, hasError)
                Text(
                    "${exchange.duration_ms} ms",
                    style = monoLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatClockTime(exchange.started_at_epoch_ms + zoneOffsetMillis),
                    style = monoLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    request?.url ?: "(no URL)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${entry.appId}  •  ${entry.deviceName}  •  ${entry.platform}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "✕",
            Modifier.clickable(onClick = onClose).padding(4.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusPill(code: Int?, hasError: Boolean) {
    val kind = statusKind(code, hasError)
    Surface(
        color = statusColor(kind),
        contentColor = onStatusColor(kind),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            statusLabel(code, hasError),
            style = monoLabel(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MessagePane(
    headers: List<Header>,
    body: ByteString,
    declaredSize: Long,
    truncated: Boolean,
    notice: String?,
) {
    SelectionContainer {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            if (notice != null) {
                Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
            }

            SectionLabel("Headers (${headers.size})")
            if (headers.isEmpty()) {
                MutedText("No headers")
            } else {
                headers.forEach { KeyValueRow(it.name, it.value_) }
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel("Body")
            BodyView(body, headers.contentType(), declaredSize, truncated)
        }
    }
}

@Composable
private fun BodyView(body: ByteString, contentType: String?, declaredSize: Long, truncated: Boolean) {
    val content = remember(body, contentType) { bodyContent(body, contentType) }
    when (content) {
        BodyContent.Empty -> MutedText("No body")
        is BodyContent.Binary -> MutedText("⟨ binary • ${formatBytes(content.size.toLong())} ⟩")
        is BodyContent.Text -> {
            if (content.json) {
                Text("JSON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
            if (truncated) {
                MutedText("(truncated during capture • declared ${formatBytes(declaredSize)})")
                Spacer(Modifier.height(4.dp))
            }
            Text(
                content.text,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                style = monoSmall(),
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, Modifier.width(200.dp), style = monoLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = monoSmall(), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
