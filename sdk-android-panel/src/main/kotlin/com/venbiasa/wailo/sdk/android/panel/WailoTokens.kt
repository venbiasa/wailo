package com.venbiasa.wailo.sdk.android.panel

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The semantic palette the on-device panel draws with, one instance per scheme.
 *
 * Read through [LocalWailoColors] rather than `MaterialTheme`: the panel is injected into someone else's
 * app, whose theme is not ours to inherit — a host with a branded `colorScheme` would repaint the panel in
 * its own colors and a host on Material 2, or no Compose theme at all, would leave it unstyled.
 */
@Immutable
internal class WailoColors(
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onSurfaceDisabled: Color,
    val outline: Color,
    val outlineVariant: Color,
    val accent: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
)

private val LightColors = WailoColors(
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurface = Color(0xFF0A0A0A),
    onSurfaceVariant = Color(0xFF525252),
    onSurfaceDisabled = Color(0xFFA3A3A3),
    outline = Color(0xFFD4D4D4),
    outlineVariant = Color(0xFFE5E5E5),
    accent = Color(0xFF0A0A0A),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF16A34A),
    warning = Color(0xFFF59E0B),
    error = Color(0xFFDC2626),
    info = Color(0xFF2563EB),
)

private val DarkColors = WailoColors(
    background = Color(0xFF000000),
    onBackground = Color(0xFFFAFAFA),
    surface = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF171717),
    surfaceVariant = Color(0xFF262626),
    onSurface = Color(0xFFFAFAFA),
    onSurfaceVariant = Color(0xFFA3A3A3),
    onSurfaceDisabled = Color(0xFF525252),
    outline = Color(0xFF404040),
    outlineVariant = Color(0xFF262626),
    accent = Color(0xFFFAFAFA),
    onAccent = Color(0xFF000000),
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
    error = Color(0xFFF87171),
    info = Color(0xFF60A5FA),
)

internal val LocalWailoColors = staticCompositionLocalOf { LightColors }

@Composable
internal fun WailoPanelTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWailoColors provides if (dark) DarkColors else LightColors, content = content)
}

internal val colors: WailoColors
    @Composable get() = LocalWailoColors.current

internal object Spacing {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x5 = 20.dp
    val x6 = 24.dp
}

internal object Radius {
    val sm = 4.dp
    val md = 8.dp
    val lg = 12.dp
}

/**
 * Token sizes and weights on the *system* face: Noto Sans would have to be bundled into the host app, and
 * this artifact ships inside other people's apps. Sizes are `sp`, so the reader's text-size setting scales
 * them without any work on our side.
 */
internal object Type {
    val titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.15.sp)
    val titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp)
    val bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp)
    val bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp)
    val labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp)
    val labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
    val monoMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
}
