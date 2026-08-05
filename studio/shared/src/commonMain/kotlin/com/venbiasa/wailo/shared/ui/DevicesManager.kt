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
import com.venbiasa.wailo.shared.theme.LocalWailoColors

/**
 * Everything currently streaming, plus everything Studio can see but hasn't reached yet — split from the
 * devices merely *allowed* in, which are their own section ([pairedDevicesSection]). The two answer
 * different questions ("why isn't my phone showing up" vs "who may connect"), and a device can sit in
 * either without the other.
 *
 * [usbPort] is named on each USB row because it is the one setting that can silently mismatch: usbmux has
 * no discovery, so a device listening on another port is indistinguishable from an app that never started.
 */
@Composable
internal fun DevicesManager(
    devices: List<DeviceInfo>,
    usbSupported: Boolean,
    usbPort: Int,
    pairing: PairingState,
    onPairingAction: (PairingAction) -> Unit,
    onClose: () -> Unit = {},
) {
    // Transient, panel-scoped: arming "forget all" dies with the panel, so reopening never lands on a
    // primed destructive button.
    var confirmingForgetAll by remember { mutableStateOf(false) }

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
                            SectionEmptyText(
                                if (usbSupported) {
                                    "No devices connected — attach an iPhone by USB (Studio dials port " +
                                        "$usbPort on it) or pair one over the local area network."
                                } else {
                                    "No devices connected — pair one over the local area network. USB " +
                                        "discovery is currently macOS-only."
                                },
                            )
                        }
                    } else {
                        items(devices, key = { "connected-${it.id}" }) { device ->
                            DeviceRow(device, usbPort)
                            RowDivider()
                        }
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
                Box(
                    Modifier.matchParentSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = cancel,
                        ),
                )
                PairingOfferCard(
                    offer = offer,
                    onCancel = cancel,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceInfo, usbPort: Int) {
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
                    if (device.transport == DeviceTransportKind.USB) "USB · $usbPort" else "LAN",
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
    DeviceConnectionStatus.WAITING_FOR_APP -> LocalWailoColors.current.warning
    DeviceConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
}

private fun statusLabel(status: DeviceConnectionStatus): String = when (status) {
    DeviceConnectionStatus.ATTACHED -> "Attached"
    DeviceConnectionStatus.CONNECTING -> "Connecting…"
    DeviceConnectionStatus.WAITING_FOR_APP -> "Waiting for an app using Wailo"
    DeviceConnectionStatus.CONNECTED -> "Connected"
    DeviceConnectionStatus.ERROR -> "Connection failed"
}
