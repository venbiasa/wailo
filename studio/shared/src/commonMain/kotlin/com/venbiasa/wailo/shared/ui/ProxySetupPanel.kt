package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.ProxySetupAction
import com.venbiasa.wailo.shared.ProxyState
import com.venbiasa.wailo.shared.ProxyTargetInfo
import com.venbiasa.wailo.shared.ProxyTargetKind
import com.venbiasa.wailo.shared.ProxyTargetTrust
import com.venbiasa.wailo.shared.ProxyTargets
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_refresh
import com.venbiasa.wailo.shared.theme.LocalWailoColors

private enum class PhonePlatform { IPhone, Android }

/**
 * Proxy target setup is detection-first; state comes from the daemon so every frontend agrees
 * (ADR-0085/0090).
 */
@Composable
internal fun ProxySetupCard(
    proxy: ProxyState,
    targets: ProxyTargets,
    onAction: (ProxySetupAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 420.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.heightIn(max = 560.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .height(TopBarHeight)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Set up a device",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                PanelIconButton(
                    icon = Res.drawable.ic_refresh,
                    contentDescription = "Look again",
                    onClick = { onAction(ProxySetupAction.RefreshTargets) },
                    enabled = !targets.loading,
                )
                CloseButton(onClose, contentDescription = "Close device setup")
            }
            RowDivider()

            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                MutedText(
                    "For anything that cannot host the Wailo SDK — a browser, a release build, someone " +
                        "else's app. Its traffic joins the list ticked in the Proxy column.",
                )

                SetupSection("On this machine") {
                    TargetsSection(targets = targets, onAction = onAction)
                }

                SetupSection("A physical phone") {
                    PhoneSteps(proxy = proxy, targets = targets, onAction = onAction)
                }
            }
        }
    }
}

@Composable
private fun SetupSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}

@Composable
private fun TargetsSection(targets: ProxyTargets, onAction: (ProxySetupAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            // `supported` is only authoritative after the first fetch.
            targets.loading && targets.targets.isEmpty() -> MutedText("Looking…")
            !targets.supported -> MutedText(
                "No device tooling found here — Wailo looks for Android platform-tools and Xcode's " +
                    "command line tools. A phone can still be configured manually, below.",
            )
            targets.targets.isEmpty() -> MutedText(
                "Nothing configurable is connected. Start a simulator or emulator, or connect an Android " +
                    "phone with ADB debugging, then press Look again.",
            )
            else -> targets.targets.forEach { target ->
                ProxyTargetRow(
                    target = target,
                    busy = targets.busyId == target.id,
                    // Device remount operations must not overlap.
                    enabled = targets.busyId == null,
                    onAction = onAction,
                    horizontalPadding = 0.dp,
                )
            }
        }
        targets.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (targets.notice.isNotEmpty()) MutedText(targets.notice)
    }
}

@Composable
internal fun ProxyTargetRow(
    target: ProxyTargetInfo,
    busy: Boolean,
    enabled: Boolean,
    onAction: (ProxySetupAction) -> Unit,
    horizontalPadding: Dp = 16.dp,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp)
                .background(
                    if (target.proxySet) LocalWailoColors.current.success else MaterialTheme.colorScheme.outline,
                    CircleShape,
                ),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                target.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOfNotNull(kindLabel(target.kind), target.detail.takeIf { it.isNotEmpty() })
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                trustLabel(target),
                style = MaterialTheme.typography.labelSmall,
                color = when (target.trust) {
                    ProxyTargetTrust.SYSTEM -> LocalWailoColors.current.success
                    ProxyTargetTrust.USER -> LocalWailoColors.current.warning
                    ProxyTargetTrust.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (target.actionRequired.isNotEmpty()) {
                Text(
                    target.actionRequired,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalWailoColors.current.warning,
                )
            }
        }
        if (target.cleanupPending) {
            Button(
                enabled = enabled,
                onClick = { onAction(ProxySetupAction.ClearTarget(target.id)) },
            ) { Text(if (busy) "Working…" else "Retry cleanup") }
        } else if (target.proxySet && target.certificateCurrent) {
            TextButton(
                enabled = enabled,
                onClick = { onAction(ProxySetupAction.ClearTarget(target.id)) },
            ) { Text(if (busy) "Working…" else "Release") }
        } else if (
            target.proxySet &&
            target.trust == ProxyTargetTrust.NONE &&
            target.actionRequired.isNotEmpty()
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Finish on device",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    enabled = enabled,
                    onClick = { onAction(ProxySetupAction.ClearTarget(target.id)) },
                ) { Text(if (busy) "Working…" else "Release") }
            }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Button(
                    enabled = enabled,
                    onClick = { onAction(ProxySetupAction.SetUpTarget(target.id)) },
                ) {
                    Text(
                        if (busy) "Working…" else if (target.proxySet) "Repair" else "Set up",
                    )
                }
                if (target.proxySet) {
                    TextButton(
                        enabled = enabled,
                        onClick = { onAction(ProxySetupAction.ClearTarget(target.id)) },
                    ) { Text("Release") }
                }
            }
        }
    }
}

private fun kindLabel(kind: ProxyTargetKind) = when (kind) {
    ProxyTargetKind.IOS_SIMULATOR -> "iOS Simulator"
    ProxyTargetKind.ANDROID_EMULATOR -> "Android emulator"
    ProxyTargetKind.ANDROID_DEVICE -> "Android phone"
}

private fun trustLabel(target: ProxyTargetInfo) = when {
    target.cleanupPending -> "Cleanup needs another attempt"
    target.proxySet && target.trust != ProxyTargetTrust.NONE && !target.certificateCurrent ->
        "The trusted certificate needs repair"
    target.proxySet && target.actionRequired.isNotEmpty() -> "Captured; HTTPS needs the step below"
    target.trust == ProxyTargetTrust.SYSTEM -> "Every app here trusts Wailo"
    target.trust == ProxyTargetTrust.USER -> "Debug builds trust Wailo; release builds will not"
    target.proxySet -> "Captured, but HTTPS stays an encrypted tunnel"
    else -> "Not set up"
}

/** Physical-phone routing stays manual because neither platform exposes Wi-Fi proxy configuration. */
@Composable
private fun PhoneSteps(
    proxy: ProxyState,
    targets: ProxyTargets,
    onAction: (ProxySetupAction) -> Unit,
) {
    var platform by remember { mutableStateOf(PhonePlatform.IPhone) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!proxy.running) {
            ReadinessStep(
                text = "The proxy is off. Start it before changing the phone's Wi-Fi settings.",
                action = "Start the proxy",
                onClick = { onAction(ProxySetupAction.SetProxyEnabled(true)) },
            )
            proxy.error?.let { SetupError(it) }
            return@Column
        }
        if (!proxy.lan) {
            ReadinessStep(
                text = "The proxy is bound to this machine only. Opening it makes the listener reachable " +
                    "by anything on this network while it runs.",
                action = "Let this network reach it",
                onClick = { onAction(ProxySetupAction.SetLan(true)) },
            )
            return@Column
        }
        if (proxy.lanAddress.isEmpty()) {
            MutedText(
                "Wailo cannot find this Mac's LAN address. Connect both devices to the same network, then press Look again.",
            )
            return@Column
        }
        if (!proxy.caInstalled) {
            ReadinessStep(
                text = "Create the local root before opening setup on the phone. Its private key stays in this Mac's Keychain.",
                action = "Create local root",
                onClick = { onAction(ProxySetupAction.EnsureCertificate) },
            )
            if (proxy.certificateNotice.isNotEmpty()) {
                SetupError(proxy.certificateNotice)
            }
            return@Column
        }

        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "1. Point the phone's Wi-Fi proxy here",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SelectionContainer {
                Text(
                    proxy.address,
                    style = monoSmall(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MutedText(
                "iPhone: Settings → Wi-Fi → the network's info button → Configure Proxy → Manual. " +
                    "Android: edit the current Wi-Fi network → Advanced options → Proxy → Manual.",
            )
        }

        targets.setupQr?.let { qr ->
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Image(
                    bitmap = qr,
                    contentDescription = "Open Wailo device setup",
                    modifier = Modifier.size(152.dp).padding(8.dp),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                )
                MutedText("Scan to open setup on the phone.")
            }
        }

        UnderlineTabs(
            items = listOf(
                TabItem(PhonePlatform.IPhone, "iPhone"),
                TabItem(PhonePlatform.Android, "Android"),
            ),
            selected = platform,
            onSelect = { platform = it },
            modifier = Modifier.fillMaxWidth(),
        )

        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Step(
                2,
                "Scan the QR or open ${targets.setupUrl}, then download the certificate.",
            )
            when (platform) {
                PhonePlatform.IPhone -> {
                    Step(3, "Settings → Profile Downloaded → Install.")
                    Step(
                        4,
                        "Settings → General → About → Certificate Trust Settings, and switch Wailo on. " +
                            "The install alone does nothing — this is the step everyone misses.",
                    )
                }
                PhonePlatform.Android -> {
                    MutedText(
                        "If ADB debugging is enabled, connect the phone to this Mac and use its one-click " +
                            "row under On this machine instead.",
                    )
                    Step(3, "Settings → Security → Encryption & credentials → Install a certificate → CA certificate.")
                    Step(
                        4,
                        "That is the user store, which only debug builds read. A release build keeps " +
                            "refusing unless it opted in — use an emulator above to have Wailo install " +
                            "into the system store instead.",
                    )
                }
            }
            Step(5, "Use Check browser trust on that page; a successful check proves this browser accepts the root.")
            Step(6, "Back here, open Unlock and add the hosts whose HTTPS traffic you need to read.")
        }
    }
}

@Composable
private fun ReadinessStep(text: String, action: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MutedText(text)
        Button(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun SetupError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun Step(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$number.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MutedText(text, Modifier.weight(1f))
    }
}
