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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
    val model = remember { WailoPanelModel() }
    LaunchedEffect(Unit) { Wailo.status.collect(model::onStatus) }
    WailoPanelTheme {
        Column(modifier.fillMaxSize().background(colors.background)) {
            TopBar(onClose)
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(Spacing.x4),
                verticalArrangement = Arrangement.spacedBy(Spacing.x4),
            ) {
                StatusSection(model)
                model.status.identityChange?.let { IdentityChangeSection(model, it.host, it.expected, it.actual) }
                model.status.refusal?.let { RefusalSection(model, it) }
                ConnectSection(model)
                PairingSection(model)
                Spacer(Modifier.height(Spacing.x6))
            }
        }
    }
}

@Composable
private fun TopBar(onClose: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(Spacing.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Wailo", Type.titleMedium, colors.onSurface, Modifier.weight(1f))
        if (onClose != null) TextButton("Done", onClose)
    }
}

// MARK: - status

@Composable
private fun StatusSection(model: WailoPanelModel) = Section("Status") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val tone = when {
            !model.isStarted -> colors.onSurfaceDisabled
            model.status.connected -> colors.success
            else -> colors.warning
        }
        Box(Modifier.size(Spacing.x2).background(tone, CircleShape))
        Spacer(Modifier.width(Spacing.x2))
        Text(model.statusTitle, Type.titleSmall, colors.onSurface)
        model.transportLabel?.let {
            Spacer(Modifier.width(Spacing.x2))
            Badge(it, colors.info)
        }
        // "Connected" over Wi-Fi says nothing about whether the session is authenticated; a paired one
        // is the only kind that proves the desktop on the other end is the one this device trusts.
        if (model.isPairedConnection) {
            Spacer(Modifier.width(Spacing.x1))
            Badge("Paired", colors.success)
        }
    }
    Text(model.statusDetail, Type.bodySmall, colors.onSurfaceVariant)
    model.status.activeAddress?.let { Text(it, Type.monoMedium, colors.onSurfaceVariant) }
}

// MARK: - connect

@Composable
private fun ConnectSection(model: WailoPanelModel) = Section("Connect") {
    Field(
        label = "Address",
        value = model.host,
        onValueChange = model::editHost,
        placeholder = "10.0.0.2 or my-mac.local",
        error = model.hostError,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
    )
    Field(
        label = "Port",
        value = model.port,
        onValueChange = model::editPort,
        placeholder = "9099",
        error = model.portError,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        FilledButton("Connect", model.canApply, model::apply)
        if (!model.isUsingDiscovery) OutlinedButton("Use discovery", model::useDiscovery)
    }
    when (val attempt = model.attempt) {
        is ConnectAttempt.Dialling -> Text("Dialling ${attempt.target}…", Type.bodySmall, colors.onSurfaceVariant)
        is ConnectAttempt.Connected -> Text("Connected to ${attempt.target}.", Type.bodySmall, colors.success)
        is ConnectAttempt.Stopped ->
            Text("Not talking to ${attempt.target}.", Type.bodySmall, colors.onSurfaceVariant)

        ConnectAttempt.Idle -> Unit
    }

    val desktops = model.desktops
    if (desktops.isEmpty()) {
        Text(
            "No desktops found yet. Open Wailo Studio on the same Wi-Fi, or plug in and run adb reverse.",
            Type.bodySmall,
            colors.onSurfaceDisabled,
        )
    } else {
        Divider()
        for (desktop in desktops) {
            DesktopRow(desktop, onFill = { model.fill(desktop) }, onForget = { model.forget(desktop) })
        }
    }
}

@Composable
private fun DesktopRow(desktop: PanelDesktop, onFill: () -> Unit, onForget: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(Spacing.x2)
                    .background(if (desktop.online) colors.success else colors.onSurfaceDisabled, CircleShape),
            )
            Spacer(Modifier.width(Spacing.x2))
            Text(desktop.name, Type.labelLarge, colors.onSurface, Modifier.weight(1f))
            if (desktop.canFill) TextButton("Fill", onFill)
            if (desktop.isRemembered) TextButton("Forget", onForget, colors.error)
        }
        desktop.subtitle?.let { Text(it, Type.monoMedium, colors.onSurfaceVariant) }
        desktop.trustLabel?.let { Text(it, Type.labelSmall, colors.onSurfaceVariant) }
        // The fingerprint is the only thing that can be checked against what Studio prints in its own
        // Settings when two machines on the network look alike (ADR-0040).
        desktop.fingerprint?.let { Text(it, Type.labelSmall, colors.onSurfaceDisabled, maxLines = 1) }
        desktop.warning?.let { Text(it, Type.labelSmall, colors.error) }
    }
}

// MARK: - pairing (ADR-0039)

@Composable
private fun PairingSection(model: WailoPanelModel) = Section("Pair over Wi-Fi") {
    Text(
        "A desktop on the network only accepts this device once they share a key. Scan the QR in " +
            "Studio's Settings, or type the code it shows. Over the cable this is skipped.",
        Type.bodySmall,
        colors.onSurfaceVariant,
    )
    val scan = rememberQrScanner(model)
    FilledButton("Scan QR", enabled = !model.isScanning, onClick = scan)

    Divider()
    Text("Or type the code", Type.labelSmall, colors.onSurfaceVariant)
    Field(
        label = "Code",
        value = model.pairingCode,
        onValueChange = model::editPairingCode,
        placeholder = "0123456789",
        error = null,
        keyboard = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        style = Type.monoMedium,
    )
    val targets = model.pairableDesktops
    if (targets.isEmpty()) {
        Text(
            "No desktop on this network is advertising a fingerprint yet, so there is nothing to aim " +
                "a code at. Scanning the QR works without one.",
            Type.bodySmall,
            colors.onSurfaceDisabled,
        )
    } else {
        Text("Showing on", Type.labelSmall, colors.onSurfaceVariant)
        for (target in targets) TargetRow(target, target.studioId == model.codeTarget?.studioId) {
            model.selectCodeTarget(target)
        }
    }
    FilledButton("Pair", model.canPairWithCode, model::pairWithCode)
    model.pairingError?.let { Text(it, Type.bodySmall, colors.error) }
    Text("${WailoPairingCode.LENGTH} characters, from Studio's Settings. It expires after two minutes.", Type.labelSmall, colors.onSurfaceDisabled)
}

@Composable
private fun TargetRow(target: WailoDesktop, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .border(1.dp, if (selected) colors.accent else colors.outlineVariant, RoundedCornerShape(Radius.md))
            .clickable(onClick = onSelect)
            .padding(Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(Spacing.x3)
                .background(if (selected) colors.accent else Color.Transparent, CircleShape)
                .border(1.dp, if (selected) colors.accent else colors.outline, CircleShape),
        )
        Spacer(Modifier.width(Spacing.x3))
        Column(Modifier.weight(1f)) {
            Text(target.name, Type.labelLarge, colors.onSurface)
            Text(target.address, Type.labelSmall, colors.onSurfaceVariant)
        }
    }
}

// MARK: - notices

@Composable
private fun RefusalSection(model: WailoPanelModel, message: String) = Section("Not recognised", colors.warning) {
    Text(message, Type.bodySmall, colors.onSurfaceVariant)
    Text(
        "Studio does not hold a key for this device any more. Pair again below, then try once more.",
        Type.bodySmall,
        colors.onSurfaceVariant,
    )
    FilledButton("Try again", onClick = model::retryAfterRefusal)
}

@Composable
private fun IdentityChangeSection(
    model: WailoPanelModel,
    host: String,
    expected: String,
    actual: String,
) = Section("Different desktop at $host", colors.error) {
    // Deliberately not decided for the user: from the device's side a Mac that changed hands and someone
    // standing in the path are the same event, and only a human knows which one this is (ADR-0040).
    Text(
        "This address answered with a different identity than the one this device trusted. That is " +
            "either a desktop that was reinstalled, or something else answering in its place.",
        Type.bodySmall,
        colors.onSurfaceVariant,
    )
    Text("Trusted", Type.labelSmall, colors.onSurfaceVariant)
    Text(expected, Type.monoMedium, colors.onSurface, maxLines = 1)
    Text("Answering now", Type.labelSmall, colors.onSurfaceVariant)
    Text(actual, Type.monoMedium, colors.onSurface, maxLines = 1)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        OutlinedButton("Keep trusting the old one", model::rejectIdentityChange)
        FilledButton("Accept", onClick = model::acceptIdentityChange)
    }
}

// MARK: - primitives

@Composable
private fun Section(title: String, accent: Color? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(Radius.lg))
            .border(1.dp, accent ?: colors.outlineVariant, RoundedCornerShape(Radius.lg))
            .padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Text(title.uppercase(), Type.labelSmall, accent ?: colors.onSurfaceVariant)
        content()
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    error: String?,
    keyboard: KeyboardOptions,
    style: TextStyle = Type.bodyMedium,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
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
                        .border(1.dp, if (error == null) colors.outline else colors.error, RoundedCornerShape(Radius.md))
                        .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
                ) {
                    if (value.isEmpty()) Text(placeholder, style, colors.onSurfaceDisabled)
                    field()
                }
            },
        )
        error?.let { Text(it, Type.labelSmall, colors.error) }
    }
}

@Composable
private fun FilledButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.background(if (enabled) colors.accent else colors.surfaceVariant, RoundedCornerShape(Radius.md))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
    ) {
        Text(text, Type.labelLarge, if (enabled) colors.onAccent else colors.onSurfaceDisabled)
    }
}

@Composable
private fun OutlinedButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.border(1.dp, colors.outline, RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
    ) {
        Text(text, Type.labelLarge, colors.onSurface)
    }
}

@Composable
private fun TextButton(text: String, onClick: () -> Unit, tint: Color = colors.accent) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = Spacing.x2, vertical = Spacing.x1)) {
        Text(text, Type.labelLarge, tint)
    }
}

@Composable
private fun Badge(text: String, tone: Color) {
    Box(
        Modifier.border(1.dp, tone, RoundedCornerShape(Radius.sm))
            .padding(horizontal = Spacing.x2, vertical = 2.dp),
    ) {
        Text(text, Type.labelSmall, tone)
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
