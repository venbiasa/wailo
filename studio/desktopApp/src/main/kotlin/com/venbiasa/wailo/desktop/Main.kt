package com.venbiasa.wailo.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.venbiasa.wailo.engine.MapLocalBodyProvider
import com.venbiasa.wailo.engine.ConnectedDevice
import com.venbiasa.wailo.engine.DeviceTransport
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.engine.pairing.InMemoryPairingKeyStore
import com.venbiasa.wailo.engine.pairing.PairingCode
import com.venbiasa.wailo.engine.pairing.PairingManager
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.shared.BreakpointNode
import com.venbiasa.wailo.shared.CaptureFilterState
import com.venbiasa.wailo.shared.DeviceConnectionStatus
import com.venbiasa.wailo.shared.DeviceInfo
import com.venbiasa.wailo.shared.DeviceTransportKind
import com.venbiasa.wailo.shared.FlowEntry
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
import com.venbiasa.wailo.shared.SeedNode
import com.venbiasa.wailo.shared.SeedRuleDef
import com.venbiasa.wailo.shared.WailoApp
import com.venbiasa.wailo.shared.WailoBreakpointWindowContent
import com.venbiasa.wailo.shared.consume
import com.venbiasa.wailo.shared.firstMatch
import com.venbiasa.wailo.shared.rulesForMatch
import com.venbiasa.wailo.shared.theme.TextScale
import com.venbiasa.wailo.desktop.pairing.KeychainPairingKeyStore
import com.venbiasa.wailo.desktop.pairing.PairingQr
import com.venbiasa.wailo.desktop.usb.UsbConnectionStatus
import com.venbiasa.wailo.desktop.usb.UsbDeviceManager
import com.venbiasa.wailo.desktop.usb.UsbDeviceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
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

fun main() = runWailo()

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
private fun runWailo() = application {
    // Seeded from the last port the user chose, so a device pointed at a non-default port keeps working
    // across restarts. The engine is built once and moved in place by [applyPort] — rebuilding it to
    // change a port would discard the captured traffic and every rule snapshot it holds.
    // The pairing identity has to outlive the process — every device pins this public key, so a new one
    // each launch would silently un-pair all of them. On a host with no Keychain the engine keeps its
    // in-memory default: loopback and USB still work, and those are the paths that never needed a key.
    val engine = remember {
        WailoEngine(
            port = PortStore.load(),
            maxRetained = MaxRetainedStore.load(),
            pairings = if (KeychainPairingKeyStore.isSupported) {
                PairingManager(KeychainPairingKeyStore())
            } else {
                PairingManager(InMemoryPairingKeyStore())
            },
            requirePairing = RequirePairingStore.load(),
        )
    }
    val rows by engine.exchanges.collectAsState()
    val capturing by engine.capturing.collectAsState()
    val connectedDevices by engine.connectedDevices.collectAsState()
    val usbManager = remember(engine) { UsbDeviceManager(engine, UsbPortStore.load()) }
    val usbDevices by usbManager.devices.collectAsState()
    val usbPort by usbManager.devicePort.collectAsState()
    DisposableEffect(usbManager) {
        onDispose { usbManager.close() }
    }
    val devices = remember(connectedDevices, usbDevices) {
        mergeDevices(connectedDevices, usbDevices)
    }

    // An engine that is replaced without being stopped keeps its socket, and the port it strands is the
    // one its replacement then fails to bind — the app coming up "not listening" against itself. That is
    // routine under hot reload, where this composition is rebuilt and the `remember` above re-runs.
    DisposableEffect(engine) {
        onDispose { engine.stop() }
    }

    // Where the server actually is, versus where it was asked to be. A bind can fail at launch as well as
    // on a change — the saved port may have been taken since it was chosen — and a server that silently
    // isn't listening is the one failure a user can't diagnose from the UI, so it's surfaced in both the
    // settings panel ([portError]) and the top bar ([listening]).
    var listenPort by remember { mutableStateOf(engine.port) }
    val listening by engine.listening.collectAsState()
    var portError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (!engine.start()) portError = portUnavailable(engine.port)
    }
    // Retaking the port after whatever was squatting on it is gone. The engine recovers a socket that died
    // under it by itself, so this only exists for the case it can't fix: a port it never got.
    val retryListen: suspend () -> Unit = {
        portError = if (engine.restart()) null else portUnavailable(engine.port)
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
            // The engine is the authority on where it ended up: a rejected port rolls back to the last one
            // that worked, so read it back rather than assuming the change took.
            listenPort = engine.port
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
            usbManager.setDevicePort(next)
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
            engine.setMaxRetained(next)
            MaxRetainedStore.save(next)
        } else {
            maxRetainedError = "Must be between ${WailoEngine.RETAINED_RANGE.first} and " +
                "${WailoEngine.RETAINED_RANGE.last} requests."
        }
    }

    // The address devices should dial. The server binds every interface; we surface the host's LAN
    // IPv4 (not the wildcard) so a physical device knows where to point, falling back to localhost. The
    // engine re-resolves it as the host moves between networks, so a laptop woken somewhere else shows
    // where it actually is rather than where it was — including in the pairing QR below.
    val lanAddress by engine.lanAddress.collectAsState()
    val listenAddress = "$lanAddress:$listenPort"

    // --- Wi-Fi pairing (ADR-0039) ---------------------------------------------------------------
    // The engine owns the keys; the host owns the QR (ZXing is a desktop dependency, and `shared` has
    // no encoder) and the countdown, since `shared` is stateless and has no clock in commonMain.
    val pairedDevices by engine.pairings.devices.collectAsState()
    val pairingOffer by engine.pairings.offer.collectAsState()
    val refusedDevices by engine.refusedDevices.collectAsState()
    val suspectedClones by engine.suspectedClones.collectAsState()
    val requirePairing by engine.requirePairing.collectAsState()
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
        engine.pairings.cancelPairing()
    }
    val identity by engine.pairings.identity.collectAsState()
    val offerQr = remember(pairingOffer, identity, lanAddress, listenPort) {
        pairingOffer?.let {
            PairingQr.render(it.qrPayload(identity.studioId, identity.publicKey, lanAddress, listenPort))
        }
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
        supported = KeychainPairingKeyStore.isSupported,
        requirePairing = requirePairing,
        studioId = identity.studioId,
    )
    val onPairingAction: (PairingAction) -> Unit = { action ->
        when (action) {
            PairingAction.Begin -> engine.pairings.beginPairing()
            PairingAction.Cancel -> engine.pairings.cancelPairing()
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
    var captureFilter by remember { mutableStateOf(CaptureFilterStore.load()) }
    val onCaptureFilterChange = { next: CaptureFilterState ->
        captureFilter = next
        CaptureFilterStore.save(next)
    }
    // Push the filter on first composition and every change; the engine re-pushes to any device that
    // hasn't acked it (like the Map Local rules below). Devices apply the newest snapshot they receive.
    // The feature master sits above both lists (ADR-0030): while it's off, neither list is armed — so
    // devices capture everything — yet each list keeps its own hosts and armed state, ready for when the
    // master flips back on. It gates only what's pushed, never the persisted filter.
    LaunchedEffect(captureFilter) {
        val on = captureFilter.masterEnabled
        engine.updateCaptureFilter(
            allowlistEnabled = on && captureFilter.allowEnabled,
            allowPatterns = captureFilter.allowHosts,
            blocklistEnabled = on && captureFilter.blockEnabled,
            blockPatterns = captureFilter.blockHosts,
        )
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
    // Map Local's feature master (ADR-0030): host-owned and persisted like the layout. Off gates what's
    // pushed (see below) — the saved layout is untouched, so flipping it back on restores every rule.
    var mapLocalEnabled by remember { mutableStateOf(MapLocalStore.loadEnabled()) }
    val onMapLocalEnabledChange = { next: Boolean ->
        mapLocalEnabled = next
        MapLocalStore.saveEnabled(next)
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
    // acked it. Group toggles/reorders change which rules are active and in what order (ADR-0026). The
    // feature master gates this without touching the saved layout (ADR-0030): off pushes no rules and
    // empties the body-resolution snapshot too, so an in-flight match can't be served a local body.
    LaunchedEffect(mapLocalNodes, mapLocalEnabled) {
        val active = if (mapLocalEnabled) mapLocalNodes else emptyList()
        ruleDefsRef.set(active)
        engine.updateRules(compileRules(active))
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
    // Breakpoints' feature master (ADR-0030), mirroring Map Local: off pushes no rules (so nothing pauses)
    // while the saved layout stays intact for when it flips back on.
    var breakpointsEnabled by remember { mutableStateOf(BreakpointStore.loadEnabled()) }
    val onBreakpointsEnabledChange = { next: Boolean ->
        breakpointsEnabled = next
        BreakpointStore.saveEnabled(next)
    }
    LaunchedEffect(breakpointNodes, breakpointsEnabled) {
        engine.updateBreakpointRules(
            compileBreakpointRules(if (breakpointsEnabled) breakpointNodes else emptyList()),
        )
    }

    // Seed layout: host-owned and persisted like the two above. Seeds never reach a device — they are
    // spent here, on the desktop, to answer a hold — so unlike Map Local and breakpoints there is nothing
    // to compile or push (ADR-0041).
    var seedNodes by remember { mutableStateOf(SeedStore.load()) }
    val onSeedLayoutChange = { next: List<SeedNode> ->
        val prev = seedNodes
        seedNodes = next
        SeedStore.reconcileRemovedBodies(prev, next)
        SeedStore.save(next)
    }
    var seedsEnabled by remember { mutableStateOf(SeedStore.loadEnabled()) }
    val onSeedsEnabledChange = { next: Boolean ->
        seedsEnabled = next
        SeedStore.saveEnabled(next)
    }

    // The armed seed queue: what Fill loaded, minus whatever has been spent. Session state on purpose —
    // it outlives closing and reopening the breakpoint window (so a queue armed for a flow isn't lost to a
    // stray close) but not a restart, since a half-spent queue is a snapshot of a run in progress, not a
    // preference (ADR-0041).
    var seedQueue by remember { mutableStateOf<List<SeedRuleDef>>(emptyList()) }
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

    // Triage each hold once, on arrival: a response-phase hold that a queued seed matches is answered
    // here and the seed spent; everything else — a request-phase hold, or a response with no matching
    // seed — needs a human, so the window opens. Only *automatic* matching is arrival-only; Fill sweeps
    // what's already waiting, because that one is an explicit ask (ADR-0044).
    val triaged = remember { mutableSetOf<String>() }
    // Keyed on Unit and fed through a State, rather than keyed on `pausedFlows`: a hold is marked seen
    // before its body read, so a re-key mid-decision (which a *second* hold arriving would cause) would
    // cancel the first hold's triage after it had been marked — leaving a device blocked on a hold that
    // is never resolved and never surfaced. `collect`, not `collectLatest`, for the same reason.
    val currentPausedFlows = rememberUpdatedState(pausedFlows)
    LaunchedEffect(Unit) {
        snapshotFlow { currentPausedFlows.value }.collect { holds ->
            holds.forEach { hold ->
                if (!triaged.add(hold.correlationId)) return@forEach
                val spent = if (seedsEnabled) spendSeedOn(engine, seedQueue, hold) else null
                if (spent != null) seedQueue = spent else breakpointWindowOpen = true
            }
            // Forget resolved holds so the set can't grow unbounded across a long session.
            triaged.retainAll(holds.mapTo(mutableSetOf()) { it.correlationId })
        }
    }

    // Fill arms the queue from the enabled seeds — flattened out of their groups, in layout order, "don't
    // bring the group, keep the order" — and then spends it against whatever is already waiting. Arming
    // late is the normal case: you notice a hold sitting there and only then load the seeds for it, and a
    // queue that arrives a moment too late to be useful is a queue you'd have to re-trigger the traffic
    // for (ADR-0044). Re-filling replaces the queue, which is how a partly-spent sequence is reset mid-run.
    val onFillSeeds = {
        val waiting = pausedFlows
        scope.launch {
            seedQueue = if (seedsEnabled) seedNodes.rulesForMatch() else emptyList()
            // Read the queue back each time rather than folding a local copy: the sweep suspends on each
            // seed's body read, and a hold arriving in that gap is triaged against the same queue.
            waiting.forEach { hold -> spendSeedOn(engine, seedQueue, hold)?.let { seedQueue = it } }
        }
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

    Window(
        // Capture the final geometry on close too, in case the last move/resize landed inside the
        // debounce window and never flushed.
        onCloseRequest = {
            if (windowState.placement == WindowPlacement.Floating) {
                WindowStateStore.Main.save(windowState.size, windowState.position)
            }
            exitApplication()
        },
        state = windowState,
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
            usbSupported = usbManager.supported,
            usbPort = usbPort,
            usbPortError = usbPortError,
            onApplyUsbPort = applyUsbPort,
            maxRetained = maxRetained,
            maxRetainedError = maxRetainedError,
            onApplyMaxRetained = applyMaxRetained,
            pairing = pairingState,
            onPairingAction = onPairingAction,
            capturing = capturing,
            onToggleCapture = { engine.setCapturing(!capturing) },
            onClear = engine::clear,
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
                pausedFlows.forEach { engine.resumeBreakpoint(it.correlationId, null, null) }
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
                onClearSeeds = { seedQueue = emptyList() },
                onLoadSeedBody = { seed -> withContext(Dispatchers.IO) { SeedStore.loadBody(seed) } },
                onBringToFront = { raiseBreakpointWindow += 1 },
                darkTheme = darkTheme,
                textScale = textScale,
                onResumeBreakpoint = { correlationId, editedRequest, editedResponse ->
                    engine.resumeBreakpoint(correlationId, editedRequest, editedResponse)
                },
                onAbortBreakpoint = { correlationId -> engine.abortBreakpoint(correlationId) },
            )
        }
    }
}

/**
 * Answers [hold] with the first seed in [queue] that matches it and returns the queue minus the seed it
 * spent, or null when nothing answered — which leaves both the hold and the queue exactly as they were.
 * Shared by the arrival triage and Fill's sweep so the two can't drift on what a seed is allowed to answer
 * (ADR-0041/0044).
 *
 * Three ways to answer nothing, all of them a hold the user has to take: a request-phase hold, since the
 * wire honours an edited response only on a RESPONSE-phase hit; no seed matching the URL/method; or a seed
 * whose body file has gone missing, where resolving the hold with an empty body would be a silent, wrong
 * success.
 */
private suspend fun spendSeedOn(
    engine: WailoEngine,
    queue: List<SeedRuleDef>,
    hold: PausedFlow,
): List<SeedRuleDef>? {
    if (hold.phase != BreakpointPhase.BREAKPOINT_PHASE_RESPONSE) return null
    val seed = queue.firstMatch(hold.request?.url.orEmpty(), hold.request?.method.orEmpty()) ?: return null
    val response = withContext(Dispatchers.IO) { SeedStore.seedResponse(seed) } ?: return null
    engine.resumeBreakpoint(hold.correlationId, null, response)
    return queue.consume(seed)
}

private fun mergeDevices(
    connected: List<ConnectedDevice>,
    attachedUsb: List<UsbDeviceState>,
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
    val attachedIds = attachedUsb.mapTo(mutableSetOf()) { "usb:${it.udid}" }
    val connectedRows = connected
        .filter { it.transport == DeviceTransport.LAN || it.connectionId !in attachedIds }
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
    return (usbRows + connectedRows).sortedWith(
        compareBy<DeviceInfo> { it.transport != DeviceTransportKind.USB }.thenBy { it.name.lowercase() },
    )
}

private fun UsbConnectionStatus.toSharedStatus(): DeviceConnectionStatus = when (this) {
    UsbConnectionStatus.ATTACHED -> DeviceConnectionStatus.ATTACHED
    UsbConnectionStatus.CONNECTING -> DeviceConnectionStatus.CONNECTING
    UsbConnectionStatus.WAITING_FOR_APP -> DeviceConnectionStatus.WAITING_FOR_APP
    UsbConnectionStatus.CONNECTED -> DeviceConnectionStatus.CONNECTED
    UsbConnectionStatus.ERROR -> DeviceConnectionStatus.ERROR
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
