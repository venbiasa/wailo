package com.venbiasa.wailo.shared.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_close
import com.venbiasa.wailo.shared.resources.ic_more_vert
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

/**
 * The traffic table's columns, in display order, all left-aligned. [defaultWidth] is a compile-time
 * starting size chosen for each column's expected content; the user can then drag columns wider or
 * narrower down to [minWidth], and the host persists what they dragged ([TrafficColumnLayout]). The
 * header and every row read the same width map, so they stay aligned.
 */
internal enum class TrafficColumn(
    val title: String,
    val defaultWidth: Dp,
    val minWidth: Dp,
) {
    Method("Method", 84.dp, 56.dp),
    Url("URL", 425.dp, 120.dp),
    Status("Status", 128.dp, 84.dp),
    Code("Code", 64.dp, 56.dp),
    Client("Client", 190.dp, 96.dp),
    Timestamp("Timestamp", 124.dp, 96.dp),
    Duration("Duration", 100.dp, 72.dp),
    Request("Request", 96.dp, 72.dp),
    Response("Response", 104.dp, 76.dp),
    Edited("Edited", 76.dp, 60.dp),
    Proxy("Proxy", 72.dp, 60.dp),
}

// Shared height for every docked surface's top bar, so the main viewer, Map Local, and the capture
// filter line up across the panels rather than each drifting to its own padding-derived height. The
// bars are icon-dominated (36.dp buttons), so 48.dp leaves an even 6.dp of vertical breathing room. A
// fixed height (like the traffic table's header) is safe because the text scale is capped at 1.8x
// (TextScale.Max), below where the buttons/titles would outgrow it.
internal val TopBarHeight = 48.dp

// Applied to a feature panel's body (the lists/rows) while its master switch is off: content stays
// legible but reads plainly inert, the muting a disabled control carries — without hiding what's still
// configured, since the per-item state is only paused, not erased.
internal const val DisabledFeatureAlpha = 0.5f

@Composable
internal fun RowDivider(color: Color = MaterialTheme.colorScheme.outlineVariant) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
internal fun ColumnDivider(color: Color = MaterialTheme.colorScheme.outlineVariant) {
    Box(Modifier.fillMaxHeight().width(1.dp).background(color))
}

// The two resize seams, one per axis: a visible grip in a wider invisible grab strip, with the matching
// resize cursor on hover. Shared rather than per-screen so every draggable split in the studio — the
// docked tool panel, the traffic detail pane, the breakpoint window's panels — grips and looks identical.
// The caller owns the size being dragged and its clamping; these only report the delta in pixels.
@Composable
internal fun PanelResizeHandle(onDragDelta: (Float) -> Unit) {
    Box(
        Modifier.fillMaxHeight()
            .width(ResizeHandleThickness)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { onDragDelta(it) },
            )
            .resizeCursor(ResizeAxis.Horizontal),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(3.dp).height(36.dp).background(MaterialTheme.colorScheme.outline))
    }
}

@Composable
internal fun DragHandle(onDragDelta: (Float) -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .height(ResizeHandleThickness)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { onDragDelta(it) },
            )
            .resizeCursor(ResizeAxis.Vertical),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(36.dp).height(3.dp).background(MaterialTheme.colorScheme.outline))
    }
}

// A layout that splits its space around a handle has to subtract the handle's own thickness to keep the
// two sides adding up, so the value is named rather than repeated as a literal at each site.
internal val ResizeHandleThickness = 9.dp

// Monospace styles keep the numeric columns and payloads tabular; chrome/text stays Noto Sans.
@Composable
internal fun monoSmall(): TextStyle =
    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

@Composable
internal fun monoLabel(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)

// A monospaced key/value line, sized to the row it's given. Shared by the detail panel's header/auth
// views and the form-body previewer — all of which sit in a pane the user can drag narrow, so the key
// gutter can't be fixed: a squeezed pane would leave the value no room. Above a floor the key keeps a
// tabular gutter (capped so keys align across rows) that shrinks with the row; below it the pair stacks
// so both stay readable rather than each wrapping in a sliver.
@Composable
internal fun KeyValueRow(key: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        // Read the width here: inside the Row/Column below, their layout scope shadows
        // BoxWithConstraintsScope (same DSL marker), so maxWidth isn't an implicit receiver there.
        val rowWidth = maxWidth
        if (rowWidth < 200.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(key, style = monoLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = monoSmall(), color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    key,
                    Modifier.width(minOf(200.dp, rowWidth * 0.4f)),
                    style = monoLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, Modifier.weight(1f), style = monoSmall(), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
internal fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// Every panel action icon is this tall, and so is the floor under a section band — a header carrying
// actions is otherwise 1.dp taller than a plain one (title text measures 19.dp, not the 20.sp line box it
// asks for), which reads as a hairline misalignment between two adjacent sections. Pinned by
// SectionHeaderHeightTest.
private val PanelIconButtonSize = 36.dp

// The tinted band that splits a panel's scrolling body into groups (Settings, Devices), matching the one
// the capture filter's lists use. [actions] takes the section's own icon buttons on the right. Only a
// floor, not a fixed height, so the band still grows with the user's text scale.
@Composable
internal fun SectionHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .heightIn(min = PanelIconButtonSize)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f).padding(vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        actions()
    }
    RowDivider()
}

// The empty line a section shows in place of its rows, indented to the section's own gutter so it reads as
// that section's body rather than as loose panel copy.
@Composable
internal fun SectionEmptyText(text: String) {
    MutedText(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
}

// The panels' shared secondary action icon: same geometry as the top bars' buttons, named on hover
// because these sit in dense header bands where a bare glyph carries no label.
@Composable
internal fun PanelIconButton(
    icon: DrawableResource,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    HoverTooltip(contentDescription) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(PanelIconButtonSize)) {
            Icon(
                vectorResource(icon),
                contentDescription = contentDescription,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DisabledFeatureAlpha)
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * A panel header's overflow: one icon button opening a menu of labelled actions. For actions whose
 * meaning a glyph can't carry — Export and Import are the same arrow to anyone who hasn't learned which
 * way round it goes — and which would otherwise push an already four-control header wider.
 */
@Composable
internal fun HeaderOverflowMenu(
    actions: List<ContextMenuAction>,
    contentDescription: String = "More actions",
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        HoverTooltip(contentDescription) {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)) {
                Icon(
                    vectorResource(Res.drawable.ic_more_vert),
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        expanded = false
                        action.onSelect()
                    },
                )
            }
        }
    }
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
    containerColor: Color = Color.Unspecified,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
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
                containerColor = containerColor,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
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
    containerColor: Color = Color.Unspecified,
    leadingIcon: (@Composable () -> Unit)? = null,
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
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            // Material's outlined container is transparent, so a field inherits whatever it sits on — right
            // on a plain panel, but on a tinted bar (the editor's find toolbar) it makes the field read as a
            // greyed-out control instead of an input. Such a caller passes the surface it should look like;
            // Unspecified keeps Material's default.
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = containerColor,
                unfocusedContainerColor = containerColor,
            ),
            // M3 lays the leading icon flush at x=0 and starts the text at
            // leadingWidth + (start - HorizontalIconPadding[12dp]). We opt out of the 48dp min-interactive
            // icon box (see above), which is what normally insets the glyph, so the caller pads its own
            // icon; 24.dp here then leaves a 12.dp gap (24-12) between the icon and the text.
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
// pointer-driven desktop panel. The track is authored at Material's own dimensions and scaled down, so
// the proportions stay stock while the control takes only the room a tight row can spare.
private const val CompactSwitchScale = 0.65f
private val SwitchTrackWidth = 52.dp
private val SwitchTrackHeight = 32.dp
private val SwitchTrackBorder = 2.dp
private val SwitchThumbOff = 16.dp
private val SwitchThumbOn = 24.dp
private val SwitchThumbInset = 4.dp
private const val SwitchAnimationMillis = 100

/**
 * A switch scaled for these dense panels, holding its thumb position in composition state.
 *
 * That last part is why this is not Material3's `Switch`: as of 1.9.0 that one parks the thumb's
 * `Animatable` on a `ThumbNode` that never overrides `onReset`, so a recycled `LazyColumn` slot arrives
 * still holding the previous row's offset and animates across to this one's — an already-enabled rule
 * visibly switches itself on as it scrolls back into view. `remember` *is* reset when a slot is reused,
 * so owning the animation here means a recycled row always starts settled and only a real toggle moves.
 */
@Composable
internal fun CompactSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val target = if (checked) 1f else 0f
    val travel = remember { Animatable(target) }
    LaunchedEffect(target) { travel.animateTo(target, tween(SwitchAnimationMillis)) }
    val moved = travel.value

    val scheme = MaterialTheme.colorScheme
    // Material's unchecked switch paints its thumb in `outline` — a near-disabled gray in this monochrome
    // theme, so "off" was indistinguishable from "disabled". The off state runs on the secondary content
    // color over a defined track instead, so it reads as an intentional off, not a greyed-out control.
    val track = lerpColor(scheme.surfaceVariant, scheme.primary, moved)
    val thumb = lerpColor(scheme.onSurfaceVariant, scheme.onPrimary, moved)

    Box(
        modifier
            // scale() is draw-only, so pair it with a layout that reports the scaled size — otherwise the
            // full 52×32.dp box lingers and leaves dead space around the shrunken thumb in these tight rows.
            .compactSwitchScale(CompactSwitchScale)
            // Ahead of the painting below, so a disabled switch fades whole rather than just its thumb.
            .alpha(if (enabled) 1f else DisabledFeatureAlpha)
            .size(SwitchTrackWidth, SwitchTrackHeight)
            .clip(CircleShape)
            .background(track)
            // Material outlines the off track and drops the outline once the track itself carries color.
            .border(SwitchTrackBorder, scheme.outline.copy(alpha = 1f - moved), CircleShape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Material's travel: the small off-thumb sits centered in the track's end cap, and the larger
        // on-thumb stops one inset short of the far edge.
        val off = (SwitchTrackHeight - SwitchThumbOff) / 2
        val on = SwitchTrackWidth - SwitchThumbInset - SwitchThumbOn
        Box(
            Modifier
                .offset(x = lerp(off, on, moved))
                .size(lerp(SwitchThumbOff, SwitchThumbOn, moved))
                .background(thumb, CircleShape),
        )
    }
}

// Shrink a fixed-size control by [scale] in both draw and layout: graphicsLayer scales the pixels while
// the layout wrapper reports the scaled size and re-centers the full-size child, so it truly occupies
// less room (and hit-tests to that smaller area) instead of floating inside its original box.
private fun Modifier.compactSwitchScale(scale: Float): Modifier =
    graphicsLayer {
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(0.5f, 0.5f)
    }.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val width = (placeable.width * scale).roundToInt()
        val height = (placeable.height * scale).roundToInt()
        layout(width, height) {
            placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
        }
    }
