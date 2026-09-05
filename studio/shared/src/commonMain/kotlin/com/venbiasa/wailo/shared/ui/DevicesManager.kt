package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.DeviceConnectionStatus
import com.venbiasa.wailo.shared.DeviceInfo
import com.venbiasa.wailo.shared.DeviceTransportKind
import com.venbiasa.wailo.shared.PairingAction
import com.venbiasa.wailo.shared.PairingState
import com.venbiasa.wailo.shared.ProxySetupAction
import com.venbiasa.wailo.shared.ProxyState
import com.venbiasa.wailo.shared.ProxyTargets
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_add
import com.venbiasa.wailo.shared.theme.LocalWailoColors

/**
 * Everything currently streaming, plus everything Studio can see but hasn't reached yet — split from the
 * devices merely *allowed* in, which are their own section ([pairedDevicesSection]). The two answer
 * different questions ("why isn't my phone showing up" vs "who may connect"), and a device can sit in
 * either without the other.
 *
 * SDK-less setup lives here because this is where users look for a device that is not appearing (ADR-0090).
 *
 * [usbPort] is named on each USB row because it is the one setting that can silently mismatch: usbmux has
 * no discovery, so a device listening on another port is indistinguishable from an app that never started.
 * An adb row names [listenPort] for the same reason — it is both ends of the reverse mapping.
 */
@Composable
internal fun DevicesManager(
    devices: List<DeviceInfo>,
    usbSupported: Boolean,
    usbPort: Int,
    adbSupported: Boolean,
    listenPort: Int,
    pairing: PairingState,
    onPairingAction: (PairingAction) -> Unit,
    proxy: ProxyState = ProxyState(),
    proxyTargets: ProxyTargets = ProxyTargets(),
    onProxySetupAction: (ProxySetupAction) -> Unit = {},
    onClose: () -> Unit = {},
) {
    // Transient, panel-scoped: arming "forget all" dies with the panel, so reopening never lands on a
    // primed destructive button.
    var confirmingForgetAll by remember { mutableStateOf(false) }

    var settingUp by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth()
                        .height(TopBarHeight)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Devices", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    CloseButton(onClose, contentDescription = "Close devices")
                }
                RowDivider()

                // Keys are prefixed per section: a live device and a paired one describe the same phone
                // and can carry the same id, which would collide in one lazy list.
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "connected-header") { SectionHeader("Connected") }
                    if (devices.isEmpty()) {
                        item(key = "connected-empty") {
                            SectionEmptyText(emptyDevicesText(usbSupported, usbPort, adbSupported))
                        }
                    } else {
                        items(devices, key = { "connected-${it.id}" }) { device ->
                            DeviceRow(device, usbPort, listenPort)
                            RowDivider()
                        }
                    }
                    item(key = "sdkless-header") {
                        SectionHeader("Without the SDK") {
                            PanelIconButton(
                                icon = Res.drawable.ic_add,
                                contentDescription = "Set up a device without the SDK",
                                onClick = {
                                    settingUp = true
                                    onProxySetupAction(ProxySetupAction.RefreshTargets)
                                },
                                enabled = !settingUp,
                            )
                        }
                    }
                    item(key = "sdkless-summary") {
                        SectionEmptyText(sdklessSummary(proxy))
                    }
                    pairedDevicesSection(
                        pairing = pairing,
                        confirmingForgetAll = confirmingForgetAll,
                        onConfirmingForgetAllChange = { confirmingForgetAll = it },
                        onAction = onPairingAction,
                    )
                }
            }

            // An open pairing offer takes over this panel and nothing else: the scrim is a child of the
            // Devices surface, so the traffic list beside it stays lit and readable while the QR is up
            // (the same scoping the `+ Add filter` modal uses, mirrored to this side). Dismissing cancels
            // the offer rather than just hiding it — a live pairing window nobody can see is worse than none.
            pairing.offer?.let { offer ->
                val cancel = { onPairingAction(PairingAction.Cancel) }
                Scrim(onDismiss = cancel)
                PairingOfferCard(
                    offer = offer,
                    onCancel = cancel,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }

            if (settingUp && pairing.offer == null) {
                Scrim(onDismiss = { settingUp = false })
                ProxySetupCard(
                    proxy = proxy,
                    targets = proxyTargets,
                    onAction = onProxySetupAction,
                    onClose = { settingUp = false },
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun BoxScope.Scrim(onDismiss: () -> Unit) {
    Box(
        Modifier.matchParentSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    )
}

private fun sdklessSummary(proxy: ProxyState): String = when {
    !proxy.running ->
        "A browser, a release build, or someone else's app can be captured through Wailo's proxy instead. " +
            "It is off right now."
    !proxy.caInstalled ->
        "Proxy running on ${proxy.address}. HTTPS is tunnelled without being read until a certificate " +
            "exists and a host is unlocked."
    proxy.decryptHosts.isEmpty() ->
        "Proxy running on ${proxy.address} with a certificate, but no host is unlocked yet, so every " +
            "HTTPS connection stays an encrypted tunnel."
    else -> "Proxy running on ${proxy.address}, reading ${proxy.decryptHosts.size} unlocked host" +
        (if (proxy.decryptHosts.size == 1) "." else "s.")
}

/**
 * Why nothing is listed, in terms of what this machine can actually do about it — a Mac without the
 * Android platform-tools cannot be told to attach a phone by cable, and a Linux host cannot be told to
 * attach an iPhone at all.
 */
private fun emptyDevicesText(usbSupported: Boolean, usbPort: Int, adbSupported: Boolean): String {
    val cable = listOfNotNull(
        "attach an iPhone by USB (Studio dials port $usbPort on it)".takeIf { usbSupported },
        "attach an Android device by USB (Studio forwards it with adb)".takeIf { adbSupported },
    )
    val ways = (cable + "pair one over the local area network").joinToString(", or ")
    val missing = when {
        !usbSupported && !adbSupported -> " iPhone USB is macOS-only, and no adb was found for Android."
        !usbSupported -> " iPhone USB is currently macOS-only."
        !adbSupported -> " No adb was found, so Android devices have to connect over the network."
        else -> ""
    }
    return "No devices connected — $ways.$missing All of those need the app to be built with the Wailo " +
        "SDK; if it isn't, use Without the SDK below."
}

@Composable
private fun DeviceRow(device: DeviceInfo, usbPort: Int, listenPort: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = 5.dp)
                .size(8.dp)
                .background(statusColor(device.status), CircleShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when (device.transport) {
                        DeviceTransportKind.USB -> "USB · $usbPort"
                        DeviceTransportKind.ADB -> "adb · $listenPort"
                        DeviceTransportKind.LAN -> "LAN"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            device.appId?.let {
                Text(it, style = monoSmall(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                statusLabel(device.status),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            device.detail?.let {
                Text(it, style = monoSmall(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            device.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun statusColor(status: DeviceConnectionStatus) = when (status) {
    DeviceConnectionStatus.CONNECTED -> LocalWailoColors.current.success
    DeviceConnectionStatus.ATTACHED,
    DeviceConnectionStatus.CONNECTING,
    -> LocalWailoColors.current.info
    DeviceConnectionStatus.WAITING_FOR_APP,
    DeviceConnectionStatus.UNAUTHORIZED,
    -> LocalWailoColors.current.warning
    DeviceConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
}

private fun statusLabel(status: DeviceConnectionStatus): String = when (status) {
    DeviceConnectionStatus.ATTACHED -> "Attached"
    DeviceConnectionStatus.CONNECTING -> "Connecting…"
    DeviceConnectionStatus.WAITING_FOR_APP -> "Waiting for an app using Wailo"
    DeviceConnectionStatus.UNAUTHORIZED -> "Waiting for permission on the device"
    DeviceConnectionStatus.CONNECTED -> "Connected"
    DeviceConnectionStatus.ERROR -> "Connection failed"
}
