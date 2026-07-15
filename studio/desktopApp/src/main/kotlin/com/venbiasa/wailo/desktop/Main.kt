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
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.TimeZone

fun main() = application {
    val engine = remember { WailoEngine().also(WailoEngine::start) }
    val rows by engine.exchanges.collectAsState()
    val capturing by engine.capturing.collectAsState()

    // The address devices should dial. The server binds every interface; we surface the host's LAN
    // IPv4 (not the wildcard) so a physical device knows where to point, falling back to localhost.
    val listenAddress = remember { "${resolveLanAddress()}:${engine.port}" }

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
            listenAddress = listenAddress,
            capturing = capturing,
            onToggleCapture = { engine.setCapturing(!capturing) },
            onClear = engine::clear,
        )
    }
}

/**
 * The host's primary LAN IPv4 — the address a device on the same network dials to reach the capture
 * server. We ask the OS which local interface routes toward a public IP via a UDP "connect" (which
 * sends nothing), so we get the active interface the way Proxyman does, instead of the first
 * enumerated site-local address — which is often a VPN/Docker/utun/bridge IP. Falls back to
 * "localhost" when there is no route (fully offline), which still covers the emulator/simulator case.
 */
private fun resolveLanAddress(): String = runCatching {
    DatagramSocket().use { socket ->
        socket.connect(InetAddress.getByName("8.8.8.8"), 53)
        socket.localAddress?.hostAddress
    }
}.getOrNull()?.takeUnless { it.isBlank() || it == "0.0.0.0" } ?: "localhost"
