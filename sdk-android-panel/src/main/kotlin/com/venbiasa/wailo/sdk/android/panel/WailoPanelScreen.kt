package com.venbiasa.wailo.sdk.android.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.sdk.android.Wailo
import com.venbiasa.wailo.sdk.android.WailoClient
import com.venbiasa.wailo.sdk.android.WailoDesktop
import com.venbiasa.wailo.sdk.android.WailoPairingCode

/**
 * The on-device panel: where the SDK is pointed, and how this device pairs with a Studio over Wi-Fi.
 *
 * Public so a host can drop it into its own debug menu instead of using the launcher shortcut. Pass
 * [onClose] only when there is somewhere to go back to; [WailoPanelActivity] finishes itself with it.
 *
 * Hand-built out of `foundation` rather than Material: the panel is injected into someone else's app, so
 * inheriting its `MaterialTheme` would repaint Wailo in the host's brand, and a host on Material 2 — or
 * with no Compose theme at all — would leave it unstyled. Everything here paints from [WailoTokens].
 */
@Composable
fun WailoPanelScreen(modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    WailoPanelScreenContent(modifier, onClose, applyWindowInsets = false)
}

@Composable
internal fun WailoPanelActivityScreen(onClose: () -> Unit) {
    WailoPanelScreenContent(Modifier, onClose, applyWindowInsets = true)
}

@Composable
private fun WailoPanelScreenContent(
    modifier: Modifier,
    onClose: (() -> Unit)?,
    applyWindowInsets: Boolean,
) {
    val model = remember { WailoPanelModel() }
    LaunchedEffect(Unit) { Wailo.status.collect(model::onStatus) }
    WailoPanelTheme {
        Column(modifier.fillMaxSize().background(colors.background)) {
            TopBar(onClose, applyWindowInsets)
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .then(
                        if (applyWindowInsets) {
                            Modifier.navigationBarsPadding().imePadding()
                        } else {
                            Modifier
                        },
                    )
                    .padding(Spacing.x4),
                verticalArrangement = Arrangement.spacedBy(Spacing.x5),
            ) {
                StatusSection(model)
                ConnectSection(model)
                PairingSection(model)
                Spacer(Modifier.height(Spacing.x6))
            }
        }
    }
}

@Composable
private fun TopBar(onClose: (() -> Unit)?, applyWindowInsets: Boolean) {
    Row(
        Modifier.fillMaxWidth()
            .background(colors.surfaceContainer)
            .then(if (applyWindowInsets) Modifier.statusBarsPadding() else Modifier)
            .padding(Spacing.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Wailo", Type.titleMedium, colors.onSurface, Modifier.weight(1f))
        if (onClose != null) TextButton("Done", onClose)
    }
}

// MARK: - status

@Composable
private fun StatusSection(model: WailoPanelModel) = Section("Status") {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(Spacing.x4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tone = when {
                !model.isStarted -> colors.onSurfaceDisabled
                model.status.connected -> colors.success
                else -> colors.warning
            }
            Box(Modifier.size(Spacing.x2).background(tone, CircleShape))
            Spacer(Modifier.width(Spacing.x2))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.x1),
            ) {
                Text(model.statusTitle, Type.titleSmall, colors.onSurface)
                model.status.activeAddress?.let { Text(it, Type.monoMedium, colors.onSurfaceVariant) }
            }
            model.transportLabel?.let { Chip(it) }
            if (model.isPairedConnection) {
                Spacer(Modifier.width(Spacing.x1))
                Chip("Paired", colors.success)
            }
        }
        Divider()
        Text(
            model.statusDetail,
            Type.bodySmall,
            colors.onSurfaceVariant,
            Modifier.padding(Spacing.x4),
        )
    }
}

// MARK: - connect

@Composable
private fun ConnectSection(model: WailoPanelModel) = Section("Connect") {
    Column {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.x4),
            verticalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            model.status.refusal?.let { RefusalNotice(model, it) }
            model.status.identityChange?.let {
                IdentityChangeNotice(model, it.host, it.expected, it.actual)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
                verticalAlignment = Alignment.Top,
            ) {
                Field(
                    label = "Desktop address",
                    value = model.host,
                    onValueChange = model::editHost,
                    placeholder = "192.168.1.20",
                    isInvalid = model.hostError != null,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                Field(
                    label = "Port",
                    value = model.port,
                    onValueChange = model::editPort,
                    placeholder = WailoClient.DEFAULT_PORT.toString(),
                    isInvalid = model.portError != null,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.width(84.dp),
                )
            }
            (model.hostError ?: model.portError)?.let { Text(it, Type.bodySmall, colors.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                FilledButton(
                    text = "Connect",
                    enabled = model.canApply,
                    modifier = Modifier.weight(1f),
                    onClick = model::apply,
                )
                OutlinedButton(
                    text = "Use discovery",
                    enabled = model.canUseDiscovery,
                    modifier = Modifier.weight(1f),
                    onClick = model::useDiscovery,
                )
            }
            when (val attempt = model.attempt) {
                is ConnectAttempt.Dialling ->
                    Text("Dialling ${attempt.target}…", Type.bodySmall, colors.onSurfaceVariant)

                is ConnectAttempt.Connected ->
                    Text("Connected to ${attempt.target}.", Type.bodySmall, colors.success)

                is ConnectAttempt.Stopped ->
                    Text(
                        "Not connected to ${attempt.target}. Check Studio is running and reachable.",
                        Type.bodySmall,
                        colors.warning,
                    )

                ConnectAttempt.Idle -> Unit
            }
            Text(
                "Pinning an address turns discovery off. Clear it to hand discovery back control.",
                Type.bodySmall,
                colors.onSurfaceVariant,
            )
        }
        Divider()
        DesktopList(model)
    }
}

@Composable
private fun DesktopList(model: WailoPanelModel) {
    val desktops = model.desktops
    Column {
        Text(
            "DESKTOPS",
            Type.labelSmall,
            colors.onSurfaceVariant,
            Modifier.padding(horizontal = Spacing.x4).padding(top = Spacing.x4),
        )
        if (desktops.isEmpty()) {
            Text(
                "Nothing yet. Open Wailo Studio on the same Wi-Fi, or connect through adb reverse.",
                Type.bodySmall,
                colors.onSurfaceVariant,
                Modifier.padding(Spacing.x4),
            )
        } else {
            for ((index, desktop) in desktops.withIndex()) {
                if (index > 0) Divider()
                DesktopRow(
                    desktop,
                    onFill = { model.fill(desktop) },
                    onForget = { model.forget(desktop) },
                )
            }
            Divider()
            Text(
                "Tapping one fills the address above — connecting is still up to you. Forget removes " +
                    "its key and stops automatic reconnection.",
                Type.bodySmall,
                colors.onSurfaceVariant,
                Modifier.padding(Spacing.x4),
            )
        }
    }
}

@Composable
private fun DesktopRow(desktop: PanelDesktop, onFill: () -> Unit, onForget: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(Spacing.x4),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            Modifier.weight(1f).clickable(enabled = desktop.canFill, onClick = onFill),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(Spacing.x2)
                    .background(if (desktop.online) colors.success else colors.onSurfaceDisabled, CircleShape),
            )
            Spacer(Modifier.width(Spacing.x2))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.x1),
            ) {
                Text(desktop.name, Type.bodyMedium, if (desktop.online) colors.onSurface else colors.onSurfaceVariant)
                desktop.subtitle?.let { Text(it, Type.monoMedium, colors.onSurfaceVariant) }
                desktop.fingerprint?.let { Text(it, Type.monoMedium, colors.onSurfaceVariant, maxLines = 1) }
                desktop.trustLabel?.let { Text(it, Type.labelSmall, colors.onSurfaceVariant) }
                desktop.warning?.let { Text(it, Type.labelSmall, colors.warning) }
            }
            if (desktop.canFill) Text("Fill", Type.labelSmall, colors.accent)
        }
        if (desktop.isRemembered) TextButton("Forget", onForget, colors.error)
    }
}

// MARK: - pairing (ADR-0039)

@Composable
private fun PairingSection(model: WailoPanelModel) = Section("Wi-Fi pairing") {
    Column(
        Modifier.fillMaxWidth().padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        val scan = rememberQrScanner(model)
        FilledButton(
            text = "Scan pairing QR",
            enabled = !model.isScanning,
            modifier = Modifier.fillMaxWidth(),
            onClick = scan,
        )
        Text("or type the code Studio is showing", Type.labelSmall, colors.onSurfaceVariant)
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
            verticalAlignment = Alignment.Bottom,
        ) {
            Field(
                label = "Pairing code",
                value = model.pairingCode,
                onValueChange = model::editPairingCode,
                placeholder = "ABCDE FGHJK",
                isInvalid = false,
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                style = Type.monoMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                text = "Pair",
                enabled = model.canPairWithCode,
                modifier = Modifier.width(96.dp),
                onClick = model::pairWithCode,
            )
        }
        val targets = model.pairableDesktops
        if (targets.isEmpty()) {
            Text(
                "No desktop on this network is advertising a fingerprint yet, so there is nothing to " +
                    "aim a code at. Scanning the QR works without one.",
                Type.bodySmall,
                colors.onSurfaceDisabled,
            )
        } else {
            Text("Showing on", Type.labelSmall, colors.onSurfaceVariant)
            for (target in targets) TargetRow(target, target.studioId == model.codeTarget?.studioId) {
                model.selectCodeTarget(target)
            }
        }
        model.pairingError?.let { Text(it, Type.bodySmall, colors.error) }
        Text(
            "${WailoPairingCode.LENGTH} characters; expires after two minutes.",
            Type.labelSmall,
            colors.onSurfaceDisabled,
        )
        Text(
            "Pairing is only needed over Wi-Fi when Studio accepts only paired devices. ADB reaches " +
                "Studio through this machine, so it connects without pairing.",
            Type.bodySmall,
            colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun TargetRow(target: WailoDesktop, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(12.dp)
                .background(if (selected) colors.accent else Color.Transparent, CircleShape)
                .border(1.dp, if (selected) colors.accent else colors.outline, CircleShape),
        )
        Spacer(Modifier.width(Spacing.x2))
        Column(Modifier.weight(1f)) {
            Text(target.name, Type.bodySmall, colors.onSurface)
            Text(target.address, Type.labelSmall, colors.onSurfaceVariant)
        }
    }
}

// MARK: - notices

@Composable
private fun RefusalNotice(model: WailoPanelModel, message: String) {
    Column(
        Modifier.fillMaxWidth()
            .background(colors.surfaceVariant, RoundedCornerShape(Radius.md))
            .padding(Spacing.x3),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Text(message, Type.bodySmall, colors.warning)
        OutlinedButton("Try again", onClick = model::retryAfterRefusal)
    }
}

@Composable
private fun IdentityChangeNotice(
    model: WailoPanelModel,
    host: String,
    expected: String,
    actual: String,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(colors.surfaceVariant, RoundedCornerShape(Radius.md))
            .padding(Spacing.x3),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Text("A different desktop is answering at $host.", Type.bodySmall, colors.error)
        Text("Trusted", Type.labelSmall, colors.onSurfaceVariant)
        Text(expected, Type.monoMedium, colors.onSurface, maxLines = 1)
        Text("Answering now", Type.labelSmall, colors.onSurfaceVariant)
        Text(actual, Type.monoMedium, colors.onSurface, maxLines = 1)
        Text(
            "Accept only if you expected this desktop to change — otherwise someone may be sitting " +
                "between you and it.",
            Type.bodySmall,
            colors.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            OutlinedButton(
                text = "Keep old",
                modifier = Modifier.weight(1f),
                onClick = model::rejectIdentityChange,
            )
            FilledButton(
                text = "Accept new",
                modifier = Modifier.weight(1f),
                onClick = model::acceptIdentityChange,
            )
        }
    }
}

// MARK: - primitives

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Text(
            title.uppercase(),
            Type.labelSmall,
            colors.onSurfaceVariant,
            Modifier.padding(start = Spacing.x1),
        )
        Column(
            Modifier.fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(Radius.lg))
                .border(1.dp, colors.outlineVariant, RoundedCornerShape(Radius.lg)),
        ) {
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isInvalid: Boolean,
    keyboard: KeyboardOptions,
    style: TextStyle = Type.bodyMedium,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
        Text(label, Type.labelSmall, colors.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = style.copy(color = colors.onSurface),
            singleLine = true,
            keyboardOptions = keyboard,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { field ->
                Box(
                    Modifier.fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(Radius.md))
                        .border(
                            1.dp,
                            if (isInvalid) colors.error else colors.outline,
                            RoundedCornerShape(Radius.md),
                        )
                        .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
                ) {
                    if (value.isEmpty()) Text(placeholder, style, colors.onSurfaceDisabled)
                    field()
                }
            },
        )
    }
}

@Composable
private fun FilledButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.background(if (enabled) colors.accent else colors.surfaceVariant, RoundedCornerShape(Radius.md))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, Type.labelLarge, if (enabled) colors.onAccent else colors.onSurfaceDisabled)
    }
}

@Composable
private fun OutlinedButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.border(1.dp, colors.outline, RoundedCornerShape(Radius.md))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, Type.labelLarge, if (enabled) colors.onSurface else colors.onSurfaceDisabled)
    }
}

@Composable
private fun TextButton(text: String, onClick: () -> Unit, tint: Color = colors.accent) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = Spacing.x2, vertical = Spacing.x1)) {
        Text(text, Type.labelLarge, tint)
    }
}

@Composable
private fun Chip(text: String, tone: Color? = null) {
    Box(
        Modifier.background(colors.surfaceVariant, RoundedCornerShape(Radius.sm))
            .then(
                if (tone == null) {
                    Modifier
                } else {
                    Modifier.border(1.dp, tone, RoundedCornerShape(Radius.sm))
                },
            )
            .padding(horizontal = Spacing.x2, vertical = 2.dp),
    ) {
        Text(text, Type.labelSmall, tone ?: colors.onSurfaceVariant)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
}

@Composable
private fun Text(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier.widthIn(min = 0.dp),
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
