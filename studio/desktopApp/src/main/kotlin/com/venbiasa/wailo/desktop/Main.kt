package com.venbiasa.wailo.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.WailoApp
import com.venbiasa.wailo.shared.theme.TextScale
import java.util.TimeZone

fun main() = application {
    val engine = remember { WailoEngine().also(WailoEngine::start) }
    val rows by engine.exchanges.collectAsState()

    // Map the engine's rows into the viewer's model at this boundary — `shared` must not depend on
    // `engine` (module firewall), so the two `Captured*` types are bridged here rather than shared.
    val entries = remember(rows) {
        rows.map { FlowEntry(it.deviceName, it.appId, it.platform, it.exchange) }
    }
    // The viewer formats timestamps in commonMain (no java.time), so pass the host's zone offset.
    val zoneOffsetMillis = remember { TimeZone.getDefault().getOffset(System.currentTimeMillis()) }

    // App-wide text size, driven by Cmd +/- (Cmd 0 resets). Handled here at the window because the
    // key intercept, the scale, and its persistence are host concerns; `shared` just receives the
    // multiplier. Seeded from the last saved value so the choice survives restarts.
    var textScale by remember { mutableStateOf(TextScaleStore.load()) }
    val setScale = { next: Float ->
        textScale = next
        TextScaleStore.save(next)
    }

    // Dark/light appearance, toggled from the top bar. Seeded from the OS on first run, then the
    // explicit choice is persisted so it survives restarts (host-owned, like the text scale above).
    val systemDark = isSystemInDarkTheme()
    var darkTheme by remember { mutableStateOf(ThemeStore.load(default = systemDark)) }
    val setDarkTheme = { next: Boolean ->
        darkTheme = next
        ThemeStore.save(next)
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Wailo",
        // Preview so the shortcut wins even when a child (e.g. a text field) holds focus. Cmd+= and
        // Cmd++ share the Equals key on most layouts; NumPad variants are handled for full keyboards.
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown || !event.isMetaPressed) {
                false
            } else when (event.key) {
                Key.Equals, Key.Plus, Key.NumPadAdd -> {
                    setScale(TextScale.increased(textScale))
                    true
                }
                Key.Minus, Key.NumPadSubtract -> {
                    setScale(TextScale.decreased(textScale))
                    true
                }
                Key.Zero, Key.NumPad0 -> {
                    setScale(TextScale.Default)
                    true
                }
                else -> false
            }
        },
    ) {
        WailoApp(
            entries = entries,
            zoneOffsetMillis = zoneOffsetMillis,
            darkTheme = darkTheme,
            onToggleDarkTheme = { setDarkTheme(!darkTheme) },
            textScale = textScale,
        )
    }
}
