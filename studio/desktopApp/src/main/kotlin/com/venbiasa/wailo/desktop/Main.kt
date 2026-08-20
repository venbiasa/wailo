package com.venbiasa.wailo.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.venbiasa.wailo.daemon.AdbConnectionStatus
import com.venbiasa.wailo.daemon.AdbDeviceInfo
import com.venbiasa.wailo.daemon.CLIENT_KIND_STUDIO
import com.venbiasa.wailo.daemon.DaemonClient
import com.venbiasa.wailo.daemon.DaemonLauncher
import com.venbiasa.wailo.daemon.UsbConnectionStatus
import com.venbiasa.wailo.daemon.UsbDeviceInfo
import com.venbiasa.wailo.engine.ConnectedDevice
import com.venbiasa.wailo.engine.DeviceTransport
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.engine.pairing.PairingCode
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.HostSeed
import com.venbiasa.wailo.protocol.CaptureFilter
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.shared.BreakpointLayoutCodec
import com.venbiasa.wailo.shared.BreakpointNode
import com.venbiasa.wailo.shared.BreakpointRuleDef
import com.venbiasa.wailo.shared.CaptureFilterState
import com.venbiasa.wailo.shared.DeviceConnectionStatus
import com.venbiasa.wailo.shared.DeviceInfo
import com.venbiasa.wailo.shared.DeviceTransportKind
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.MapLocalLayoutCodec
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.PairedDeviceInfo
import com.venbiasa.wailo.shared.PairingAction
import com.venbiasa.wailo.shared.PairingOfferInfo
import com.venbiasa.wailo.shared.PairingRefusal
import com.venbiasa.wailo.shared.PairingState
import com.venbiasa.wailo.shared.PausedFlow
import com.venbiasa.wailo.shared.PickedFile
import com.venbiasa.wailo.shared.RuleNode
import com.venbiasa.wailo.shared.ResponseHeader
import com.venbiasa.wailo.shared.SeedLayoutCodec
import com.venbiasa.wailo.shared.SeedNode
import com.venbiasa.wailo.shared.SeedRuleDef
import com.venbiasa.wailo.shared.WailoApp
import com.venbiasa.wailo.shared.WailoBreakpointWindowContent
import com.venbiasa.wailo.shared.allRules
import com.venbiasa.wailo.shared.isRuleActive
import com.venbiasa.wailo.shared.theme.TextScale
import com.venbiasa.wailo.desktop.pairing.PairingQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.util.TimeZone
import javax.imageio.ImageIO
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main() {
    // The kind is what lets the menu bar agent tell "a Studio is here, raise it" from "there is none, start
    // one" (ADR-0065); the presence connection itself is the daemon's reference count (ADR-0062).
    val daemon = runBlocking { DaemonClient.connect(holdPresence = true, clientKind = CLIENT_KIND_STUDIO) }
    try {
        runWailo(daemon)
    } finally {
        daemon.close()
    }
}

/**
 * Runs [action] when a daemon-relayed counter goes up, ignoring whatever it already stood at when this
 * window connected — a request made before Studio existed must not replay at launch (ADR-0065).
 */
@Composable
private fun OnDaemonRequest(counter: Int?, action: () -> Unit) {
    var seen by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(counter) {
        val value = counter ?: return@LaunchedEffect
        val previous = seen
        seen = value
        if (previous != null && value != previous) action()
    }
}

// The window's first-run size, also enforced as its floor so the layout never has to reflow below the
// geometry it was designed against. On desktop Compose sizes windows from Dp values 1:1 with AWT's
// (density-independent) window units, so the same numbers drive both the initial size and the minimum.
private val DefaultWindowSize = DpSize(800.dp, 600.dp)

// The breakpoint window (ADR-0034) opens larger than the main window's floor: it hosts the queue + the
// editor side by side, and the editor's headers/body panes need room. A modest minimum keeps both panes
// usable when the user shrinks it.
private val DefaultBreakpointWindowSize = DpSize(920.dp, 680.dp)
private val MinBreakpointWindowSize = DpSize(560.dp, 420.dp)

// debounce (used below to coalesce window resize/move writes) is still a coroutines preview API.
@OptIn(FlowPreview::class)
private fun runWailo(engine: DaemonClient) = application {
    val rows by engine.exchanges.collectAsState()
    val capturing by engine.capturing.collectAsState()
    val connectedDevices by engine.connectedDevices.collectAsState()
    val usbDevices by engine.usbDevices.collectAsState()
    val usbPort by engine.usbPort.collectAsState()
    val usbSupported by engine.usbSupported.collectAsState()
    val adbDevices by engine.adbDevices.collectAsState()
    val adbSupported by engine.adbSupported.collectAsState()
    val devices = remember(connectedDevices, usbDevices, adbDevices) {
        mergeDevices(connectedDevices, usbDevices, adbDevices)
    }

    val listenPort by engine.capturePort.collectAsState()
    val listening by engine.listening.collectAsState()
    var portError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(listening, listenPort) {
        if (!listening) portError = portUnavailable(listenPort)
    }
    val retryListen: suspend () -> Unit = {
        portError = if (engine.restart()) null else portUnavailable(engine.capturePort.value)
    }

    // Only the host can try a bind, so it — not `shared` — decides whether a port is usable and hands the
    // panel the verdict. Suspending: tearing the old server down blocks, which must not happen on the UI
    // thread. A rejected port leaves the server on the one it was already serving.
    val scope = rememberCoroutineScope()
    val applyPort: (Int) -> Unit = { next ->
        scope.launch {
            portError = when {
                next !in WailoEngine.PORT_RANGE ->
                    "Port must be between ${WailoEngine.PORT_RANGE.first} and ${WailoEngine.PORT_RANGE.last}."
                engine.rebind(next) -> {
                    PortStore.save(next)
                    null
                }
                else -> portUnavailable(next)
            }
        }
    }

    // The USB port is a number on the *phone*, so nothing here can validate it beyond its range — a port
    // no app is listening on is indistinguishable from an app that hasn't launched, and the Devices panel
    // is where that shows up. Applying it re-dials every attached device.
    var usbPortError by remember { mutableStateOf<String?>(null) }
    val applyUsbPort: (Int) -> Unit = { next ->
        if (next in WailoEngine.PORT_RANGE) {
            usbPortError = null
            UsbPortStore.save(next)
            scope.launch { engine.setUsbPort(next) }
        } else {
            usbPortError =
                "Port must be between ${WailoEngine.PORT_RANGE.first} and ${WailoEngine.PORT_RANGE.last}."
        }
    }

    // How much captured traffic the engine holds. The engine is the authority (it trims to fit the moment
    // the cap drops), so read it back from there rather than mirroring it here. Range is all there is to
    // validate — unlike a port, no number here can be refused by anything outside the process.
    val maxRetained by engine.maxRetained.collectAsState()
    var maxRetainedError by remember { mutableStateOf<String?>(null) }
    val applyMaxRetained: (Int) -> Unit = { next ->
        if (next in WailoEngine.RETAINED_RANGE) {
            maxRetainedError = null
            scope.launch { engine.setMaxRetained(next) }
            MaxRetainedStore.save(next)
        } else {
            maxRetainedError = "Must be between ${WailoEngine.RETAINED_RANGE.first} and " +
                "${WailoEngine.RETAINED_RANGE.last} requests."
        }
    }

    // Whether AI tools may reach the capture, and whether what they read is stripped of credentials
    // (ADR-0059). The daemon owns and persists both, because the gate has to hold for an MCP session
    // running with no Studio open — this window only shows and flips them.
    val mcpAccess by engine.mcpAccess.collectAsState()
    val mcpRedactSecrets by engine.mcpRedactSecrets.collectAsState()

    // The address devices should dial. The server binds every interface; we surface the host's LAN
    // IPv4 (not the wildcard) so a physical device knows where to point, falling back to localhost. The
    // engine re-resolves it as the host moves between networks, so a laptop woken somewhere else shows
    // where it actually is rather than where it was — including in the pairing QR below.
    val lanAddress by engine.lanAddress.collectAsState()
    val listenAddress = "$lanAddress:$listenPort"

    // --- Wi-Fi pairing (ADR-0039) ---------------------------------------------------------------
    // The engine owns the keys; the host owns the QR (ZXing is a desktop dependency, and `shared` has
    // no encoder) and the countdown, since `shared` is stateless and has no clock in commonMain.
    val daemonPairing by engine.pairing.collectAsState()
    val pairedDevices = daemonPairing.devices
    val pairingOffer = daemonPairing.offer
    val refusedDevices = daemonPairing.refusals
    val suspectedClones = daemonPairing.suspectedClones
    val requirePairing = daemonPairing.requirePairing
    var offerRemaining by remember { mutableStateOf(0) }
    LaunchedEffect(pairingOffer) {
        val offer = pairingOffer ?: return@LaunchedEffect
        while (true) {
            val remaining = ((offer.expiresAtEpochMs - System.currentTimeMillis()) / 1000)
                .coerceAtLeast(0).toInt()
            offerRemaining = remaining
            if (remaining == 0) break
            delay(1.seconds)
        }
        // Drop the offer once it lapses rather than leaving a dead code on screen; the engine already
        // refuses it, and a code that looks live but is not is worse than none.
        engine.cancelPairing()
    }
    val offerQr = remember(pairingOffer) {
        pairingOffer?.let { PairingQr.render(it.qrPayload) }
    }
    val pairingState = PairingState(
        offer = pairingOffer?.let {
            PairingOfferInfo(qr = offerQr, code = PairingCode.format(it.code), remainingSeconds = offerRemaining)
        },
        devices = pairedDevices.map {
            PairedDeviceInfo(
                deviceId = it.deviceId,
                name = it.name,
                pairedAtEpochMs = it.pairedAtEpochMs,
                lastSeenEpochMs = it.lastSeenEpochMs,
                suspectedClone = it.deviceId in suspectedClones,
                trustedOnFirstUse = it.trustedOnFirstUse,
            )
        },
        refusals = refusedDevices.map { PairingRefusal(it.deviceId, it.reason) },
        supported = daemonPairing.supported,
        requirePairing = requirePairing,
        studioId = daemonPairing.studioId,
    )
    val onPairingAction: (PairingAction) -> Unit = { action ->
        scope.launch {
            when (action) {
                PairingAction.Begin -> engine.beginPairing()
                PairingAction.Cancel -> engine.cancelPairing()
                is PairingAction.Forget -> engine.forgetDevice(action.deviceId)
                PairingAction.ForgetAll -> engine.forgetAllDevices()
                PairingAction.ResetIdentity -> engine.resetIdentity()
                is PairingAction.SetRequirePairing -> {
                    engine.setRequirePairing(action.enabled)
                    RequirePairingStore.save(action.enabled)
                }
                is PairingAction.DismissRefusal -> engine.dismissRefusal(action.deviceId)
            }
        }
    }

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

    // Capture filter: the allow/block host lists + each list's on/off switch that decide which traffic
    // devices capture and stream (ADR-0029). Host-owned and persisted like bookmarks; `shared` renders it
    // and hands back a whole new [CaptureFilterState] for any change and stays stateless. The engine pushes
    // it to devices, which gate whole exchanges at the source — with both lists off (the default) every
    // exchange is captured.
    val daemonCaptureFilter by engine.captureFilter.collectAsState()
    val initialDaemonCaptureFilter = remember { engine.captureFilter.value }
    val daemonFilterConfigured = initialDaemonCaptureFilter.allow_patterns.isNotEmpty() ||
        initialDaemonCaptureFilter.block_patterns.isNotEmpty()
    var captureFilter by remember {
        mutableStateOf(
            if (daemonFilterConfigured) initialDaemonCaptureFilter.toUiState() else CaptureFilterStore.load(),
        )
    }
    val onCaptureFilterChange = { next: CaptureFilterState ->
        captureFilter = next
        CaptureFilterStore.save(next)
    }
    // Push the filter on first composition and every change; the engine re-pushes to any device that
    // hasn't acked it (like the Map Local rules below). Devices apply the newest snapshot they receive.
    // The feature master sits above both lists (ADR-0030): while it's off, neither list is armed — so
    // devices capture everything — yet each list keeps its own hosts and armed state, ready for when the
    // master flips back on. It gates only what's pushed, never the persisted filter.
    var lastPublishedFilterSignature by remember {
        mutableStateOf(captureFilterSignature(initialDaemonCaptureFilter))
    }
    LaunchedEffect(captureFilter) {
        val on = captureFilter.masterEnabled
        val signature = captureFilterSignature(captureFilter)
        if (signature != lastPublishedFilterSignature) {
            engine.updateCaptureFilter(
                allowlistEnabled = on && captureFilter.allowEnabled,
                allowPatterns = captureFilter.allowHosts,
                blocklistEnabled = on && captureFilter.blockEnabled,
                blockPatterns = captureFilter.blockHosts,
            )
            lastPublishedFilterSignature = signature
        }
    }
    LaunchedEffect(daemonCaptureFilter) {
        val signature = captureFilterSignature(daemonCaptureFilter)
        if (signature == lastPublishedFilterSignature) return@LaunchedEffect
        val daemonConfigured = daemonCaptureFilter.allow_patterns.isNotEmpty() ||
            daemonCaptureFilter.block_patterns.isNotEmpty()
        val localConfigured = captureFilter.allowHosts.isNotEmpty() || captureFilter.blockHosts.isNotEmpty()
        // An empty daemon snapshot is a restart, not a user-cleared filter. Re-push the authored lists
        // rather than copying emptiness into prefs (ADR-0061).
        if (localConfigured && !daemonConfigured) {
            val on = captureFilter.masterEnabled
            engine.updateCaptureFilter(
                allowlistEnabled = on && captureFilter.allowEnabled,
                allowPatterns = captureFilter.allowHosts,
                blocklistEnabled = on && captureFilter.blockEnabled,
                blockPatterns = captureFilter.blockHosts,
            )
            lastPublishedFilterSignature = captureFilterSignature(captureFilter)
            return@LaunchedEffect
        }
        val imported = daemonCaptureFilter.toUiState()
        captureFilter = imported
        CaptureFilterStore.save(imported)
        lastPublishedFilterSignature = signature
    }

    // Map Local layout (groups + rules, in priority order): Studio prefs keep the authored structure;
    // the daemon persists the compiled snapshot plus that layout string so a restart still serves and
    // still restores groups (ADR-0061). `shared` only renders the layout.
    val daemonMapLocalRules by engine.mapLocalRules.collectAsState()
    val daemonMapLocalEnabled by engine.mapLocalEnabled.collectAsState()
    val daemonMapLocalLayout by engine.mapLocalLayout.collectAsState()
    val storedMapLocal = remember { MapLocalStore.load() }
    val initialDaemonMapRules = remember { engine.mapLocalRules.value }
    val initialDaemonMapLayout = remember { engine.mapLocalLayout.value }
    var mapLocalNodes by remember {
        mutableStateOf(
            initialMapLocalNodes(storedMapLocal, initialDaemonMapLayout, initialDaemonMapRules),
        )
    }
    val onLayoutChange = { next: List<MapLocalNode> ->
        val prev = mapLocalNodes
        mapLocalNodes = next
        // Sweep bodies orphaned by this change (a rule delete or a group delete-all) — the layout is
        // replaced wholesale, so the removed set is recovered by diffing (ADR-0026).
        MapLocalStore.reconcileRemovedBodies(prev, next)
        MapLocalStore.save(next)
    }
    // Map Local's feature master (ADR-0030): host-owned and persisted like the layout. Off gates what's
    // pushed (see below) — the saved layout is untouched, so flipping it back on restores every rule.
    var mapLocalEnabled by remember {
        mutableStateOf(
            if (storedMapLocal.isNotEmpty()) MapLocalStore.loadEnabled() else engine.mapLocalEnabled.value,
        )
    }
    val onMapLocalEnabledChange = { next: Boolean ->
        mapLocalEnabled = next
        MapLocalStore.saveEnabled(next)
    }
    var lastPublishedMapSignature by remember {
        mutableStateOf(mapRuleSignature(initialDaemonMapRules, engine.mapLocalEnabled.value))
    }
    LaunchedEffect(mapLocalNodes, mapLocalEnabled) {
        val rules = withContext(Dispatchers.IO) { mapLocalNodes.toHostMapLocalRules() }
        val signature = mapRuleSignature(rules, mapLocalEnabled)
        if (signature != lastPublishedMapSignature) {
            engine.replaceMapLocalRules(rules, mapLocalEnabled, MapLocalLayoutCodec.encode(mapLocalNodes))
            lastPublishedMapSignature = signature
        }
    }
    LaunchedEffect(daemonMapLocalRules, daemonMapLocalEnabled, daemonMapLocalLayout) {
        val signature = mapRuleSignature(daemonMapLocalRules, daemonMapLocalEnabled)
        if (signature == lastPublishedMapSignature) return@LaunchedEffect
        // The master is one boolean the daemon owns, so the menu bar item or an agent can flip it while
        // this window is open (ADR-0066). Adopt it up here, before any of the rule-import decisions below
        // return: a feature still shows its switch with no rules under it, and a stale switch means the
        // next edit silently republishes the value the user just changed. The exception is a daemon that
        // came back with nothing while we hold a layout — there its master is a restored default, not a
        // choice, and the re-seed below sends ours.
        if (daemonMapLocalRules.isNotEmpty() || mapLocalNodes.isEmpty()) {
            if (daemonMapLocalEnabled != mapLocalEnabled) {
                mapLocalEnabled = daemonMapLocalEnabled
                MapLocalStore.saveEnabled(daemonMapLocalEnabled)
                lastPublishedMapSignature = signature
            }
        }
        // Studio's grouped layout is the authoring copy. An empty daemon is a restart, not a delete —
        // re-seed it. A non-empty daemon while we already have a layout is a flattened projection of
        // the same rules (or an MCP upsert); importing it would drop groups and names.
        if (mapLocalNodes.isNotEmpty()) {
            if (daemonMapLocalRules.isEmpty()) {
                val rules = withContext(Dispatchers.IO) { mapLocalNodes.toHostMapLocalRules() }
                engine.replaceMapLocalRules(rules, mapLocalEnabled, MapLocalLayoutCodec.encode(mapLocalNodes))
                lastPublishedMapSignature = mapRuleSignature(rules, mapLocalEnabled)
            }
            return@LaunchedEffect
        }
        val imported = if (daemonMapLocalLayout.isNotBlank()) {
            MapLocalLayoutCodec.decode(daemonMapLocalLayout)
        } else {
            importMapLocalRules(daemonMapLocalRules)
        }
        if (imported.isEmpty()) return@LaunchedEffect
        mapLocalNodes = imported
        mapLocalEnabled = daemonMapLocalEnabled
        MapLocalStore.save(imported)
        MapLocalStore.saveEnabled(daemonMapLocalEnabled)
        lastPublishedMapSignature = signature
    }

    // Breakpoint layout: Studio prefs keep the authored structure; the daemon persists the compiled
    // snapshot plus that layout string so a restart still arms the same rules (ADR-0061).
    val daemonBreakpointRules by engine.breakpointRules.collectAsState()
    val daemonBreakpointsEnabled by engine.breakpointsEnabled.collectAsState()
    val daemonBreakpointLayout by engine.breakpointLayout.collectAsState()
    val storedBreakpoints = remember { BreakpointStore.load() }
    val initialDaemonBreakpointRules = remember { engine.breakpointRules.value }
    val initialDaemonBreakpointLayout = remember { engine.breakpointLayout.value }
    var breakpointNodes by remember {
        mutableStateOf(
            initialBreakpointNodes(
                storedBreakpoints,
                initialDaemonBreakpointLayout,
                initialDaemonBreakpointRules,
            ),
        )
    }
    val onBreakpointLayoutChange = { next: List<BreakpointNode> ->
        breakpointNodes = next
        BreakpointStore.save(next)
    }
    // Breakpoints' feature master (ADR-0030), mirroring Map Local: off pushes no rules (so nothing pauses)
    // while the saved layout stays intact for when it flips back on.
    var breakpointsEnabled by remember {
        mutableStateOf(
            if (storedBreakpoints.isNotEmpty()) {
                BreakpointStore.loadEnabled()
            } else {
                engine.breakpointsEnabled.value
            },
        )
    }
    val onBreakpointsEnabledChange = { next: Boolean ->
        breakpointsEnabled = next
        BreakpointStore.saveEnabled(next)
    }
    var lastPublishedBreakpointSignature by remember {
        mutableStateOf(breakpointRuleSignature(initialDaemonBreakpointRules, engine.breakpointsEnabled.value))
    }
    LaunchedEffect(breakpointNodes, breakpointsEnabled) {
        val rules = breakpointNodes.toHostBreakpointRules()
        val signature = breakpointRuleSignature(rules, breakpointsEnabled)
        if (signature != lastPublishedBreakpointSignature) {
            engine.replaceBreakpointRules(
                rules,
                breakpointsEnabled,
                BreakpointLayoutCodec.encode(breakpointNodes),
            )
            lastPublishedBreakpointSignature = signature
        }
    }
    LaunchedEffect(daemonBreakpointRules, daemonBreakpointsEnabled, daemonBreakpointLayout) {
        val signature = breakpointRuleSignature(daemonBreakpointRules, daemonBreakpointsEnabled)
        if (signature == lastPublishedBreakpointSignature) return@LaunchedEffect
        // Adopted before the import decisions, and under the same restart exception, as Map Local above.
        if (daemonBreakpointRules.isNotEmpty() || breakpointNodes.isEmpty()) {
            if (daemonBreakpointsEnabled != breakpointsEnabled) {
                breakpointsEnabled = daemonBreakpointsEnabled
                BreakpointStore.saveEnabled(daemonBreakpointsEnabled)
                lastPublishedBreakpointSignature = signature
            }
        }
        if (breakpointNodes.isNotEmpty()) {
            if (daemonBreakpointRules.isEmpty()) {
                val rules = breakpointNodes.toHostBreakpointRules()
                engine.replaceBreakpointRules(
                    rules,
                    breakpointsEnabled,
                    BreakpointLayoutCodec.encode(breakpointNodes),
                )
                lastPublishedBreakpointSignature = breakpointRuleSignature(rules, breakpointsEnabled)
            }
            return@LaunchedEffect
        }
        val imported = if (daemonBreakpointLayout.isNotBlank()) {
            BreakpointLayoutCodec.decode(daemonBreakpointLayout)
        } else {
            importBreakpointRules(daemonBreakpointRules)
        }
        if (imported.isEmpty()) return@LaunchedEffect
        breakpointNodes = imported
        breakpointsEnabled = daemonBreakpointsEnabled
        BreakpointStore.save(imported)
        BreakpointStore.saveEnabled(daemonBreakpointsEnabled)
        lastPublishedBreakpointSignature = signature
    }

    // Seed layout: Studio prefs keep the authored structure and the body files, and the daemon gets the
    // flattened library with its bytes inline — a seed is spent by whoever owns the hold, and that is the
    // daemon whether or not this window exists (ADR-0067). Published and adopted exactly like the two
    // above; unlike them, nothing reaches a device.
    val daemonSeeds by engine.seeds.collectAsState()
    val daemonSeedsEnabled by engine.seedsEnabled.collectAsState()
    val daemonSeedLayout by engine.seedLayout.collectAsState()
    val storedSeeds = remember { SeedStore.load() }
    val initialDaemonSeeds = remember { engine.seeds.value }
    val initialDaemonSeedLayout = remember { engine.seedLayout.value }
    var seedNodes by remember {
        mutableStateOf(initialSeedNodes(storedSeeds, initialDaemonSeedLayout, initialDaemonSeeds))
    }
    val onSeedLayoutChange = { next: List<SeedNode> ->
        val prev = seedNodes
        seedNodes = next
        SeedStore.reconcileRemovedBodies(prev, next)
        SeedStore.save(next)
    }
    var seedsEnabled by remember {
        mutableStateOf(if (storedSeeds.isNotEmpty()) SeedStore.loadEnabled() else engine.seedsEnabled.value)
    }
    val onSeedsEnabledChange = { next: Boolean ->
        seedsEnabled = next
        SeedStore.saveEnabled(next)
    }
    var lastPublishedSeedSignature by remember {
        mutableStateOf(seedSignature(initialDaemonSeeds, engine.seedsEnabled.value))
    }
    LaunchedEffect(seedNodes, seedsEnabled) {
        val seeds = withContext(Dispatchers.IO) { seedNodes.toHostSeeds() }
        val signature = seedSignature(seeds, seedsEnabled)
        if (signature != lastPublishedSeedSignature) {
            engine.replaceSeeds(seeds, seedsEnabled, SeedLayoutCodec.encode(seedNodes))
            lastPublishedSeedSignature = signature
        }
    }
    LaunchedEffect(daemonSeeds, daemonSeedsEnabled, daemonSeedLayout) {
        val signature = seedSignature(daemonSeeds, daemonSeedsEnabled)
        if (signature == lastPublishedSeedSignature) return@LaunchedEffect
        // Adopted before the import decisions, and under the same restart exception, as Map Local above.
        if (daemonSeeds.isNotEmpty() || seedNodes.isEmpty()) {
            if (daemonSeedsEnabled != seedsEnabled) {
                seedsEnabled = daemonSeedsEnabled
                SeedStore.saveEnabled(daemonSeedsEnabled)
                lastPublishedSeedSignature = signature
            }
        }
        if (seedNodes.isNotEmpty()) {
            if (daemonSeeds.isEmpty()) {
                val seeds = withContext(Dispatchers.IO) { seedNodes.toHostSeeds() }
                engine.replaceSeeds(seeds, seedsEnabled, SeedLayoutCodec.encode(seedNodes))
                lastPublishedSeedSignature = seedSignature(seeds, seedsEnabled)
            }
            return@LaunchedEffect
        }
        val imported = if (daemonSeedLayout.isNotBlank()) {
            SeedLayoutCodec.decode(daemonSeedLayout)
        } else {
            importSeeds(daemonSeeds)
        }
        if (imported.isEmpty()) return@LaunchedEffect
        // A seed authored elsewhere arrives with its bytes, and the panel reads bodies from disk, so the
        // import has to land them as files or the seed would show and serve as empty here.
        withContext(Dispatchers.IO) { daemonSeeds.forEach(SeedStore::importHostBody) }
        seedNodes = imported
        seedsEnabled = daemonSeedsEnabled
        SeedStore.save(imported)
        SeedStore.saveEnabled(daemonSeedsEnabled)
        lastPublishedSeedSignature = signature
    }

    // The armed queue, as the daemon reports it: what Fill loaded, minus whatever has been spent. Session
    // state on purpose — it outlives closing and reopening the breakpoint window (so a queue armed for a
    // flow isn't lost to a stray close) but not a daemon restart, since a half-spent queue is a snapshot
    // of a run in progress, not a preference (ADR-0041).
    val daemonSeedQueue by engine.seedQueue.collectAsState()
    val seedQueue = remember(daemonSeedQueue, seedNodes) {
        val authored = seedNodes.allRules().associateBy { it.id }
        // Prefer the authored definition so the window lists the seed the way the panel does; a seed
        // armed from another frontend still shows, rebuilt from what the daemon holds.
        daemonSeedQueue.map { authored[it.id] ?: it.toSeedRuleDef() }
    }
    // Bumped after an import so the viewer reveals it by opening the Seed panel.
    var openSeedPanelRequests by remember { mutableStateOf(0) }
    // Copies a Map Local rule into the Seed list (its row's right-click "Seed…"). It lands here rather
    // than in `shared` because it spans both layouts and copies a body file — all host-owned. The seed is
    // appended, so it takes the lowest priority and an existing armed queue's order is undisturbed.
    val onSeedFromMapLocalRule = { rule: MapLocalRuleDef ->
        val seed = SeedRuleDef(
            id = SeedRuleDef.newId(),
            urlPattern = rule.urlPattern,
            method = rule.method,
            statusCode = rule.statusCode,
            headers = rule.headers,
        )
        scope.launch {
            // Copying the body is a file read + write of an arbitrarily large payload, so it goes off the
            // UI thread; the layout is committed after, so the seed is never listed without its body.
            withContext(Dispatchers.IO) {
                val contentType = seed.headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value
                SeedStore.importBody(seed.id, contentType, MapLocalStore.loadServedBody(rule))
            }
            onSeedLayoutChange(seedNodes + RuleNode(seed))
            openSeedPanelRequests += 1
        }
        Unit
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

    // The breakpoint window is user-owned (ADR-0041): opened from the Breakpoints panel or by a hold that
    // needs a human, closed only by the user. It used to exist exactly while something was held, which
    // can't work now that a seed-answered hold must not flash a window open, and that arming seeds
    // requires the window before any traffic arrives.
    var breakpointWindowOpen by remember { mutableStateOf(false) }
    // Bumped whenever the window should be raised. Opening an already-open window is a no-op on the flag
    // above, so "open it" from the panel would look broken once it's buried; the inspector bumps this too
    // when a hold arrives behind another window.
    var raiseBreakpointWindow by remember { mutableStateOf(0) }

    // The daemon spends seeds, so the only decision left here is whether a hold needs a human. A hold it
    // has finished deciding about and not answered — a request-phase hold, or a response with no matching
    // seed — opens the window. Waiting for that verdict rather than opening on first sight is what keeps
    // a seed-answered hold from flashing a window open (ADR-0067).
    val triagedHolds by engine.triagedHolds.collectAsState()
    LaunchedEffect(pausedFlows, triagedHolds) {
        if (pausedFlows.any { it.correlationId in triagedHolds }) breakpointWindowOpen = true
    }

    // Fill arms the daemon's enabled library in order and spends it against whatever is already waiting.
    // Arming late is the normal case: you notice a hold sitting there and only then load the seeds for
    // it, and a queue that arrives a moment too late to be useful is a queue you'd have to re-trigger the
    // traffic for (ADR-0044). Re-filling replaces the queue, resetting a partly-spent sequence mid-run.
    val onFillSeeds = {
        scope.launch { engine.fillSeeds() }
        Unit
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
        size = WindowStateStore.Main.loadSize(DefaultWindowSize),
        position = WindowStateStore.Main.loadPosition(),
    )
    // Persist continuously (so a crash/force-quit still remembers), but only while Floating so a
    // maximized/fullscreen window never overwrites the saved floating geometry. Debounced so a
    // drag-resize doesn't hammer prefs every frame.
    LaunchedEffect(windowState) {
        snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }
            .filter { (_, _, placement) -> placement == WindowPlacement.Floating }
            .debounce(300.milliseconds)
            .collect { (size, position, _) -> WindowStateStore.Main.save(size, position) }
    }

    // Cmd +/- (Cmd 0 resets) text zoom, shared by every window so the shortcut behaves the same whichever
    // one holds focus (the breakpoint window's body editor benefits from it as much as the main list).
    // Preview so it wins even when a child (e.g. a text field) holds focus. Cmd+= and Cmd++ share the
    // Equals key on most layouts; NumPad variants are handled for full keyboards.
    val onScaleKeyEvent: (KeyEvent) -> Boolean = onScaleKeyEvent@{ event ->
        if (event.type != KeyEventType.KeyDown || !event.isMetaPressed) return@onScaleKeyEvent false
        when (event.key) {
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
    }

    // Cmd/Ctrl+F opens the traffic list's filter bar. It has to live at the window level, not on a
    // focusable inside `shared`: Compose only dispatches key events to the focused node's ancestor chain,
    // and clicking the list background or the top bar seeds no focus at all — so a handler in the content
    // never ran until something (a detail panel's editor) happened to take focus. The window sees the key
    // regardless. `onKeyEvent`, not preview, is what preserves the contract that a *focused* code editor's
    // own find wins: it consumes the shortcut in its preview handler and this is never reached.
    var openFilterRequests by remember { mutableStateOf(0) }
    val onFilterKeyEvent: (KeyEvent) -> Boolean = onFilterKeyEvent@{ event ->
        val find = event.type == KeyEventType.KeyDown &&
            (event.isMetaPressed || event.isCtrlPressed) &&
            event.key == Key.F
        if (!find) return@onFilterKeyEvent false
        openFilterRequests += 1
        true
    }

    var windowVisible by remember { mutableStateOf(true) }
    // Bumped whenever the window should come forward. Showing an already-visible window is a no-op on the
    // flag above, so "Show Studio" on a buried window would otherwise look broken.
    var raiseMainWindow by remember { mutableStateOf(0) }
    // Capture the final geometry before the window goes, in case the last move/resize landed inside the
    // debounce window and never flushed.
    val saveMainWindowGeometry = {
        if (windowState.placement == WindowPlacement.Floating) {
            WindowStateStore.Main.save(windowState.size, windowState.position)
        }
    }
    val showStudio = {
        windowVisible = true
        raiseMainWindow += 1
    }
    // Cmd-Q ends this window's process and nothing else. The daemon is not Studio's to stop any more: the
    // item that stands for it is a separate process and carries the Quit that takes everything down
    // (ADR-0065), and an unreferenced daemon retires itself anyway (ADR-0062).
    val quitStudio = {
        saveMainWindowGeometry()
        exitApplication()
    }
    DisposableEffect(Unit) {
        val reopened = DesktopAppEvents.addReopenedListener(showStudio)
        val quit = DesktopAppEvents.setQuitHandler(quitStudio)
        onDispose {
            reopened?.close()
            quit?.close()
        }
    }
    // The menu bar item belongs to its own process now (ADR-0065), so its two rows that reach in here
    // arrive as counters the daemon relays.
    OnDaemonRequest(engine.showStudioRequests.collectAsState().value, showStudio)
    // The agent stops the daemon itself once the frontends are gone, so this only takes Studio down.
    OnDaemonRequest(engine.quitRequests.collectAsState().value, quitStudio)

    Window(
        onCloseRequest = {
            saveMainWindowGeometry()
            // Closing leaves the menu bar item to stand for the daemon — but only if something is actually
            // drawing one, since hiding the last window with no item to reopen it strands the app. Asked
            // here rather than at launch because the agent is spawned alongside this window and may not
            // have claimed its lock yet; by the time a human closes the window, the answer has settled.
            if (DaemonLauncher.menubarRunning()) windowVisible = false else exitApplication()
        },
        state = windowState,
        visible = windowVisible,
        title = "Wailo",
        icon = appIconPainter,
        onPreviewKeyEvent = onScaleKeyEvent,
        onKeyEvent = onFilterKeyEvent,
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
        // Skipped at 0 so launching the app does not fight the OS for foreground; every later bump is a
        // "Show Studio" that has to win against whatever the user was looking at.
        LaunchedEffect(raiseMainWindow) {
            if (raiseMainWindow == 0) return@LaunchedEffect
            window.toFront()
            window.requestFocus()
            DesktopAppEvents.requestForeground()
        }
        WailoApp(
            entries = entries,
            zoneOffsetMillis = zoneOffsetMillis,
            darkTheme = darkTheme,
            onToggleDarkTheme = { setDarkTheme(!darkTheme) },
            textScale = textScale,
            openFilterSignal = openFilterRequests,
            listenAddress = listenAddress,
            listenPort = listenPort,
            listening = listening,
            onRetryListen = retryListen,
            portError = portError,
            onApplyPort = applyPort,
            devices = devices,
            usbSupported = usbSupported,
            usbPort = usbPort,
            usbPortError = usbPortError,
            onApplyUsbPort = applyUsbPort,
            adbSupported = adbSupported,
            maxRetained = maxRetained,
            maxRetainedError = maxRetainedError,
            onApplyMaxRetained = applyMaxRetained,
            mcpAccess = mcpAccess,
            onMcpAccessChange = { scope.launch { engine.setMcpAccess(it) } },
            mcpRedactSecrets = mcpRedactSecrets,
            onMcpRedactSecretsChange = { scope.launch { engine.setMcpRedactSecrets(it) } },
            pairing = pairingState,
            onPairingAction = onPairingAction,
            capturing = capturing,
            onToggleCapture = { scope.launch { engine.setCapturing(!capturing) } },
            onClear = { scope.launch { engine.clear() } },
            bookmarks = bookmarks,
            onAddBookmark = addBookmark,
            onRemoveBookmark = removeBookmark,
            captureFilter = captureFilter,
            onCaptureFilterChange = onCaptureFilterChange,
            mapLocalNodes = mapLocalNodes,
            onMapLocalLayoutChange = onLayoutChange,
            onLoadMapLocalBody = { rule -> withContext(Dispatchers.IO) { MapLocalStore.loadInlineBody(rule) } },
            onSaveMapLocalBody = { rule, bytes -> withContext(Dispatchers.IO) { MapLocalStore.saveInlineBody(rule, bytes) } },
            // `window` (the ComposeWindow, an AWT Frame) parents the native dialog so it's modal to the app.
            onPickMapLocalFile = { chooseMapLocalFile(window) },
            mapLocalEnabled = mapLocalEnabled,
            onMapLocalEnabledChange = onMapLocalEnabledChange,
            onSeedFromMapLocalRule = onSeedFromMapLocalRule,
            breakpointNodes = breakpointNodes,
            onBreakpointLayoutChange = onBreakpointLayoutChange,
            breakpointsEnabled = breakpointsEnabled,
            onBreakpointsEnabledChange = onBreakpointsEnabledChange,
            onOpenBreakpointWindow = {
                breakpointWindowOpen = true
                raiseBreakpointWindow += 1
            },
            seedNodes = seedNodes,
            onSeedLayoutChange = onSeedLayoutChange,
            onLoadSeedBody = { seed -> withContext(Dispatchers.IO) { SeedStore.loadBody(seed) } },
            onSaveSeedBody = { seed, bytes -> withContext(Dispatchers.IO) { SeedStore.saveBody(seed, bytes) } },
            seedsEnabled = seedsEnabled,
            onSeedsEnabledChange = onSeedsEnabledChange,
            openSeedPanelSignal = openSeedPanelRequests,
            toolPanelWidthRatio = toolPanelWidthRatio,
            onToolPanelWidthRatioChange = { toolPanelWidthRatio = it },
        )
    }

    // The paused-traffic inspector is its own OS window (ADR-0034), not a modal over the main window, so
    // captured traffic stays browsable while a hold is open. It is user-owned (ADR-0041): opened from the
    // Breakpoints panel or by a hold needing attention, and closed only by the user — resolving the last
    // hold leaves it open on the seed list, ready for the next run. Concurrent holds show as a queue the
    // user resolves in any order.
    if (breakpointWindowOpen) {
        // Seeded from — and persisted back to — its own geometry keys, so it reopens at the size/position
        // the user last left it (across app restarts), exactly like the main window.
        val breakpointWindowState = rememberWindowState(
            size = WindowStateStore.Breakpoint.loadSize(DefaultBreakpointWindowSize),
            position = WindowStateStore.Breakpoint.loadPosition(),
        )
        // Persist continuously (crash/force-quit safe) and once more on dispose, so the final geometry is
        // never lost inside the debounce window when the user closes it.
        LaunchedEffect(breakpointWindowState) {
            snapshotFlow { Triple(breakpointWindowState.size, breakpointWindowState.position, breakpointWindowState.placement) }
                .filter { (_, _, placement) -> placement == WindowPlacement.Floating }
                .debounce(300.milliseconds)
                .collect { (size, position, _) -> WindowStateStore.Breakpoint.save(size, position) }
        }
        DisposableEffect(Unit) {
            onDispose {
                if (breakpointWindowState.placement == WindowPlacement.Floating) {
                    WindowStateStore.Breakpoint.save(breakpointWindowState.size, breakpointWindowState.position)
                }
            }
        }
        Window(
            // Closing the window abandons any hold still open in it, so release them the safe way —
            // proceed each with its original bytes, the same fail-open as a desktop disconnect
            // (ADR-0027) — rather than aborting the app's calls or leaving devices hanging.
            onCloseRequest = {
                scope.launch {
                    pausedFlows.forEach { engine.resumeHold(it.correlationId, null, null) }
                }
                breakpointWindowOpen = false
            },
            state = breakpointWindowState,
            title = if (pausedFlows.size > 1) "Breakpoints (${pausedFlows.size})" else "Breakpoint",
            icon = appIconPainter,
            onPreviewKeyEvent = onScaleKeyEvent,
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(
                    MinBreakpointWindowSize.width.value.toInt(),
                    MinBreakpointWindowSize.height.value.toInt(),
                )
            }
            // Raise on every request, including the first composition — a window opened on demand should
            // land in front of whatever the user was looking at.
            LaunchedEffect(raiseBreakpointWindow) {
                window.toFront()
                window.requestFocus()
            }
            WailoBreakpointWindowContent(
                pausedFlows = pausedFlows,
                seeds = seedQueue,
                onFillSeeds = onFillSeeds,
                onClearSeeds = { scope.launch { engine.clearSeedQueue() } },
                onLoadSeedBody = { seed -> withContext(Dispatchers.IO) { SeedStore.loadBody(seed) } },
                onBringToFront = { raiseBreakpointWindow += 1 },
                darkTheme = darkTheme,
                textScale = textScale,
                onResumeBreakpoint = { correlationId, editedRequest, editedResponse ->
                    scope.launch { engine.resumeHold(correlationId, editedRequest, editedResponse) }
                },
                onAbortBreakpoint = { correlationId ->
                    scope.launch { engine.abortHold(correlationId) }
                },
            )
        }
    }
}

/**
 * The daemon's copy of the library: the flattened, in-order rules the panel shows, each carrying the
 * bytes it will answer with. Bodies ride along rather than being read at spend time, because the daemon
 * has to answer a hold with no Studio to ask (ADR-0067) — and a seed whose file has gone missing is sent
 * with [HostSeed.bodyAvailable] false, so it declines the hold instead of answering it empty.
 */
private fun List<SeedNode>.toHostSeeds(): List<HostSeed> = allRules().map { seed ->
    val body = SeedStore.loadBodyOrNull(seed)
    HostSeed(
        id = seed.id,
        // The whole library goes over, disabled seeds included — Fill is what filters, and a seed that
        // vanished from the daemon while switched off could not be listed or re-armed from anywhere else.
        // A seed inside an off group is inactive however its own switch reads, same as Map Local.
        enabled = isRuleActive(seed.id),
        urlPattern = seed.urlPattern,
        method = seed.method,
        statusCode = seed.statusCode,
        headers = seed.headers.map { Header(name = it.name, value_ = it.value) },
        body = body ?: ByteArray(0),
        bodyAvailable = body != null,
    )
}

private fun HostSeed.toSeedRuleDef() = SeedRuleDef(
    id = id,
    enabled = enabled,
    urlPattern = urlPattern,
    method = method,
    statusCode = statusCode,
    headers = headers.map { ResponseHeader(name = it.name, value = it.value_) },
)

/** A flat list of seeds from a daemon with no layout string — an MCP/CLI-authored library. */
private fun importSeeds(seeds: List<HostSeed>): List<SeedNode> =
    seeds.map { RuleNode(it.toSeedRuleDef()) }

private fun initialSeedNodes(
    stored: List<SeedNode>,
    daemonLayout: String,
    daemonSeeds: List<HostSeed>,
): List<SeedNode> = when {
    stored.isNotEmpty() -> stored
    daemonLayout.isNotBlank() -> SeedLayoutCodec.decode(daemonLayout)
    else -> importSeeds(daemonSeeds)
}

/**
 * Compares what the panel would publish with what the daemon reports, so an echo of our own push is not
 * mistaken for someone else's edit. Bodies are in it because a body-only edit is still an edit the
 * daemon has to serve — matching the Map Local signature (ADR-0061).
 */
private fun seedSignature(seeds: List<HostSeed>, enabled: Boolean): String =
    buildString {
        append(enabled)
        seeds.forEach { seed ->
            append('|')
            append(seed.id)
            append(':')
            append(seed.enabled)
            append(':')
            append(seed.urlPattern)
            append(':')
            append(seed.method)
            append(':')
            append(seed.statusCode)
            append(':')
            append(seed.headers.joinToString("\u0000") { "${it.name}\u0001${it.value_}" })
            append(':')
            append(seed.bodyAvailable)
            append(':')
            append(seed.bodyCopy().contentHashCode())
        }
    }

private fun CaptureFilter.toUiState() = CaptureFilterState(
    masterEnabled = allowlist_enabled || blocklist_enabled ||
        (allow_patterns.isEmpty() && block_patterns.isEmpty()),
    allowEnabled = allowlist_enabled,
    allowHosts = allow_patterns,
    blockEnabled = blocklist_enabled,
    blockHosts = block_patterns,
)

private fun captureFilterSignature(filter: CaptureFilter): String =
    "${filter.allowlist_enabled}:${filter.allow_patterns.joinToString("\u0000")}|" +
        "${filter.blocklist_enabled}:${filter.block_patterns.joinToString("\u0000")}"

private fun captureFilterSignature(filter: CaptureFilterState): String {
    val on = filter.masterEnabled
    return "${on && filter.allowEnabled}:${filter.allowHosts.joinToString("\u0000")}|" +
        "${on && filter.blockEnabled}:${filter.blockHosts.joinToString("\u0000")}"
}

private fun initialMapLocalNodes(
    stored: List<MapLocalNode>,
    daemonLayout: String,
    daemonRules: List<HostMapLocalRule>,
): List<MapLocalNode> = when {
    stored.isNotEmpty() -> stored
    daemonLayout.isNotBlank() -> MapLocalLayoutCodec.decode(daemonLayout)
    else -> importMapLocalRules(daemonRules)
}

private fun List<MapLocalNode>.toHostMapLocalRules(): List<HostMapLocalRule> = allRules().map { rule ->
    val body = MapLocalStore.loadServedBodyOrNull(rule)
    HostMapLocalRule(
        id = rule.id,
        name = rule.name,
        enabled = isRuleActive(rule.id),
        urlPattern = rule.urlPattern,
        methods = rule.method.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty),
        statusCode = rule.statusCode,
        headers = rule.headers.map { Header(name = it.name, value_ = it.value) },
        body = body ?: ByteArray(0),
        bodyAvailable = body != null,
    )
}

private fun importMapLocalRules(rules: List<HostMapLocalRule>): List<MapLocalNode> = rules.map { rule ->
    val definition = MapLocalRuleDef(
        id = rule.id,
        name = rule.name.ifBlank { rule.id },
        enabled = rule.enabled,
        urlPattern = rule.urlPattern,
        method = rule.methods.joinToString(","),
        statusCode = rule.statusCode,
        headers = rule.headers
            .filterNot { it.name.equals("Content-Length", ignoreCase = true) }
            .map { ResponseHeader(it.name, it.value_) },
        inline = true,
    )
    if (rule.bodyAvailable) MapLocalStore.saveInlineBody(definition, rule.bodyCopy())
    RuleNode(definition)
}

private fun mapRuleSignature(rules: List<HostMapLocalRule>, enabled: Boolean): String =
    buildString {
        append(enabled)
        rules.forEach { rule ->
            append('|')
            append(rule.id)
            append(':')
            // Included so a rename alone still counts as a change worth republishing to the daemon.
            append(rule.name)
            append(':')
            append(rule.enabled)
            append(':')
            append(rule.urlPattern)
            append(':')
            append(rule.methods.joinToString(","))
            append(':')
            append(rule.statusCode)
            append(':')
            append(rule.headers.joinToString("\u0000") { "${it.name}\u0001${it.value_}" })
            append(':')
            append(rule.bodyAvailable)
            append(':')
            append(rule.bodyCopy().contentHashCode())
        }
    }

private fun initialBreakpointNodes(
    stored: List<BreakpointNode>,
    daemonLayout: String,
    daemonRules: List<HostBreakpointRule>,
): List<BreakpointNode> = when {
    stored.isNotEmpty() -> stored
    daemonLayout.isNotBlank() -> BreakpointLayoutCodec.decode(daemonLayout)
    else -> importBreakpointRules(daemonRules)
}

private fun List<BreakpointNode>.toHostBreakpointRules(): List<HostBreakpointRule> = allRules().map { rule ->
    HostBreakpointRule(
        id = rule.id,
        enabled = isRuleActive(rule.id),
        urlPattern = rule.urlPattern,
        methods = rule.method.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty),
        onRequest = rule.onRequest,
        onResponse = rule.onResponse,
    )
}

private fun importBreakpointRules(rules: List<HostBreakpointRule>): List<BreakpointNode> = rules.map { rule ->
    RuleNode(
        BreakpointRuleDef(
            id = rule.id,
            enabled = rule.enabled,
            urlPattern = rule.urlPattern,
            method = rule.methods.joinToString(","),
            onRequest = rule.onRequest,
            onResponse = rule.onResponse,
        ),
    )
}

private fun breakpointRuleSignature(rules: List<HostBreakpointRule>, enabled: Boolean): String =
    buildString {
        append(enabled)
        rules.forEach { rule ->
            append('|')
            append(rule.id)
            append(':')
            append(rule.enabled)
            append(':')
            append(rule.urlPattern)
            append(':')
            append(rule.methods.joinToString(","))
            append(':')
            append(rule.onRequest)
            append(':')
            append(rule.onResponse)
        }
    }

private fun mergeDevices(
    connected: List<ConnectedDevice>,
    attachedUsb: List<UsbDeviceInfo>,
    attachedAdb: List<AdbDeviceInfo>,
): List<DeviceInfo> {
    val connectedById = connected.associateBy(ConnectedDevice::connectionId)
    val usbRows = attachedUsb.map { usb ->
        val session = connectedById["usb:${usb.udid}"]
        DeviceInfo(
            id = "usb:${usb.udid}",
            name = session?.deviceName ?: "iOS device",
            appId = session?.appId,
            platform = session?.platform ?: "ios",
            transport = DeviceTransportKind.USB,
            status = usb.status.toSharedStatus(),
            detail = abbreviatedUdid(usb.udid),
            error = usb.error,
        )
    }

    // An `adb reverse` session arrives on the ordinary capture server as an anonymous loopback peer:
    // nothing on the wire ties it back to a serial, and nothing can. So a session is claimed by an adb
    // row only when there is exactly one of each — the one-developer-one-phone case, where "connected"
    // on the row is certain. With more of either, the tunnel rows and the session rows stand apart:
    // saying less is better than pairing the wrong phone with the wrong app.
    val tunnelled = connected.filter { it.loopback && it.platform.equals("android", ignoreCase = true) }
    val claimed = tunnelled.singleOrNull()?.takeIf { attachedAdb.size == 1 }
    val adbRows = attachedAdb.map { adb ->
        val session = claimed.takeIf {
            adb.status == AdbConnectionStatus.WAITING_FOR_APP ||
                adb.status == AdbConnectionStatus.FORWARDING
        }
        DeviceInfo(
            id = "adb:${adb.serial}",
            name = session?.deviceName ?: adb.name,
            appId = session?.appId,
            platform = "android",
            transport = DeviceTransportKind.ADB,
            status = if (session != null) DeviceConnectionStatus.CONNECTED else adb.status.toSharedStatus(),
            detail = adb.serial,
            error = adb.error,
        )
    }

    val attachedIds = attachedUsb.mapTo(mutableSetOf()) { "usb:${it.udid}" }
    val connectedRows = connected
        .filter { it.transport == DeviceTransport.LAN || it.connectionId !in attachedIds }
        .filterNot { it.connectionId == claimed?.connectionId }
        .map { session ->
            DeviceInfo(
                id = session.connectionId,
                name = session.deviceName,
                appId = session.appId,
                platform = session.platform,
                transport = if (session.transport == DeviceTransport.USB) {
                    DeviceTransportKind.USB
                } else {
                    DeviceTransportKind.LAN
                },
                status = DeviceConnectionStatus.CONNECTED,
                detail = session.connectionId.removePrefix("usb:").takeIf { session.transport == DeviceTransport.USB },
            )
        }
    return (usbRows + adbRows + connectedRows).sortedWith(
        compareBy<DeviceInfo> { it.transport == DeviceTransportKind.LAN }.thenBy { it.name.lowercase() },
    )
}

private fun UsbConnectionStatus.toSharedStatus(): DeviceConnectionStatus = when (this) {
    UsbConnectionStatus.ATTACHED -> DeviceConnectionStatus.ATTACHED
    UsbConnectionStatus.CONNECTING -> DeviceConnectionStatus.CONNECTING
    UsbConnectionStatus.WAITING_FOR_APP -> DeviceConnectionStatus.WAITING_FOR_APP
    UsbConnectionStatus.CONNECTED -> DeviceConnectionStatus.CONNECTED
    UsbConnectionStatus.ERROR -> DeviceConnectionStatus.ERROR
}

private fun AdbConnectionStatus.toSharedStatus(): DeviceConnectionStatus = when (this) {
    AdbConnectionStatus.UNAUTHORIZED -> DeviceConnectionStatus.UNAUTHORIZED
    AdbConnectionStatus.FORWARDING -> DeviceConnectionStatus.CONNECTING
    AdbConnectionStatus.WAITING_FOR_APP -> DeviceConnectionStatus.WAITING_FOR_APP
    AdbConnectionStatus.CONNECTED -> DeviceConnectionStatus.CONNECTED
    AdbConnectionStatus.ERROR -> DeviceConnectionStatus.ERROR
}

private fun abbreviatedUdid(udid: String): String =
    if (udid.length > 16) "${udid.take(8)}…${udid.takeLast(6)}" else udid

// "In use" is the overwhelmingly common cause, but a privileged port (< 1024 without root) fails the same
// way, so the wording points at the class of problem rather than naming a cause it can't actually confirm.
private fun portUnavailable(port: Int) = "Port $port is unavailable — another app may be using it."

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
