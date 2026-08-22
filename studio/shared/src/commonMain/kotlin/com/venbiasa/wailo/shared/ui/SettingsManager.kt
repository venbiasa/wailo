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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.venbiasa.wailo.shared.PairingAction
import com.venbiasa.wailo.shared.PairingState
import com.venbiasa.wailo.shared.ProxyState
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

// Six digits covers the host's retention ceiling — same idea as the port cap above.
private const val MaxRetainedDigits = 6

/**
 * The settings panel: the two ports a device can arrive on, how much captured traffic Studio keeps, what
 * AI tools are allowed to do with it, and the local network trust surface.
 *
 * Stateless over its inputs, like the other tool panels (ADR-0013): the host owns the engine, the
 * persistence, and all validation. The number fields are the one exception — they hold in-progress text
 * locally and only report on Apply, since no keystroke should rebind a server or discard captured traffic.
 * The host decides whether a value is usable (only it can try the bind) and reports back through
 * [portError]/[usbPortError]/[maxRetainedError]; this renders that verdict rather than second-guessing it.
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
    proxy: ProxyState,
    onProxyEnabledChange: (Boolean) -> Unit,
    onApplyProxyPort: (Int) -> Unit,
    maxRetained: Int,
    retainedCount: Int,
    maxRetainedError: String?,
    onApplyMaxRetained: (Int) -> Unit,
    mcpAccess: Boolean = true,
    onMcpAccessChange: (Boolean) -> Unit = {},
    mcpRedactSecrets: Boolean = true,
    onMcpRedactSecretsChange: (Boolean) -> Unit = {},
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
                SectionHeader("Socket")
                NumberField(
                    label = "Socket port",
                    value = listenPort,
                    maxDigits = MaxPortDigits,
                    placeholder = "8899",
                    error = portError,
                    // A port that failed to bind is worth retrying unchanged — whatever was squatting on
                    // it may be gone.
                    canReapplyUnchanged = !listening,
                    onApply = onApplyPort,
                    status = listenAddress,
                    statusPrefix = "Devices dial ",
                    help = "Changing it restarts the server, so connected devices drop. They come back " +
                        "on their own: an attached Android device is re-forwarded with adb, and iOS " +
                        "finds the new port over Bonjour.",
                )
                if (usbSupported) {
                    RowDivider()
                    NumberField(
                        label = "USB device port",
                        value = usbPort,
                        maxDigits = MaxPortDigits,
                        placeholder = "8900",
                        error = usbPortError,
                        canReapplyUnchanged = false,
                        onApply = onApplyUsbPort,
                        status = "usb:$usbPort",
                        statusPrefix = "Studio dials ",
                        help = "Must match the port the iOS SDK listens on. USB has no discovery, so a " +
                            "mismatch looks exactly like an app that isn't running.",
                    )
                }

                SectionHeader("Proxy")
                ToggleRow(
                    label = "Capture clients that have no Wailo SDK",
                    checked = proxy.running,
                    onCheckedChange = onProxyEnabledChange,
                    error = proxy.error,
                    help = "Point a browser, a CLI, or an emulator at ${proxy.address} and its traffic " +
                        "joins the list, ticked in the Proxy column. HTTPS is tunnelled but not " +
                        "decrypted — that needs a certificate Wailo does not have yet.",
                )
                RowDivider()
                NumberField(
                    label = "Proxy port",
                    value = proxy.port,
                    maxDigits = MaxPortDigits,
                    placeholder = "9090",
                    // The bind verdict belongs to the switch above, which is what asked for it; repeating
                    // it here would show the same failure twice.
                    error = null,
                    canReapplyUnchanged = false,
                    onApply = onApplyProxyPort,
                    status = proxy.address,
                    statusPrefix = "Clients point at ",
                    help = "Changing it while the proxy runs restarts the listener, so anything already " +
                        "pointed at the old port loses its network until you move it too.",
                )

                SectionHeader("Capture")
                NumberField(
                    label = "Requests kept in memory",
                    value = maxRetained,
                    maxDigits = MaxRetainedDigits,
                    placeholder = "10000",
                    error = maxRetainedError,
                    canReapplyUnchanged = false,
                    onApply = onApplyMaxRetained,
                    status = "Holding $retainedCount of $maxRetained",
                    help = "A bigger number keeps more history and costs more memory, since every kept " +
                        "request holds its body. Lowering it drops the oldest right away — that traffic " +
                        "is gone, not hidden.",
                )

                SectionHeader("AI tool access")
                ToggleRow(
                    label = "Let AI tools read and control this capture",
                    checked = mcpAccess,
                    onCheckedChange = onMcpAccessChange,
                    help = "Off, an agent's Wailo tool calls are all refused until you turn this back " +
                        "on. Capture keeps running either way, and nothing changes for Studio or the CLI.",
                )
                if (mcpAccess) {
                    RowDivider()
                    ToggleRow(
                        label = "Hide credentials from AI tools",
                        checked = mcpRedactSecrets,
                        onCheckedChange = onMcpRedactSecretsChange,
                        help = "On, authorization headers, cookies, and secret-looking fields read back " +
                            "as placeholders. Off, a live token reaches the agent and its provider's logs.",
                    )
                }

                if (pairing.supported) {
                    SectionHeader("Local area network security")
                    ToggleRow(
                        label = "Allow only paired devices over local area network",
                        checked = pairing.requirePairing,
                        onCheckedChange = { onPairingAction(PairingAction.SetRequirePairing(it)) },
                        help = "On, a device must scan the QR or type the code from Devices first. Off, " +
                            "one that dials an address you typed is trusted on first contact. Sessions " +
                            "are encrypted either way, and devices you already trust stay connected.",
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
        SelectionContainer(Modifier.fillMaxWidth()) {
            Text(
                studioId.ifEmpty { "unavailable" },
                style = monoSmall(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                Button(onClick = { confirming = false; onReset() }) { Text("Reset") }
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        } else {
            Button(onClick = { confirming = true }) { Text("Reset") }
        }
        MutedText(
            "Devices pin this fingerprint and refuse anything else at this address. Reset it if the " +
                "key store leaked — forgetting devices one at a time won't help.",
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    help: String,
    // Only for a flip that something outside this process can refuse — a listener that could not bind.
    // Without it such a switch springs back with no explanation and reads as a broken control.
    error: String? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The label takes the weight rather than a trailing spacer: the panel is user-resizable down
            // to 340.dp, and a label that can't wrap would otherwise push the switch out of the panel.
            Text(
                label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CompactSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        MutedText(help)
    }
}

// A number and everything the user needs to judge changing it: what it means right now, whether the last
// change took, and what the change costs them. Committed on Apply or Enter — never per keystroke, since
// every setting behind one of these acts immediately, and typing "8899" would rebind four times on the way.
@Composable
private fun NumberField(
    label: String,
    value: Int,
    maxDigits: Int,
    placeholder: String,
    error: String?,
    canReapplyUnchanged: Boolean,
    onApply: (Int) -> Unit,
    status: String,
    help: String,
    statusPrefix: String? = null,
) {
    // Keyed on the applied value so a successful change (or a rollback to the previous one) re-seeds the
    // field, and the user is never left editing a number nothing is on.
    var valueText by remember(value) { mutableStateOf(value.toString()) }
    // The host's verdict is about the value that was applied, so it goes stale the moment the user starts
    // typing a different one. Reset by each new verdict, which is what re-arms the message.
    var editedSinceVerdict by remember(error) { mutableStateOf(false) }
    val shownError = error?.takeUnless { editedSinceVerdict }
    val pending = valueText.toIntOrNull()?.takeIf { it != value || canReapplyUnchanged }
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
                value = valueText,
                onValueChange = { next ->
                    valueText = next.filter { it.isDigit() }.take(maxDigits)
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
                placeholder = placeholder,
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
            if (statusPrefix == null) {
                Text(
                    status,
                    style = monoSmall(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        statusPrefix,
                        style = monoSmall(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            status,
                            style = monoSmall(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        MutedText(help)
    }
}
