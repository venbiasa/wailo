package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.venbiasa.wailo.shared.PairingAction
import com.venbiasa.wailo.shared.PairingState
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
import androidx.compose.ui.unit.dp

// A TCP port is at most five digits; capping input there stops a typo becoming an obviously-invalid
// number the host has to reject.
private const val MaxPortDigits = 5

/**
 * The settings panel: the two ports a device can arrive on. Appearance (the theme and the keyboard-only
 * text scale) is a separate piece of work, which is why one lone section still carries a header — it's the
 * shape the next group drops into.
 *
 * Stateless over its inputs, like the other tool panels (ADR-0013): the host owns the engine, the
 * persistence, and all validation. The port fields are the one exception — they hold in-progress text
 * locally and only report on Apply, since every keystroke can't rebind a server. The host decides whether
 * a port is usable (only it can try the bind) and reports back through [portError]/[usbPortError]; this
 * renders that verdict rather than second-guessing it.
 *
 * The USB row only appears where Studio can actually reach a device that way ([usbSupported], macOS
 * today): elsewhere it would be a setting with nothing behind it.
 */
@Composable
internal fun SettingsManager(
    listenPort: Int,
    listenAddress: String,
    listening: Boolean,
    portError: String?,
    onApplyPort: (Int) -> Unit,
    usbSupported: Boolean,
    usbPort: Int,
    usbPortError: String?,
    onApplyUsbPort: (Int) -> Unit,
    pairing: PairingState = PairingState(),
    onPairingAction: (PairingAction) -> Unit = {},
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
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                CloseButton(onClose, contentDescription = "Close settings")
            }
            RowDivider()

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SectionHeader("Connection")
                PortField(
                    label = "Capture server port",
                    port = listenPort,
                    error = portError,
                    // A port that failed to bind is worth retrying unchanged — whatever was squatting on
                    // it may be gone.
                    canReapplyUnchanged = !listening,
                    onApply = onApplyPort,
                    status = "Devices dial $listenAddress",
                    help = "Changing the port restarts the LAN server, so LAN-connected devices drop. " +
                        "Android needs adb reverse re-run on the new port; iOS devices using Bonjour " +
                        "find it on their own.",
                )
                if (usbSupported) {
                    RowDivider()
                    PortField(
                        label = "USB device port",
                        port = usbPort,
                        error = usbPortError,
                        canReapplyUnchanged = false,
                        onApply = onApplyUsbPort,
                        status = "Studio dials usb:$usbPort",
                        help = "Must match the port the iOS SDK listens on. USB has no discovery, so a " +
                            "mismatch looks exactly like an app that isn't running. Applying re-dials " +
                            "attached devices and leaves LAN sessions alone.",
                    )
                }

                if (pairing.supported) {
                    SectionHeader("Wi-Fi security")
                    ToggleRow(
                        label = "Only paired devices over Wi-Fi",
                        checked = pairing.requirePairing,
                        onCheckedChange = { onPairingAction(PairingAction.SetRequirePairing(it)) },
                        help = "Off, a device that dials an address you typed is taken at its word the " +
                            "first time and remembered from then on — the usual case, your own phone " +
                            "and your own Mac. On, a device must scan the QR or type the code from the " +
                            "Devices panel first. Either way the session is encrypted; this decides " +
                            "what has to be proved before it starts. Devices you already trust stay " +
                            "connected.",
                    )
                    RowDivider()
                    IdentityRow(studioId = pairing.studioId, deviceCount = pairing.devices.size) {
                        onPairingAction(PairingAction.ResetIdentity)
                    }
                }
            }
        }
    }
}

// Resetting the identity is rare, irreversible and disconnects everything, so it asks first and says
// exactly what it will cost — a plain button here would be a trap sitting next to a port field.
@Composable
private fun IdentityRow(studioId: String, deviceCount: Int, onReset: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "This Studio's identity",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            studioId.ifEmpty { "unavailable" },
            style = monoSmall(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (confirming) {
            Text(
                "Resetting disconnects and forgets " +
                    when (deviceCount) {
                        0 -> "every paired device"
                        1 -> "the 1 paired device"
                        else -> "all $deviceCount paired devices"
                    } +
                    ". Each has to pair again.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { confirming = false; onReset() }) { Text("Reset identity") }
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        } else {
            Button(onClick = { confirming = true }) { Text("Reset identity…") }
        }
        MutedText(
            "Devices pin this fingerprint the first time they connect, and refuse anything else at " +
                "that address. Reset it if you think the key store leaked — forgetting devices one " +
                "at a time does not help when the leak is on this side.",
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    help: String,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        MutedText(help)
    }
}

// A port field and everything the user needs to judge a change: where devices should point, whether it
// took, and what the change costs them. Committed on Apply or Enter — never per keystroke, which would
// rebind on the way to typing "8899".
@Composable
private fun PortField(
    label: String,
    port: Int,
    error: String?,
    canReapplyUnchanged: Boolean,
    onApply: (Int) -> Unit,
    status: String,
    help: String,
) {
    // Keyed on the applied port so a successful change (or a rollback to the previous port) re-seeds the
    // field, and the user is never left editing a number nothing is on.
    var portText by remember(port) { mutableStateOf(port.toString()) }
    // The host's verdict is about the port that was applied, so it goes stale the moment the user starts
    // typing a different one. Reset by each new verdict, which is what re-arms the message.
    var editedSinceVerdict by remember(error) { mutableStateOf(false) }
    val shownError = error?.takeUnless { editedSinceVerdict }
    val pending = portText.toIntOrNull()?.takeIf { it != port || canReapplyUnchanged }
    val apply: () -> Unit = { pending?.let(onApply) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactOutlinedTextField(
                value = portText,
                onValueChange = { next ->
                    portText = next.filter { it.isDigit() }.take(MaxPortDigits)
                    editedSinceVerdict = true
                },
                modifier = Modifier.widthIn(min = 96.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                            apply()
                            true
                        } else {
                            false
                        }
                    },
                placeholder = "8899",
                isError = shownError != null,
            )
            Button(onClick = apply, enabled = pending != null) { Text("Apply") }
        }
        if (shownError != null) {
            Text(
                shownError,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                status,
                style = monoSmall(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MutedText(help)
    }
}

// The same header band the capture filter's lists use, so the settings groups read as the panel sections
// they are rather than as loose rows.
@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    RowDivider()
}
