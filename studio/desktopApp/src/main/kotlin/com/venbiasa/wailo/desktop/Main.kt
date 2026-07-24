package com.venbiasa.wailo.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import com.venbiasa.wailo.shared.BreakpointNode
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.PausedFlow
import com.venbiasa.wailo.shared.PickedFile
import com.venbiasa.wailo.shared.WailoApp
import com.venbiasa.wailo.shared.theme.TextScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Taskbar
import java.awt.image.BufferedImage
import java.io.File
import java.io.FilenameFilter
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.TimeZone
import javax.imageio.ImageIO
import kotlin.coroutines.resume
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
        rows.map {
            FlowEntry(
                it.deviceName,
                it.appId,
                it.platform,
                it.exchange,
                edited = it.exchange.edited,
                bodiesOmitted = it.exchange.bodies_omitted,
            )
        }
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

    // Unlocked hosts whose request/response bodies are captured (metadata is always captured). Host-owned
    // and persisted like bookmarks; `shared` gets the list plus unlock/lock callbacks and stays stateless.
    // The engine pushes this to devices, so only these hosts stream bodies (Proxyman-style "unlock").
    var unlockedHosts by remember { mutableStateOf(CaptureAllowlistStore.load()) }
    val unlockHost = { host: String ->
        if (host.isNotBlank() && host !in unlockedHosts) {
            unlockedHosts = unlockedHosts + host
            CaptureAllowlistStore.save(unlockedHosts)
        }
    }
    val lockHost = { host: String ->
        if (host in unlockedHosts) {
            unlockedHosts = unlockedHosts - host
            CaptureAllowlistStore.save(unlockedHosts)
        }
    }
    // Push the allowlist on first composition and every change; the engine re-pushes to any device that
    // hasn't acked it (like the Map Local rules below). Devices apply the newest snapshot they receive.
    LaunchedEffect(unlockedHosts) {
        engine.updateAllowlist(unlockedHosts)
    }

    // Map Local layout (groups + rules, in priority order): host-owned and persisted (like bookmarks).
    // `shared` renders it and hands back a whole new layout for any structural change; the host is the
    // only side that reads files and talks to the engine. The tool panel's open state and any row-seeded
    // draft are the viewer's own transient state now (ADR-0021), so the host only owns the layout.
    var mapLocalNodes by remember { mutableStateOf(MapLocalStore.load()) }
    val onLayoutChange = { next: List<MapLocalNode> ->
        val prev = mapLocalNodes
        mapLocalNodes = next
        // Sweep bodies orphaned by this change (a rule delete or a group delete-all) — the layout is
        // replaced wholesale, so the removed set is recovered by diffing (ADR-0026).
        MapLocalStore.reconcileRemovedBodies(prev, next)
        MapLocalStore.save(next)
    }
    // The engine (running on non-UI threads) resolves a matched rule's body through this seam; Compose
    // state can't be read off the composition, so bridge the current layout through an AtomicReference the
    // rule effect keeps fresh. Files are read on demand, on the IO dispatcher (ADR-0019).
    val ruleDefsRef = remember { java.util.concurrent.atomic.AtomicReference(mapLocalNodes) }
    LaunchedEffect(Unit) {
        engine.bodyProvider = MapLocalBodyProvider { ruleId, _, _ ->
            withContext(Dispatchers.IO) { serveBody(ruleId, ruleDefsRef.get()) }
        }
    }
    // Push the match-metadata snapshot (no file reads here) on first composition and every edit; a save
    // re-pushes the active rules in priority order, and the engine re-pushes to any device that hasn't
    // acked it. Group toggles/reorders change which rules are active and in what order (ADR-0026).
    LaunchedEffect(mapLocalNodes) {
        ruleDefsRef.set(mapLocalNodes)
        engine.updateRules(compileRules(mapLocalNodes))
    }

    // Breakpoint layout: host-owned and persisted like Map Local (groups + rules in priority order). `shared`
    // renders it and hands back a whole new layout for any structural change; the host persists it and pushes
    // the compiled active rules. On a match a device pauses and the engine surfaces it via [pausedExchanges]
    // below (ADR-0026/0027).
    var breakpointNodes by remember { mutableStateOf(BreakpointStore.load()) }
    val onBreakpointLayoutChange = { next: List<BreakpointNode> ->
        breakpointNodes = next
        BreakpointStore.save(next)
    }
    LaunchedEffect(breakpointNodes) {
        engine.updateBreakpointRules(compileBreakpointRules(breakpointNodes))
    }

    // Exchanges currently held at a breakpoint. Bridged from the engine row to the viewer's model at
    // this boundary (like [entries] above), since `shared` must not depend on `engine`.
    val paused by engine.pausedExchanges.collectAsState()
    val pausedFlows = remember(paused) {
        paused.map {
            PausedFlow(
                correlationId = it.correlationId,
                deviceName = it.deviceName,
                appId = it.appId,
                platform = it.platform,
                phase = it.phase,
                request = it.request,
                response = it.response,
            )
        }
    }

    // Docked tool-panel width, host-owned and persisted like the theme/scale above, but stored as a
    // fraction of the window so it scales with the window rather than pinning to a fixed dp. `shared`
    // gets the fraction plus a callback the resize handle drives; the clamping lives in ToolPanelLayout.
    var toolPanelWidthRatio by remember { mutableStateOf(PanelWidthStore.load()) }
    // Persist debounced so a drag-resize doesn't hammer prefs every frame (like the window geometry below).
    LaunchedEffect(Unit) {
        snapshotFlow { toolPanelWidthRatio }
            .debounce(300.milliseconds)
            .collect { PanelWidthStore.save(it) }
    }

    // The app icon: one bitmap drives the Compose window/taskbar icon and — because macOS surfaces the
    // Dock icon through AWT's Taskbar rather than the window icon — the Dock too, so the dev run shows
    // the real mark. A packaged app takes its icon from nativeDistributions instead (build.gradle.kts).
    val appIcon = remember { loadAppIcon() }
    val appIconPainter = remember(appIcon) { appIcon?.let { BitmapPainter(it.toComposeImageBitmap()) } }
    LaunchedEffect(appIcon) {
        val image = appIcon ?: return@LaunchedEffect
        runCatching { if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar().iconImage = image }
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
        icon = appIconPainter,
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
            unlockedHosts = unlockedHosts,
            onUnlockHost = unlockHost,
            onLockHost = lockHost,
            mapLocalNodes = mapLocalNodes,
            onMapLocalLayoutChange = onLayoutChange,
            onLoadMapLocalBody = { rule -> withContext(Dispatchers.IO) { MapLocalStore.loadInlineBody(rule) } },
            onSaveMapLocalBody = { rule, bytes -> withContext(Dispatchers.IO) { MapLocalStore.saveInlineBody(rule, bytes) } },
            // `window` (the ComposeWindow, an AWT Frame) parents the native dialog so it's modal to the app.
            onPickMapLocalFile = { chooseMapLocalFile(window) },
            breakpointNodes = breakpointNodes,
            onBreakpointLayoutChange = onBreakpointLayoutChange,
            pausedFlows = pausedFlows,
            onResumeBreakpoint = { correlationId, editedRequest, editedResponse ->
                engine.resumeBreakpoint(correlationId, editedRequest, editedResponse)
            },
            onAbortBreakpoint = { correlationId -> engine.abortBreakpoint(correlationId) },
            toolPanelWidthRatio = toolPanelWidthRatio,
            onToolPanelWidthRatioChange = { toolPanelWidthRatio = it },
        )
    }
}

/**
 * The Wailo app icon (the mark on the near-black accent), loaded from the classpath resource baked in
 * at src/main/resources/icons/wailo.png. Returns null — falling back to the platform default — only if
 * the resource is somehow missing, so a bad icon can never keep the window from opening.
 */
private fun loadAppIcon(): BufferedImage? = runCatching {
    object {}.javaClass.getResourceAsStream("/icons/wailo.png")?.use(ImageIO::read)
}.getOrNull()

// The common Map Local body file types, used to gently filter the native picker.
private val BodyFileExtensions = setOf(
    "json", "txt", "html", "xml", "js", "css", "csv", "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg",
)

/**
 * Opens the *native* OS file picker (Cocoa's NSOpenPanel on macOS, the Win32 dialog on Windows, GTK on
 * Linux) for a Map Local body file and reads the chosen file. [java.awt.FileDialog] is the native picker;
 * Swing's JFileChooser is a cross-platform look-alike, which is why we use FileDialog here. The file's
 * extension drives the Content-Type, which selects the editor's body surface (JSON editor vs image
 * preview) and what the mock serves. Returns null when the user cancels or the file can't be read.
 */
private suspend fun chooseMapLocalFile(owner: Frame?): PickedFile? {
    val file = awaitNativeFileDialog(owner) ?: return null
    return withContext(Dispatchers.IO) {
        runCatching { PickedFile(file.readBytes(), guessContentType(file.name)) }.getOrNull()
    }
}

/**
 * Shows the native modal [FileDialog] and suspends until it's dismissed. The dialog is opened via
 * [EventQueue.invokeLater] — on a *fresh* EDT event rather than inline — so its nested modal event loop
 * never runs inside Compose's current render/flush pass. Opening it inline (this is called from the
 * Compose composition scope) re-enters Compose's coroutine dispatcher mid-flush and crashes it with a
 * ClassCastException / ConcurrentModificationException.
 */
private suspend fun awaitNativeFileDialog(owner: Frame?): File? = suspendCancellableCoroutine { cont ->
    EventQueue.invokeLater {
        val dialog = FileDialog(owner, "Choose body file", FileDialog.LOAD).apply {
            isMultipleMode = false
            // Honored by the native macOS/Linux pickers to gray out non-body files; Windows ignores it and
            // shows everything, which is fine — any file can still be mapped.
            filenameFilter = FilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in BodyFileExtensions }
        }
        dialog.isVisible = true // blocks the EDT (nested modal loop) until the user chooses or cancels
        val name = dialog.file
        val dir = dialog.directory
        cont.resume(if (name != null && dir != null) File(dir, name) else null)
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
