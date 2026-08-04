package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.PairedDeviceInfo
import com.venbiasa.wailo.shared.PairingAction
import com.venbiasa.wailo.shared.PairingState
import com.venbiasa.wailo.shared.theme.LocalWailoColors

/**
 * Pairing, as it appears above the device list (ADR-0039).
 *
 * WiFi is the one transport where the peer is whoever answered an mDNS advertisement, so it is the one
 * that has to be paired; loopback and USB stay zero-config and never appear here. The countdown is not
 * decoration — the typed code is only ~50 bits, and the window closing is most of what protects it.
 */
@Composable
internal fun PairingPanel(
    pairing: PairingState,
    onAction: (PairingAction) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Paired devices", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            if (pairing.offer == null) {
                SmallTextButton("Pair a device", enabled = pairing.supported) { onAction(PairingAction.Begin) }
            } else {
                SmallTextButton("Cancel") { onAction(PairingAction.Cancel) }
            }
        }

        if (!pairing.supported) {
            Spacer(Modifier.height(6.dp))
            MutedText(
                "This machine has no secure key store, so Wailo will not hold pairing keys here. " +
                    "Devices can still connect over USB or from a Simulator.",
            )
        }

        pairing.refusals.forEach { refusal ->
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "A device ${refusal.reason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalWailoColors.current.warning,
                    )
                    Text(refusal.deviceId, style = monoSmall(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SmallTextButton("Dismiss") { onAction(PairingAction.DismissRefusal(refusal.deviceId)) }
            }
        }

        pairing.offer?.let { offer ->
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                offer.qr?.let { qr ->
                    Image(
                        bitmap = qr,
                        contentDescription = "Pairing QR code",
                        modifier = Modifier.size(148.dp)
                            .background(Color.White, RoundedCornerShape(6.dp))
                            .padding(6.dp),
                        // Nearest-neighbour: a QR is a grid of hard edges and smoothing it can cost a scan.
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MutedText("Scan this in the app's Wailo panel.")
                    Text(
                        "or type",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(offer.code, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (offer.remainingSeconds > 0) "Expires in ${offer.remainingSeconds}s" else "Expired",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (offer.remainingSeconds > EXPIRY_WARNING_SECONDS) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            LocalWailoColors.current.warning
                        },
                    )
                }
            }
        }

        if (pairing.devices.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            MutedText(
                if (pairing.requirePairing) {
                    "No devices trusted. Wi-Fi devices have to pair here before they can connect."
                } else {
                    "No devices trusted yet. A device that dials this Mac's address is taken at its " +
                        "word the first time and listed here. Pair instead to prove it up front."
                },
            )
        } else {
            pairing.devices.forEach { device ->
                Spacer(Modifier.height(8.dp))
                PairedDeviceRow(device) { onAction(PairingAction.Forget(device.deviceId)) }
            }
            Spacer(Modifier.height(10.dp))
            SmallTextButton("Forget all") { onAction(PairingAction.ForgetAll) }
        }
    }
}

@Composable
private fun PairedDeviceRow(device: PairedDeviceInfo, onForget: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                device.name.ifBlank { "Unnamed device" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(device.deviceId, style = monoSmall(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (device.trustedOnFirstUse) "Trusted on first contact" else "Paired",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (device.suspectedClone) {
                Text(
                    "This key has been used from somewhere else. Forget it and pair again.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        SmallTextButton("Forget", onClick = onForget)
    }
}

@Composable
private fun SmallTextButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private const val EXPIRY_WARNING_SECONDS = 10
