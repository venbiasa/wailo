package com.venbiasa.wailo.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.venbiasa.wailo.engine.MapLocalBodyProvider
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.WailoApp
import com.venbiasa.wailo.shared.theme.TextScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.TimeZone
import kotlin.time.Duration.Companion.milliseconds

fun main() = runWailo()

// The window's first-run size, also enforced as its floor so the layout never has to reflow below the
// geometry it was designed against. On desktop Compose sizes windows from Dp values 1:1 with AWT's
// (density-independent) window units, so the same numbers drive both the initial size and the minimum.
private val DefaultWindowSize = DpSize(800.dp, 600.dp)

// debounce (used below to coalesce window resize/move writes) is still a coroutines preview API.
@OptIn(FlowPreview::class)
private fun runWailo() = application {
    val engine = remember { WailoEngine().also(WailoEngine::start) }
    val rows by engine.exchanges.collectAsState()
    val capturing by engine.capturing.collectAsState()

    // The address devices should dial. The server binds every interface; we surface the host's LAN
    // IPv4 (not the wildcard) so a physical device knows where to point, falling back to localhost.
    val listenAddress = remember { "${resolveLanAddress()}:${engine.port}" }

    // Map the engine's rows into the viewer's model at this boundary — `shared` must not depend on
    // `engine` (module firewall), so the two `Captured*` types are bridged here rather than shared.
    val entries = remember(rows) {
        rows.map { FlowEntry(it.deviceName, it.appId, it.platform, it.exchange, edited = it.exchange.edited) }
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

    // Bookmarked hosts, host-owned and persisted so they survive restarts (like the theme and text
    // scale above). `shared` gets the list plus add/remove callbacks and stays stateless (ADR-0013).
    var bookmarks by remember { mutableStateOf(BookmarkStore.load()) }
    val addBookmark = { host: String ->
        if (host.isNotBlank() && host !in bookmarks) {
            bookmarks = bookmarks + host
            BookmarkStore.save(bookmarks)
        }
    }
    val removeBookmark = { host: String ->
        if (host in bookmarks) {
            bookmarks = bookmarks - host
            BookmarkStore.save(bookmarks)
        }
    }

    // Map Local rules: host-owned and persisted (like bookmarks). `shared` gets the definitions plus
    // upsert/remove callbacks and stays stateless; the host is the only side that reads files and talks
    // to the engine. The tool panel's open state and any row-seeded draft are the viewer's own
    // transient state now (ADR-0021), so the host only owns the rules themselves.
    var mapLocalRules by remember { mutableStateOf(MapLocalStore.load()) }
    val upsertRule = { rule: MapLocalRuleDef ->
        mapLocalRules = if (mapLocalRules.any { it.id == rule.id }) {
            mapLocalRules.map { if (it.id == rule.id) rule else it }
        } else {
            mapLocalRules + rule
        }
        // A file-backed rule owns no managed body; drop any left over from a prior inline edit.
        if (!rule.inline) MapLocalStore.deleteInlineBody(rule.id)
        MapLocalStore.save(mapLocalRules)
    }
    val removeRule = { id: String ->
        mapLocalRules = mapLocalRules.filterNot { it.id == id }
        MapLocalStore.deleteInlineBody(id)
        MapLocalStore.save(mapLocalRules)
    }
    // The engine (running on non-UI threads) resolves a matched rule's body through this seam; Compose
    // state can't be read off the composition, so bridge the current defs through an AtomicReference the
    // rule effect keeps fresh. Files are read on demand, on the IO dispatcher (ADR-0019).
    val ruleDefsRef = remember { java.util.concurrent.atomic.AtomicReference(mapLocalRules) }
    LaunchedEffect(Unit) {
        engine.bodyProvider = MapLocalBodyProvider { ruleId, _, _ ->
            withContext(Dispatchers.IO) { serveBody(ruleId, ruleDefsRef.get()) }
        }
    }
    // Push the match-metadata snapshot (no file reads here) on first composition and every edit; a save
    // re-pushes the whole set, and the engine re-pushes to any device that hasn't acked it.
    LaunchedEffect(mapLocalRules) {
        ruleDefsRef.set(mapLocalRules)
        engine.updateRules(compileRules(mapLocalRules))
    }

    // Window geometry survives restarts (host concern, like the theme/scale/bookmarks above). Seeded
    // from the last floating size/position; first run falls back to Compose's default size and lets
    // the OS place the window.
    val windowState = rememberWindowState(
        size = WindowStateStore.loadSize(DefaultWindowSize),
        position = WindowStateStore.loadPosition(),
    )
    // Persist continuously (so a crash/force-quit still remembers), but only while Floating so a
    // maximized/fullscreen window never overwrites the saved floating geometry. Debounced so a
    // drag-resize doesn't hammer prefs every frame.
    LaunchedEffect(windowState) {
        snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }
            .filter { (_, _, placement) -> placement == WindowPlacement.Floating }
            .debounce(300.milliseconds)
            .collect { (size, position, _) -> WindowStateStore.save(size, position) }
    }

    Window(
        // Capture the final geometry on close too, in case the last move/resize landed inside the
        // debounce window and never flushed.
        onCloseRequest = {
            if (windowState.placement == WindowPlacement.Floating) {
                WindowStateStore.save(windowState.size, windowState.position)
            }
            exitApplication()
        },
        state = windowState,
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
        // Floor the window at its first-run size so the content can't be squeezed below the layout it
        // was built for. AWT enforces this on the OS chrome, covering drag-resize the Compose state
        // never sees. Runs on the EDT (Compose Desktop's main dispatcher), so touching AWT is safe.
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(
                DefaultWindowSize.width.value.toInt(),
                DefaultWindowSize.height.value.toInt(),
            )
        }
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
            bookmarks = bookmarks,
            onAddBookmark = addBookmark,
            onRemoveBookmark = removeBookmark,
            mapLocalRules = mapLocalRules,
            onUpsertRule = upsertRule,
            onRemoveRule = removeRule,
            onLoadMapLocalBody = { rule -> withContext(Dispatchers.IO) { MapLocalStore.loadInlineBody(rule) } },
            onSaveMapLocalBody = { rule, text -> withContext(Dispatchers.IO) { MapLocalStore.saveInlineBody(rule, text) } },
        )
    }
}

/**
 * The host's primary LAN IPv4 — the address a device on the same network dials to reach the capture
 * server. We ask the OS which local interface routes toward a public IP via a UDP "connect" (which
 * sends nothing), so we get the active outbound interface, instead of the first
 * enumerated site-local address — which is often a VPN/Docker/utun/bridge IP. Falls back to
 * "localhost" when there is no route (fully offline), which still covers the emulator/simulator case.
 */
private fun resolveLanAddress(): String = runCatching {
    DatagramSocket().use { socket ->
        socket.connect(InetAddress.getByName("8.8.8.8"), 53)
        socket.localAddress?.hostAddress
    }
}.getOrNull()?.takeUnless { it.isBlank() || it == "0.0.0.0" } ?: "localhost"
