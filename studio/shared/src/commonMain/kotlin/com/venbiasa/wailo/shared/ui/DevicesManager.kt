package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
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
 * Everything currently streaming, plus everything Studio can see but hasn't reached yet.
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    PairingPanel(pairing, onPairingAction)
                    RowDivider()
                }
                if (devices.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "No devices",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(6.dp))
                            MutedText(
                                if (usbSupported) {
                                    "Connect an iPhone by USB and open an app using Wailo, or pair it over " +
                                        "Wi-Fi. USB dials port $usbPort on the device."
                                } else {
                                    "Pair an app using Wailo over Wi-Fi. USB discovery is currently macOS-only."
                                },
                            )
                        }
                    }
                } else {
                    items(devices, key = { it.id }) { device ->
                        DeviceRow(device, usbPort)
                        RowDivider()
                    }
                }
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
