// GENERATED FROM tokens.json — DO NOT EDIT
package com.venbiasa.wailo.shared.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Status/intent colors that have no Material 3 [androidx.compose.material3.ColorScheme] slot. Read
 * via [LocalWailoColors]; everything with an M3 slot goes through [MaterialTheme.colorScheme].
 */
@Immutable
data class WailoColors(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val info: Color,
    val onInfo: Color,
    val onSurfaceDisabled: Color,
)

private val LightColorScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightOnAccent,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceContainer = LightSurfaceContainer,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = LightOnError,
    scrim = LightScrim,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceContainer = DarkSurfaceContainer,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError,
    scrim = DarkScrim,
)

private val LightWailoColors = WailoColors(
    success = LightSuccess,
    onSuccess = LightOnSuccess,
    warning = LightWarning,
    onWarning = LightOnWarning,
    info = LightInfo,
    onInfo = LightOnInfo,
    onSurfaceDisabled = LightOnSurfaceDisabled,
)

private val DarkWailoColors = WailoColors(
    success = DarkSuccess,
    onSuccess = DarkOnSuccess,
    warning = DarkWarning,
    onWarning = DarkOnWarning,
    info = DarkInfo,
    onInfo = DarkOnInfo,
    onSurfaceDisabled = DarkOnSurfaceDisabled,
)

val LocalWailoColors = staticCompositionLocalOf { LightWailoColors }

@Composable
fun WailoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalWailoColors provides if (darkTheme) DarkWailoColors else LightWailoColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = wailoTypography(),
            content = content,
        )
    }
}
