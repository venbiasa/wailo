package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.format.isValidHostPattern
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_delete
import org.jetbrains.compose.resources.vectorResource

/**
 * The capture-allowlist panel: the (few, intentional) host patterns whose request/response bodies are
 * captured. Metadata is always captured for every request; only body bytes are gated by this list, so
 * memory stays bounded (Proxyman-style "unlock"). Stateless over its inputs — the host owns
 * [unlockedHosts] and its persistence, and pushes it to devices; this only renders it and hands changes
 * back via [onUnlockHost]/[onLockHost]. `*` matches any run of characters, so `*.example.com` unlocks a
 * whole subdomain. It fills whatever surface it's given (the studio's right tool panel).
 */
@Composable
internal fun CaptureAllowlistManager(
    unlockedHosts: List<String>,
    onUnlockHost: (String) -> Unit,
    onLockHost: (String) -> Unit,
    onClose: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    // A background surface with a surfaceContainer header band (not a fully-tinted panel) so the top bar
    // matches the other panels exactly (same fill and the shared [TopBarHeight]). Tapping anywhere off the
    // host field drops its focus — the click-away counterpart to Esc; child controls (Close, Unlock, the
    // field) hit-test first, so this only fires on empty space.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .height(TopBarHeight)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Capture Allowlist",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                CloseButton(onClose, contentDescription = "Close capture allowlist")
            }
            RowDivider()

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MutedText(
                    "Bodies are captured only for these hosts — everything else records metadata only " +
                        "(use * as a wildcard, e.g. *.example.com).",
                )
                HostInput(onAdd = onUnlockHost)
                if (unlockedHosts.isEmpty()) {
                    MutedText("No hosts unlocked. Bodies are not captured until you add one.")
                } else {
                    unlockedHosts.forEach { host ->
                        HostRow(host = host, onRemove = { onLockHost(host) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HostInput(onAdd: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf("") }
    // Validate only when the user commits (Unlock button or Enter), never while typing — so a
    // partially-typed host (e.g. a bare "s" with no dot or wildcard) can't flash red. Editing the field
    // clears a prior error.
    var showError by remember { mutableStateOf(false) }
    val submit = {
        val trimmed = text.trim()
        if (isValidHostPattern(trimmed)) {
            onAdd(trimmed)
            text = ""
            showError = false
            focusManager.clearFocus()
        } else {
            showError = true
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactOutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    showError = false
                },
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
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
            Button(onClick = submit, enabled = text.isNotBlank()) {
                Text("Unlock")
            }
        }
        if (showError) {
            Text(
                "Enter a domain like example.com or *.example.com.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun HostRow(host: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            host,
            modifier = Modifier.weight(1f),
            style = monoSmall(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_delete),
                contentDescription = "Lock $host (stop capturing bodies)",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
