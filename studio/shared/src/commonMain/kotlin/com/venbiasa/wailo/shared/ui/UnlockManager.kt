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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.ProxySetupAction
import com.venbiasa.wailo.shared.ProxyState

@Composable
internal fun UnlockManager(
    proxy: ProxyState,
    onAction: (ProxySetupAction) -> Unit,
    onClose: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
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
                    "Unlock",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                CloseButton(onClose, contentDescription = "Close unlock")
            }
            RowDivider()

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            ) {
                MutedText(
                    "Decrypt HTTPS traffic from matching proxy hosts. Use * to match part of a host.",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                )
                RowDivider()
                if (!proxy.caInstalled) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Create and trust the local root before unlocking HTTPS hosts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { onAction(ProxySetupAction.InstallCertificate) },
                        ) {
                            Text("Create certificate")
                        }
                        if (proxy.certificateNotice.isNotEmpty()) {
                            Text(
                                proxy.certificateNotice,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    RowDivider()
                } else {
                    Text(
                        "Local root exists. Each client still has to trust it.",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RowDivider()
                }
                HostPatternSection(
                    title = "Unlocked hosts",
                    hosts = proxy.decryptHosts,
                    emptyText = "No hosts — proxy HTTPS traffic stays encrypted.",
                    addTooltip = "Unlock host",
                    onAddHost = { host ->
                        onAction(
                            ProxySetupAction.SetDecryptHosts(
                                if (host in proxy.decryptHosts) proxy.decryptHosts else proxy.decryptHosts + host,
                            ),
                        )
                    },
                    onRemoveHost = { host ->
                        onAction(
                            ProxySetupAction.SetDecryptHosts(proxy.decryptHosts.filterNot { it == host }),
                        )
                    },
                )
            }
        }
    }
}
