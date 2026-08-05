package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.PairedDeviceInfo
import com.venbiasa.wailo.shared.PairingAction
import com.venbiasa.wailo.shared.PairingOfferInfo
import com.venbiasa.wailo.shared.PairingRefusal
import com.venbiasa.wailo.shared.PairingState
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_close
import com.venbiasa.wailo.shared.resources.ic_delete
import com.venbiasa.wailo.shared.resources.ic_link
import com.venbiasa.wailo.shared.theme.LocalWailoColors

/**
 * The trust half of the Devices panel: which devices are allowed in, as opposed to which are here now
 * (ADR-0039).
 *
 * The local area network is the one transport where the peer is whoever answered an mDNS advertisement,
 * so it is the one that has to be paired; loopback and USB stay zero-config and never appear here.
 * Starting a pairing is a section action rather than a row, and its QR/code open as a modal over the
 * panel ([PairingOfferCard]) — the offer is a short-lived thing to act on, not a state of the list.
 *
 * [confirmingForgetAll] is hoisted rather than remembered here because this is a lazy list: state kept
 * inside the header's item would be dropped the moment a long device list scrolled it out of view.
 */
internal fun LazyListScope.pairedDevicesSection(
    pairing: PairingState,
    confirmingForgetAll: Boolean,
    onConfirmingForgetAllChange: (Boolean) -> Unit,
    onAction: (PairingAction) -> Unit,
) {
    item(key = "paired-header") {
        SectionHeader("Paired") {
            // A link, not a QR: the offer has two ways in (scan or type the code), so naming the action
            // after one of them misleads anyone whose camera isn't an option. The glyph describes what
            // the action produces — a trust link to a device — which is true of both routes.
            PanelIconButton(
                icon = Res.drawable.ic_link,
                contentDescription = "Pair a device",
                onClick = { onAction(PairingAction.Begin) },
                // One offer at a time, and the open one already owns the screen as a modal.
                enabled = pairing.supported && pairing.offer == null,
            )
            if (pairing.devices.isNotEmpty()) {
                PanelIconButton(
                    icon = Res.drawable.ic_delete,
                    contentDescription = "Forget all paired devices",
                    onClick = { onConfirmingForgetAllChange(true) },
                )
            }
        }
    }

    // Gated on the list still having devices, so the prompt can't outlive what it was about — forgetting
    // the last one individually leaves nothing to confirm.
    if (confirmingForgetAll && pairing.devices.isNotEmpty()) {
        item(key = "paired-forget-all-confirm") {
            ForgetAllConfirmRow(
                deviceCount = pairing.devices.size,
                onConfirm = {
                    onConfirmingForgetAllChange(false)
                    onAction(PairingAction.ForgetAll)
                },
                onCancel = { onConfirmingForgetAllChange(false) },
            )
            RowDivider()
        }
    }

    if (!pairing.supported) {
        item(key = "paired-unsupported") {
            SectionEmptyText(
                "This machine has no secure key store, so Wailo will not hold pairing keys here. " +
                    "Devices can still connect over USB or from a Simulator.",
            )
        }
        return
    }

    items(pairing.refusals, key = { "refusal-${it.deviceId}" }) { refusal ->
        RefusalRow(refusal) { onAction(PairingAction.DismissRefusal(refusal.deviceId)) }
        RowDivider()
    }

    if (pairing.devices.isEmpty()) {
        item(key = "paired-empty") {
            SectionEmptyText(
                if (pairing.requirePairing) {
                    "No devices paired — one has to pair here before it can connect over the local area network."
                } else {
                    "No devices paired — a device that dials an address you typed is trusted on first " +
                        "contact and listed here."
                },
            )
        }
    } else {
        items(pairing.devices, key = { "paired-${it.deviceId}" }) { device ->
            PairedDeviceRow(device) { onAction(PairingAction.Forget(device.deviceId)) }
            RowDivider()
        }
    }
}

/**
 * An open pairing window, as a card over the Devices panel. Two ways in, because a camera is not always
 * usable: the QR carries a full-strength key, and the code is the fallback. The countdown is not
 * decoration — the typed code is only ~50 bits, and the window closing is most of what protects it.
 */
@Composable
internal fun PairingOfferCard(
    offer: PairingOfferInfo,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 300.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Pair a device",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                CloseButton(onCancel, contentDescription = "Cancel pairing")
            }
            MutedText("Scan this in the app's Wailo panel, or type the code.", Modifier.padding(end = 8.dp))
            // QR over code rather than beside it: the panel this floats in is resizable down to 340.dp,
            // where a two-column layout would squeeze the code to a sliver.
            Column(
                Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                offer.qr?.let { qr ->
                    Image(
                        bitmap = qr,
                        contentDescription = "Pairing QR code",
                        modifier = Modifier.size(180.dp)
                            .background(Color.White, RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        // Nearest-neighbour: a QR is a grid of hard edges and smoothing it can cost a scan.
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                }
                Text(
                    offer.code,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
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
}

// Forgetting every device at once is irreversible and costs every one of them a re-pair, so the header's
// icon only arms it — the same two-step Settings gives Reset, rather than a confirmation dialog, because
// the cost is worth stating in place next to the list it empties.
@Composable
private fun ForgetAllConfirmRow(deviceCount: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (deviceCount == 1) {
                "This disconnects and forgets the 1 paired device. It has to pair again."
            } else {
                "This disconnects and forgets all $deviceCount paired devices. Each has to pair again."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirm) { Text("Forget all") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun PairedDeviceRow(device: PairedDeviceInfo, onForget: () -> Unit) {
    val name = device.name.ifBlank { "Unnamed device" }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
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
        PanelIconButton(
            icon = Res.drawable.ic_delete,
            contentDescription = "Forget $name",
            onClick = onForget,
        )
    }
}

// A device that dialled in and was turned away, on the tinted container that separates a notice from the
// rows around it — so an absent device is not mistaken for a bad network.
@Composable
private fun RefusalRow(refusal: PairingRefusal, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "A device ${refusal.reason}",
                style = MaterialTheme.typography.labelSmall,
                color = LocalWailoColors.current.warning,
            )
            Text(refusal.deviceId, style = monoSmall(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PanelIconButton(
            icon = Res.drawable.ic_close,
            contentDescription = "Dismiss",
            onClick = onDismiss,
        )
    }
}

private const val EXPIRY_WARNING_SECONDS = 10
