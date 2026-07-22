package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_close
import org.jetbrains.compose.resources.vectorResource

/**
 * The traffic table's columns, in display order, all left-aligned. [defaultWidth] is a compile-time
 * starting size chosen for each column's expected content; the user can then drag columns wider or
 * narrower down to [minWidth] ([rememberColumnWidths]). The header and every row read the same width
 * map, so they stay aligned.
 */
internal enum class TrafficColumn(
    val title: String,
    val defaultWidth: Dp,
    val minWidth: Dp,
) {
    Method("Method", 84.dp, 56.dp),
    Url("URL", 340.dp, 120.dp),
    Status("Status", 128.dp, 84.dp),
    Code("Code", 64.dp, 56.dp),
    Client("Client", 190.dp, 96.dp),
    Timestamp("Timestamp", 124.dp, 96.dp),
    Duration("Duration", 100.dp, 72.dp),
    Request("Request", 96.dp, 72.dp),
    Response("Response", 104.dp, 76.dp),
    Edited("Edited", 76.dp, 60.dp),
}

/** Live per-column widths, seeded from [TrafficColumn.defaultWidth] and mutated in place as the user drags. */
@Composable
internal fun rememberColumnWidths(): SnapshotStateMap<TrafficColumn, Dp> = remember {
    mutableStateMapOf<TrafficColumn, Dp>().apply {
        TrafficColumn.entries.forEach { put(it, it.defaultWidth) }
    }
}

@Composable
internal fun RowDivider(color: Color = MaterialTheme.colorScheme.outlineVariant) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
internal fun ColumnDivider(color: Color = MaterialTheme.colorScheme.outlineVariant) {
    Box(Modifier.fillMaxHeight().width(1.dp).background(color))
}

// Monospace styles keep the numeric columns and payloads tabular; chrome/text stays Noto Sans.
@Composable
internal fun monoSmall(): TextStyle =
    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

@Composable
internal fun monoLabel(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)

// A monospaced key/value line: fixed-width key gutter, value takes the rest. Shared by the detail
// panel's header/auth views and the form-body previewer.
@Composable
internal fun KeyValueRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, Modifier.width(200.dp), style = monoLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = monoSmall(), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// The panels' shared close affordance: an icon button whose circular hover/press state matches the top
// bar's pause/clear buttons, so every dismiss control in the app reads the same way (not a bare glyph).
@Composable
internal fun CloseButton(onClose: () -> Unit, contentDescription: String = "Close") {
    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
        Icon(
            vectorResource(Res.drawable.ic_close),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

// Material's OutlinedTextField renders 16.sp body text in a 56.dp-min box with 16.dp vertical padding —
// oversized for the studio's dense tool-panel forms. This keeps the stock outline and focus animation
// (the DecorationBox's default container) but drives it with 14.sp text and trimmed padding, so the field
// hugs its single line and still grows with the user's text scale (no fixed dp height to clip at 1.8x).
@Composable
internal fun CompactOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        // Input text color rides on BasicTextField (the DecorationBox colors don't reach it); the outline,
        // placeholder, and focus tint still come from the standard OutlinedTextField colors below.
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            CompactFieldDecoration(
                value = value,
                interactionSource = interactionSource,
                enabled = enabled,
                singleLine = singleLine,
                placeholder = placeholder,
                isError = isError,
                innerTextField = innerTextField,
            )
        },
    )
}

// The single decoration behind every compact field — real text inputs and the read-only method picker
// alike. Routing both through one DecorationBox is what keeps a text field and a dropdown sitting side by
// side the *same height*: their outline, padding, and single-line box are computed from one source, so
// they can't drift apart when one is restyled (hand-matching two separate layouts is what kept breaking).
// A `trailingIcon` turns this into the dropdown affordance.
//
// The min-interactive-size opt-out is load-bearing, not cosmetic: Material wraps the trailing-icon slot
// in a 48.dp touch target, which silently forces the whole field to 48.dp — so a field WITH a trailing
// icon rendered 8.dp taller than one without, no matter how the padding was tuned. Dropping the slop
// (this is a pointer-driven desktop panel, and the whole field is the click target — same rationale as
// CompactSwitch) makes height depend only on the shared text line + padding, so every compact field lines
// up regardless of its trailing icon.
@Composable
internal fun CompactFieldDecoration(
    value: String,
    interactionSource: MutableInteractionSource,
    innerTextField: @Composable () -> Unit,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    placeholder: String? = null,
    isError: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        OutlinedTextFieldDefaults.DecorationBox(
            value = value,
            innerTextField = innerTextField,
            enabled = enabled,
            singleLine = singleLine,
            visualTransformation = VisualTransformation.None,
            interactionSource = interactionSource,
            isError = isError,
            placeholder = placeholder?.let { text ->
                { Text(text, style = MaterialTheme.typography.bodyMedium) }
            },
            trailingIcon = trailingIcon,
            contentPadding = OutlinedTextFieldDefaults.contentPadding(
                start = 12.dp,
                top = 8.dp,
                end = 12.dp,
                bottom = 8.dp,
            ),
        )
    }
}

// Material reserves a 48.dp interactive target around a 52×32.dp switch track — bulky in a dense,
// pointer-driven desktop panel. This drops that reservation (desktop doesn't need the touch slop) and
// scales the track down, keeping the stock ripple, hover, and thumb animation.
@Composable
internal fun CompactSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier.scale(0.8f),
            enabled = enabled,
            // The stock unchecked switch paints its thumb in `outline` — a near-disabled gray in this
            // monochrome theme, so "off" was indistinguishable from "disabled". Drive the off-state from
            // the secondary content color (a solid, clearly-active thumb) over a defined track so it reads
            // as an intentional off, not a greyed-out control.
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}
