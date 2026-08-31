package com.venbiasa.wailo.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.venbiasa.wailo.daemon.AdbConnectionStatus
import com.venbiasa.wailo.daemon.AdbDeviceInfo
import com.venbiasa.wailo.daemon.CLIENT_KIND_STUDIO
import com.venbiasa.wailo.daemon.DaemonClient
import com.venbiasa.wailo.daemon.DaemonLauncher
import com.venbiasa.wailo.daemon.DaemonRuleGroup
import com.venbiasa.wailo.daemon.DaemonRuleNode
import com.venbiasa.wailo.daemon.ProxyCertificate
import com.venbiasa.wailo.daemon.RULE_FAMILY_MAP_LOCAL
import com.venbiasa.wailo.daemon.RULE_FAMILY_SEEDS
import com.venbiasa.wailo.daemon.bodyDigest
import com.venbiasa.wailo.daemon.UsbConnectionStatus
import com.venbiasa.wailo.daemon.UsbDeviceInfo
import com.venbiasa.wailo.engine.BodyRef
import com.venbiasa.wailo.engine.CaptureSource
import com.venbiasa.wailo.engine.ConnectedDevice
import com.venbiasa.wailo.engine.DeviceTransport
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.engine.pairing.PairingCode
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.HostSeed
import com.venbiasa.wailo.protocol.CaptureFilter
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.shared.BreakpointNode
import com.venbiasa.wailo.shared.BreakpointRuleDef
import com.venbiasa.wailo.shared.CaptureFilterState
import com.venbiasa.wailo.shared.DeviceConnectionStatus
import com.venbiasa.wailo.shared.DeviceInfo
import com.venbiasa.wailo.shared.DeviceTransportKind
import com.venbiasa.wailo.shared.BodyHandle
import com.venbiasa.wailo.shared.BodyLoader
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.GroupNode
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.PairedDeviceInfo
import com.venbiasa.wailo.shared.PairingAction
import com.venbiasa.wailo.shared.PairingOfferInfo
import com.venbiasa.wailo.shared.PairingRefusal
import com.venbiasa.wailo.shared.PairingState
import com.venbiasa.wailo.shared.PausedFlow
import com.venbiasa.wailo.shared.PickedFile
import com.venbiasa.wailo.shared.ProxySetupAction
import com.venbiasa.wailo.shared.ProxyState
import com.venbiasa.wailo.shared.RuleArchiveCodec
import com.venbiasa.wailo.shared.RuleGroup
import com.venbiasa.wailo.shared.RuleNode
import com.venbiasa.wailo.shared.ResponseHeader
import com.venbiasa.wailo.shared.SeedNode
import com.venbiasa.wailo.shared.SeedRuleDef
import com.venbiasa.wailo.shared.WailoApp
import com.venbiasa.wailo.shared.WailoBreakpointWindowContent
import com.venbiasa.wailo.shared.WailoCompareWindowContent
import com.venbiasa.wailo.shared.allRules
import com.venbiasa.wailo.shared.theme.TextScale
import com.venbiasa.wailo.shared.ui.StickyScopeRows
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
import java.awt.Desktop
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Taskbar
import java.awt.image.BufferedImage
import java.io.File
import java.io.FilenameFilter
import java.time.LocalDate
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
        sweepLegacyStudioState()
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

// The compare window (ADR-0079) opens wider still: it is two monospace columns side by side, and a body
// line that has to wrap or scroll horizontally defeats the point of reading a diff. The floor is the
// narrowest at which both panes still show a useful span of each line.
private val DefaultCompareWindowSize = DpSize(1120.dp, 720.dp)
private val MinCompareWindowSize = DpSize(640.dp, 400.dp)

// Comparisons opened while another is already up cascade off the remembered origin instead of landing
// exactly on it: a diff that opens covering its predecessor is indistinguishable from having replaced it,
// which is the one thing a second window exists to avoid. Capped so a long run of them stays on screen.
private val CompareCascadeStep = 28.dp
private const val MaxCompareCascade = 6

// debounce (used below to coalesce window resize/move writes) is still a coroutines preview API.
@OptIn(FlowPreview::class)
// The engine's handle, remapped for the viewer at the same boundary CapturedExchange is: `shared` is a
// sibling of `engine`, not a consumer of it.
private fun BodyRef.toHandle() = BodyHandle(id = id, size = size)

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
                engine.rebind(next) -> null
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
        } else {
            maxRetainedError = "Must be between ${WailoEngine.RETAINED_RANGE.first} and " +
                "${WailoEngine.RETAINED_RANGE.last} requests."
        }
    }

    // How deep the editors' sticky header pins. Held here rather than in the engine because nothing about
    // capture depends on it, and range is all there is to validate. Every open editor reads it from a
    // CompositionLocal, so a new depth lands in all of them on the next frame rather than on the next body.
    var stickyScopeRows by remember { mutableStateOf(StickyScopeRowsStore.load()) }
    var stickyScopeRowsError by remember { mutableStateOf<String?>(null) }
    val applyStickyScopeRows: (Int) -> Unit = { next ->
        if (next in StickyScopeRows.Min..StickyScopeRows.Max) {
            stickyScopeRowsError = null
            stickyScopeRows = next
            StickyScopeRowsStore.save(next)
        } else {
            stickyScopeRowsError = "Must be between ${StickyScopeRows.Min} and ${StickyScopeRows.Max} lines."
        }
    }

    // Whether AI tools may reach the capture, and whether what they read is stripped of credentials
    // (ADR-0059). The daemon owns and persists both, because the gate has to hold for an MCP session
    // running with no Studio open — this window only shows and flips them.
    val mcpAccess by engine.mcpAccess.collectAsState()
    val mcpRedactSecrets by engine.mcpRedactSecrets.collectAsState()

    // The bundled proxy, likewise daemon-owned (ADR-0070): a browser pointed at it must not lose its
    // network because this window closed, so Studio only asks. The daemon answers with the state it
    // actually reached, which is how a refused port gets reported instead of a switch that lies.
    val daemonProxy by engine.proxy.collectAsState()
    // Where the exported root landed, or why it didn't. Held here rather than in the daemon's status: the
    // file is this machine's, and writing one is an event, not a state the daemon can keep answering with.
    var certificateNotice by remember { mutableStateOf("") }
    val proxy = ProxyState(
        running = daemonProxy.running,
        port = daemonProxy.port,
        connections = daemonProxy.connections,
        exchanges = daemonProxy.exchanges,
        error = daemonProxy.error,
        lan = daemonProxy.lan,
        lanAddress = daemonProxy.lanAddress,
        systemProxy = daemonProxy.systemProxy,
        systemProxySupported = daemonProxy.systemProxySupported,
        chainedTo = daemonProxy.chainedTo,
        caInstalled = daemonProxy.caInstalled,
        caFingerprint = daemonProxy.caFingerprint,
        decryptHosts = daemonProxy.decryptHosts,
        certificateNotice = certificateNotice,
    )
    val setProxyEnabled: (Boolean) -> Unit = { enabled -> scope.launch { engine.setProxyEnabled(enabled) } }
    val applyProxyPort: (Int) -> Unit = { port -> scope.launch { engine.setProxyPort(port) } }
    val proxySetupAction: (ProxySetupAction) -> Unit = { action ->
        scope.launch {
            when (action) {
                is ProxySetupAction.SetLan -> engine.setProxyLan(action.enabled)
                is ProxySetupAction.SetSystemProxy -> engine.setSystemProxy(action.enabled)
                is ProxySetupAction.SetDecryptHosts -> engine.setProxyDecryptHosts(action.hosts)
                ProxySetupAction.InstallCertificate ->
                    certificateNotice = exportCertificate(engine.proxyCertificate())
                ProxySetupAction.RotateCertificate ->
                    certificateNotice = exportCertificate(engine.rotateProxyCertificate())
                ProxySetupAction.RemoveCertificate -> {
                    engine.removeProxyCertificate()
                    certificateNotice = ""
                }
            }
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
                is PairingAction.SetRequirePairing -> engine.setRequirePairing(action.enabled)
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
                requestBody = it.requestBody?.toHandle(),
                responseBody = it.responseBody?.toHandle(),
                viaProxy = it.source == CaptureSource.PROXY,
            )
        }
    }

    // Bodies stay on the daemon and come back a range at a time (ADR-0069), so the viewer is given the
    // means to fetch them rather than the bytes themselves.
    val bodyLoader = remember(engine) {
        BodyLoader { handle, offset, length ->
            engine.readBody(BodyRef(handle.id, handle.size), offset, length)
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

    // Bookmarked hosts: daemon state, so which hosts the user cares about is readable by an agent or a
    // CLI session with no window open (ADR-0084). One host per call, so two frontends bookmarking at once
    // do not overwrite each other. `shared` gets the list plus add/remove callbacks and stays stateless.
    val bookmarks by engine.bookmarkedHosts.collectAsState()
    val addBookmark = { host: String ->
        if (host.isNotBlank() && host !in bookmarks) {
            scope.launch { engine.setBookmarked(host, bookmarked = true) }
            Unit
        }
    }
    val removeBookmark = { host: String ->
        if (host in bookmarks) {
            scope.launch { engine.setBookmarked(host, bookmarked = false) }
            Unit
        }
    }

    // Capture filter: the allow/block host lists + each list's on/off switch that decide which traffic
    // devices capture and stream (ADR-0029). The daemon owns it, feature master included (ADR-0082), and
    // folds the master in only on the way to devices — so the lists come back armed exactly as authored.
    // Read straight off the daemon with no local copy beside it: a second copy needs a rule for which one
    // wins on launch, and every version of that rule mistook a deliberately empty filter for an
    // unconfigured one and published the local copy over it (ADR-0085). `shared` renders this and hands
    // back a whole new [CaptureFilterState] for any change, so a change is one write to the owner.
    val daemonCaptureFilter by engine.captureFilter.collectAsState()
    val daemonCaptureFilterEnabled by engine.captureFilterEnabled.collectAsState()
    val captureFilter = daemonCaptureFilter.toUiState(daemonCaptureFilterEnabled)
    // One call, master included: sent apart, the daemon reports the new lists beside the old master for as
    // long as the second call takes, and this would render that half-applied state back at the user
    // (ADR-0082). Edits are discrete acts — add a host, flip a switch — never a keystroke, so the
    // round-trip is not in the way of typing.
    val onCaptureFilterChange = { next: CaptureFilterState ->
        scope.launch {
            engine.updateCaptureFilter(
                allowlistEnabled = next.allowEnabled,
                allowPatterns = next.allowHosts,
                blocklistEnabled = next.blockEnabled,
                blockPatterns = next.blockHosts,
                enabled = next.masterEnabled,
            )
        }
        Unit
    }

    // Bodies an editor has saved but whose layout has not been published yet. The rule editors commit a
    // body and its row through two separate callbacks, so the bytes wait here for the publish that
    // follows and are dropped once the daemon has them. Not a second copy of anything: it holds only what
    // the daemon has not been told (ADR-0085), and only what is being changed (ADR-0086).
    val pendingBodies = remember { mutableStateMapOf<String, ByteArray>() }

    // Map Local layout (groups + rules, in priority order). The daemon owns it, grouping included
    // (ADR-0081), so a rule an agent files into a group is in that group here too and serves the same
    // bytes. Read straight off it with no local copy (ADR-0085); `shared` only renders it. A rule arrives
    // naming its body rather than carrying it, so the bytes are fetched per rule (ADR-0086).
    val daemonMapLocalNodes by engine.mapLocalNodes.collectAsState()
    val mapLocalEnabled by engine.mapLocalEnabled.collectAsState()
    val daemonMapRules = remember(daemonMapLocalNodes) {
        daemonMapLocalNodes.flatMap { it.rules }.associateBy { it.id }
    }
    val mapLocalNodes = remember(daemonMapLocalNodes) { daemonMapLocalNodes.toMapLocalNodes() }
    val mapLocalBody: suspend (String) -> ByteArray = { id ->
        pendingBodies[id] ?: engine.readRuleBody(RULE_FAMILY_MAP_LOCAL, id)
    }
    // A layout and no bytes: every rule the daemon already knows keeps the body it is holding, so
    // toggling or reordering costs the rows alone. Only what an editor just staged travels.
    val onLayoutChange = { next: List<MapLocalNode> ->
        scope.launch {
            engine.replaceMapLocalNodes(
                next.toDaemonMapLocalNodes(daemonMapRules),
                mapLocalEnabled,
                pendingBodies.staged(next.allRules().map { it.id }),
            )
        }
        Unit
    }
    // Map Local's feature master (ADR-0030). Off gates what the daemon pushes — the stored layout is
    // untouched, so flipping it back on restores every rule.
    val onMapLocalEnabledChange = { next: Boolean ->
        scope.launch { engine.setMapLocalEnabled(next) }
        Unit
    }

    // Breakpoint layout: owned by the daemon, grouping included, exactly as Map Local above (ADR-0081),
    // and read straight off it with no local copy (ADR-0085). A reorder commits once on drop and a toggle
    // is one click, so writing through on every change costs one round-trip per deliberate act.
    val daemonBreakpointNodes by engine.breakpointNodes.collectAsState()
    val breakpointsEnabled by engine.breakpointsEnabled.collectAsState()
    val breakpointNodes = remember(daemonBreakpointNodes) { daemonBreakpointNodes.toBreakpointNodes() }
    val onBreakpointLayoutChange = { next: List<BreakpointNode> ->
        scope.launch {
            engine.replaceBreakpointNodes(next.toDaemonBreakpointNodes(), breakpointsEnabled)
        }
        Unit
    }
    // Breakpoints' feature master (ADR-0030), mirroring Map Local: off pushes no rules (so nothing pauses)
    // while the saved layout stays intact for when it flips back on.
    val onBreakpointsEnabledChange = { next: Boolean ->
        scope.launch { engine.setBreakpointsEnabled(next) }
        Unit
    }

    // Seed layout: owned by the daemon, which holds the bytes too — a seed is spent by whoever owns the
    // hold, and that is the daemon whether or not this window exists (ADR-0067). Read and written exactly
    // like Map Local above; unlike it, nothing reaches a device.
    val daemonSeedNodes by engine.seedNodes.collectAsState()
    val seedsEnabled by engine.seedsEnabled.collectAsState()
    val daemonSeedRules = remember(daemonSeedNodes) {
        daemonSeedNodes.flatMap { it.rules }.associateBy { it.id }
    }
    val seedNodes = remember(daemonSeedNodes) { daemonSeedNodes.toSeedNodes() }
    val seedBody: suspend (String) -> ByteArray = { id ->
        pendingBodies[id] ?: engine.readRuleBody(RULE_FAMILY_SEEDS, id)
    }
    val onSeedLayoutChange = { next: List<SeedNode> ->
        scope.launch {
            engine.replaceSeedNodes(
                next.toDaemonSeedNodes(daemonSeedRules),
                seedsEnabled,
                pendingBodies.staged(next.allRules().map { it.id }),
            )
        }
        Unit
    }
    val onSeedsEnabledChange = { next: Boolean ->
        scope.launch { engine.setSeedsEnabled(next) }
        Unit
    }

    // A staged body is released only once the daemon reports that digest back, never when the publish
    // call returns. Dropping it any earlier leaves a frame in which the rule's bytes are neither here nor
    // acknowledged there, and an edit landing in it would publish a layout that unstages the save.
    LaunchedEffect(daemonMapRules, daemonSeedRules) {
        pendingBodies.keys
            .filter { id ->
                val published = daemonMapRules[id]?.bodyHash ?: daemonSeedRules[id]?.bodyHash
                published != null && published == bodyDigest(pendingBodies.getValue(id))
            }
            .forEach(pendingBodies::remove)
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
    // than in `shared` because it spans both layouts — the body is carried across as bytes staged for the
    // publish that follows. The seed is appended, so it takes the lowest priority and an existing armed
    // queue's order is undisturbed.
    val onSeedFromMapLocalRule = { rule: MapLocalRuleDef ->
        val seed = SeedRuleDef(
            id = SeedRuleDef.newId(),
            urlPattern = rule.urlPattern,
            method = rule.method,
            statusCode = rule.statusCode,
            headers = rule.headers,
        )
        // The source rule's bytes are the daemon's, so the copy is a fetch — and the layout is published
        // only once they are staged, or the new seed would land naming a body nothing had sent.
        scope.launch {
            pendingBodies[seed.id] = mapLocalBody(rule.id)
            onSeedLayoutChange(seedNodes + RuleNode(seed))
            openSeedPanelRequests += 1
        }
        Unit
    }

    // Rule export/import. One file carries every authored tool, so what a user keeps is one thing rather
    // than four that can drift apart. Both are host work end to end — the native dialog, the bytes, the
    // bodies — and hand the panel back the single line it shows.
    // Both take the owner frame rather than closing over it: `window` is the composition-local of the
    // FrameWindowScope down at the WailoApp call, which is below where this state lives.
    val onExportRules: suspend (Frame?) -> String = onExport@{ owner: Frame? ->
        val target = chooseArchiveFile(owner, save = true) ?: return@onExport ""
        // Every authored body is copied into the archive, so both halves stay off the UI thread.
        withContext(Dispatchers.IO) {
            writeArchive(
                target,
                buildArchive(
                    mapLocalNodes = mapLocalNodes,
                    mapLocalEnabled = mapLocalEnabled,
                    breakpointNodes = breakpointNodes,
                    breakpointsEnabled = breakpointsEnabled,
                    seedNodes = seedNodes,
                    seedsEnabled = seedsEnabled,
                    captureFilter = captureFilter,
                    mapLocalBody = mapLocalBody,
                    seedBody = seedBody,
                ),
            )
        }
    }
    val onImportRules: suspend (Frame?) -> String = onImport@{ owner: Frame? ->
        val source = chooseArchiveFile(owner, save = false) ?: return@onImport ""
        val result = withContext(Dispatchers.IO) {
            importArchive(source, mapLocalNodes, breakpointNodes, seedNodes, captureFilter)
        }
        // The archive's bodies are staged the same way the editor stages one, so the layout push below
        // carries them to the daemon through the one path that writes bodies.
        pendingBodies.putAll(result.bodies)
        // Adopted through each panel's own change handler rather than by assigning state, so the daemon
        // push runs exactly as it does for a hand edit.
        if (result.mapLocal != mapLocalNodes) onLayoutChange(result.mapLocal)
        if (result.breakpoints != breakpointNodes) onBreakpointLayoutChange(result.breakpoints)
        if (result.seeds != seedNodes) onSeedLayoutChange(result.seeds)
        if (result.captureFilter != captureFilter) onCaptureFilterChange(result.captureFilter)
        result.message
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

    // Every comparison the user has open, each getting a window of its own below (ADR-0091). Held here
    // rather than in the viewer because only the host can own an OS window — and pinning each pair is what
    // lets the list behind it stay fully usable: clicking through rows changes the selection, not any
    // comparison. Kept to rows that still exist, so clearing the capture closes their windows.
    var comparisons by remember { mutableStateOf<List<Comparison>>(emptyList()) }
    LaunchedEffect(entries) {
        comparisons = comparisons.withOnlyLive(entries.mapTo(mutableSetOf()) { it.id })
    }
    // One raise counter per comparison: re-issuing a pair is a no-op on the list above, so without this the
    // menu item would look dead once that window is buried — and it must be *that* window that comes up.
    val raiseComparison = remember { mutableStateMapOf<String, Int>() }
    val comparedIds = remember(comparisons) {
        comparisons.flatMapTo(mutableSetOf()) { listOf(it.left, it.right) }
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

    // The traffic table's column widths, host-owned and persisted the same way — a column dragged wide to
    // read long URLs is a layout set up once, not one to redo every launch. Stored as plain dp per column
    // and clamped by the table, which owns the floors, so this side never has to know them.
    var trafficColumnWidths by remember { mutableStateOf(ColumnWidthStore.load()) }
    LaunchedEffect(Unit) {
        snapshotFlow { trafficColumnWidths }
            .debounce(300.milliseconds)
            .collect { ColumnWidthStore.save(it) }
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
            bodyLoader = bodyLoader,
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
            stickyScopeRows = stickyScopeRows,
            stickyScopeRowsError = stickyScopeRowsError,
            onApplyStickyScopeRows = applyStickyScopeRows,
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
            onLoadMapLocalBody = { rule -> mapLocalBody(rule.id) },
            onSaveMapLocalBody = { rule, bytes -> pendingBodies[rule.id] = bytes },
            // `window` (the ComposeWindow, an AWT Frame) parents the native dialog so it's modal to the app.
            onPickMapLocalFile = { chooseMapLocalFile(window) },
            mapLocalEnabled = mapLocalEnabled,
            onMapLocalEnabledChange = onMapLocalEnabledChange,
            onSeedFromMapLocalRule = onSeedFromMapLocalRule,
            onExportRules = { onExportRules(window) },
            onImportRules = { onImportRules(window) },
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
            onLoadSeedBody = { seed -> seedBody(seed.id) },
            onSaveSeedBody = { seed, bytes -> pendingBodies[seed.id] = bytes },
            seedsEnabled = seedsEnabled,
            onSeedsEnabledChange = onSeedsEnabledChange,
            openSeedPanelSignal = openSeedPanelRequests,
            proxy = proxy,
            onProxyEnabledChange = setProxyEnabled,
            onApplyProxyPort = applyProxyPort,
            onProxySetupAction = proxySetupAction,
            toolPanelWidthRatio = toolPanelWidthRatio,
            onToolPanelWidthRatioChange = { toolPanelWidthRatio = it },
            trafficColumnWidths = trafficColumnWidths,
            onTrafficColumnWidthsChange = { trafficColumnWidths = it },
            comparedIds = comparedIds,
            onCompare = { (a, b) ->
                val comparison = Comparison(a, b)
                comparisons = comparisons.withOpened(comparison)
                raiseComparison[comparison.key] = (raiseComparison[comparison.key] ?: 0) + 1
            },
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
                onLoadSeedBody = { seed -> seedBody(seed.id) },
                onBringToFront = { raiseBreakpointWindow += 1 },
                darkTheme = darkTheme,
                textScale = textScale,
                stickyScopeRows = stickyScopeRows,
                onResumeBreakpoint = { correlationId, editedRequest, editedResponse ->
                    scope.launch { engine.resumeHold(correlationId, editedRequest, editedResponse) }
                },
                onAbortBreakpoint = { correlationId ->
                    scope.launch { engine.abortHold(correlationId) }
                },
            )
        }
    }

    // The diff is its own OS window (ADR-0079) rather than a mode of the detail panel: two monospace
    // columns under the traffic list had room to prove the feature worked and none to actually read it.
    // Detached, it also stops competing with the list for the same vertical space — the point of comparing
    // two rows is usually to keep hunting through the rest of them. One window per comparison (ADR-0091),
    // so reading a third row against a fourth keeps the diff that prompted it instead of spending it.
    comparisons.forEach { comparison ->
        key(comparison.key) {
            val left = entries.firstOrNull { it.id == comparison.left }
            val right = entries.firstOrNull { it.id == comparison.right }
            // A comparison whose rows have just gone is dropped by the prune above, which runs after this
            // composition — so skip the frame in between rather than opening a window on nothing.
            if (left != null && right != null) {
                // How far this one opened from the remembered origin, fixed for its lifetime: recomputing it
                // as siblings close would slide a window the user had already placed.
                val cascade = remember {
                    comparisons.indexOfFirst { it.key == comparison.key }.coerceIn(0, MaxCompareCascade)
                }
                val compareWindowState = rememberWindowState(
                    size = WindowStateStore.Compare.loadSize(DefaultCompareWindowSize),
                    // With nothing saved the position stays PlatformDefault, where the OS does its own
                    // cascading, so the offset is only ever applied to a real remembered origin.
                    position = when (val saved = WindowStateStore.Compare.loadPosition()) {
                        is WindowPosition.Absolute -> WindowPosition.Absolute(
                            saved.x + CompareCascadeStep * cascade,
                            saved.y + CompareCascadeStep * cascade,
                        )
                        else -> saved
                    },
                )
                // Only the window that opened at the origin writes geometry back. The others opened offset
                // from it, and saving that would walk the remembered origin down the screen, one comparison
                // at a time, until a diff opened off the edge of it.
                if (cascade == 0) {
                    LaunchedEffect(compareWindowState) {
                        snapshotFlow {
                            Triple(
                                compareWindowState.size,
                                compareWindowState.position,
                                compareWindowState.placement,
                            )
                        }
                            .filter { (_, _, placement) -> placement == WindowPlacement.Floating }
                            .debounce(300.milliseconds)
                            .collect { (size, position, _) -> WindowStateStore.Compare.save(size, position) }
                    }
                    DisposableEffect(Unit) {
                        onDispose {
                            if (compareWindowState.placement == WindowPlacement.Floating) {
                                WindowStateStore.Compare.save(compareWindowState.size, compareWindowState.position)
                            }
                        }
                    }
                }
                Window(
                    onCloseRequest = {
                        comparisons = comparisons.withClosed(comparison.key)
                        raiseComparison.remove(comparison.key)
                    },
                    state = compareWindowState,
                    title = "Compare",
                    icon = appIconPainter,
                    onPreviewKeyEvent = onScaleKeyEvent,
                ) {
                    LaunchedEffect(Unit) {
                        window.minimumSize = Dimension(
                            MinCompareWindowSize.width.value.toInt(),
                            MinCompareWindowSize.height.value.toInt(),
                        )
                    }
                    LaunchedEffect(raiseComparison[comparison.key]) {
                        window.toFront()
                        window.requestFocus()
                    }
                    WailoCompareWindowContent(
                        left = left,
                        right = right,
                        onSwap = { comparisons = comparisons.withSidesSwapped(comparison.key) },
                        darkTheme = darkTheme,
                        textScale = textScale,
                        bodyLoader = bodyLoader,
                    )
                }
            }
        }
    }
}

/**
 * The daemon's copy of the library: the in-order rules the panel shows, each naming the body it will
 * answer with. The bytes are the daemon's — it has to answer a hold with no Studio to ask (ADR-0067) —
 * so a publish repeats the reference it was given rather than sending the payload back (ADR-0086).
 * [known] is the daemon's own view of each seed, which is where that reference comes from; a seed it has
 * never heard of has none until the bodies beside this layout give it one.
 */
internal fun List<SeedNode>.toDaemonSeedNodes(
    known: Map<String, HostSeed>,
): List<DaemonRuleNode<HostSeed>> = map { node ->
    when (node) {
        is GroupNode -> DaemonRuleNode(node.group.toDaemonGroup(), node.rules.map { it.toHostSeed(known) })
        is RuleNode -> DaemonRuleNode(null, listOf(node.rule.toHostSeed(known)))
    }
}

private fun SeedRuleDef.toHostSeed(known: Map<String, HostSeed>) = HostSeed(
    id = id,
    // The whole library goes over, disabled seeds included — Fill is what filters, and a seed that
    // vanished from the daemon while switched off could not be listed or re-armed from anywhere else.
    enabled = enabled,
    urlPattern = urlPattern,
    method = method,
    statusCode = statusCode,
    headers = headers.map { Header(name = it.name, value_ = it.value) },
    bodySize = known[id]?.bodySize ?: 0,
    bodyHash = known[id]?.bodyHash.orEmpty(),
)

private fun HostSeed.toSeedRuleDef() = SeedRuleDef(
    id = id,
    enabled = enabled,
    urlPattern = urlPattern,
    method = method,
    statusCode = statusCode,
    headers = headers.map { ResponseHeader(name = it.name, value = it.value_) },
)

internal fun List<DaemonRuleNode<HostSeed>>.toSeedNodes(): List<SeedNode> = flatMap { node ->
    val definitions = node.rules.map { it.toSeedRuleDef() }
    node.group?.let { listOf(GroupNode(it.toRuleGroup(), definitions)) } ?: definitions.map { RuleNode(it) }
}

/**
 * The staged bodies that belong with a publish of [ids]. One waiting for the other panel's turn, or for
 * a rule that has since been deleted, stays put rather than riding along with a layout that never names
 * it — the daemon sweeps bodies no rule claims, so an unnamed one would be written and then collected.
 */
private fun Map<String, ByteArray>.staged(ids: List<String>): Map<String, ByteArray> {
    val named = ids.toSet()
    return filterKeys { it in named }
}

private fun RuleGroup.toDaemonGroup() = DaemonRuleGroup(id = id, name = name, enabled = enabled)

private fun DaemonRuleGroup.toRuleGroup() = RuleGroup(id = id, name = name, enabled = enabled)

private fun CaptureFilter.toUiState(masterEnabled: Boolean) = CaptureFilterState(
    masterEnabled = masterEnabled,
    allowEnabled = allowlist_enabled,
    allowHosts = allow_patterns,
    blockEnabled = blocklist_enabled,
    blockHosts = block_patterns,
)

/**
 * The layout as the daemon holds it (ADR-0081). A rule's *own* switch travels, not the resolved one: the
 * group's switch rides on the group beside it and the daemon folds the two together, so a rule inside a
 * group that is turned off still remembers being on.
 */
internal fun List<MapLocalNode>.toDaemonMapLocalNodes(
    known: Map<String, HostMapLocalRule>,
): List<DaemonRuleNode<HostMapLocalRule>> = map { node ->
    when (node) {
        is GroupNode -> DaemonRuleNode(node.group.toDaemonGroup(), node.rules.map { it.toHostRule(known) })
        is RuleNode -> DaemonRuleNode(null, listOf(node.rule.toHostRule(known)))
    }
}

private fun MapLocalRuleDef.toHostRule(known: Map<String, HostMapLocalRule>) = HostMapLocalRule(
    id = id,
    name = name,
    enabled = enabled,
    urlPattern = urlPattern,
    method = method.trim(),
    statusCode = statusCode,
    headers = headers.map { Header(name = it.name, value_ = it.value) },
    // The daemon's reference, echoed back rather than the bytes it stands for (ADR-0086).
    bodySize = known[id]?.bodySize ?: 0,
    bodyHash = known[id]?.bodyHash.orEmpty(),
)

/**
 * The daemon's layout as the panel shows it. Every rule reads as inline because that is now the only
 * thing a rule can be: the bytes are held by the daemon, so a path a legacy rule still carried was a
 * pointer to a file nothing had read since serving moved off this process (ADR-0085).
 */
internal fun List<DaemonRuleNode<HostMapLocalRule>>.toMapLocalNodes(): List<MapLocalNode> = flatMap { node ->
    val definitions = node.rules.map { rule ->
        MapLocalRuleDef(
            id = rule.id,
            name = rule.name.ifBlank { rule.id },
            enabled = rule.enabled,
            urlPattern = rule.urlPattern,
            method = rule.method,
            statusCode = rule.statusCode,
            headers = rule.headers
                .filterNot { it.name.equals("Content-Length", ignoreCase = true) }
                .map { ResponseHeader(it.name, it.value_) },
            inline = true,
        )
    }
    node.group?.let { listOf(GroupNode(it.toRuleGroup(), definitions)) } ?: definitions.map { RuleNode(it) }
}

internal fun List<BreakpointNode>.toDaemonBreakpointNodes(): List<DaemonRuleNode<HostBreakpointRule>> = map { node ->
    when (node) {
        is GroupNode -> DaemonRuleNode(node.group.toDaemonGroup(), node.rules.map { it.toHostRule() })
        is RuleNode -> DaemonRuleNode(null, listOf(node.rule.toHostRule()))
    }
}

private fun BreakpointRuleDef.toHostRule() = HostBreakpointRule(
    id = id,
    enabled = enabled,
    urlPattern = urlPattern,
    method = method.trim(),
    onRequest = onRequest,
    onResponse = onResponse,
)

internal fun List<DaemonRuleNode<HostBreakpointRule>>.toBreakpointNodes(): List<BreakpointNode> =
    flatMap { node ->
        val definitions = node.rules.map { rule ->
            BreakpointRuleDef(
                id = rule.id,
                enabled = rule.enabled,
                urlPattern = rule.urlPattern,
                method = rule.method,
                onRequest = rule.onRequest,
                onResponse = rule.onResponse,
            )
        }
        node.group?.let { listOf(GroupNode(it.toRuleGroup(), definitions)) }
            ?: definitions.map { RuleNode(it) }
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
    val file = awaitNativeFileDialog(
        owner = owner,
        title = "Choose body file",
        // Honored by the native macOS/Linux pickers to gray out non-body files; Windows ignores it and
        // shows everything, which is fine — any file can still be mapped.
        filter = FilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in BodyFileExtensions },
    ) ?: return null
    return withContext(Dispatchers.IO) {
        runCatching { PickedFile(file.readBytes(), guessContentType(file.name)) }.getOrNull()
    }
}

/**
 * The rule archive's save/open dialog. A save seeds a dated name so successive backups sit beside each
 * other instead of overwriting, and re-appends the extension if the user typed it away — the Open dialog
 * filters on it, so a file saved without one would be invisible to the import that wants it back.
 */
private suspend fun chooseArchiveFile(owner: Frame?, save: Boolean): File? {
    val extension = RuleArchiveCodec.FILE_EXTENSION
    val file = awaitNativeFileDialog(
        owner = owner,
        title = if (save) "Export rules" else "Import rules",
        mode = if (save) FileDialog.SAVE else FileDialog.LOAD,
        defaultFileName = if (save) "wailo-backup-${LocalDate.now()}.$extension" else null,
        filter = if (save) null else FilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) },
    ) ?: return null
    return if (save && !file.name.endsWith(".$extension", ignoreCase = true)) {
        File(file.parentFile, "${file.name}.$extension")
    } else {
        file
    }
}

/**
 * Puts the local root somewhere the user can act on it and, where the OS knows how, hands it to the tool
 * that installs certificates (Keychain Access on macOS).
 *
 * Studio writes the file rather than opening a save dialog because the next step is the user's — trusting
 * a root is a decision the OS must ask about, and it cannot until the certificate exists somewhere real.
 * Downloads, not a Wailo folder: it is where a browser would have put it, so it is where people look.
 * Returns the sentence the panel shows, since a silent write is indistinguishable from nothing happening.
 */
private suspend fun exportCertificate(certificate: ProxyCertificate): String = withContext(Dispatchers.IO) {
    certificate.error?.let { return@withContext "Could not create the certificate: $it" }
    if (!certificate.installed || certificate.pem.isEmpty()) return@withContext "Could not create the certificate."
    val home = File(System.getProperty("user.home"))
    val directory = File(home, "Downloads").takeIf { it.isDirectory } ?: home
    // One fixed name, so a rotation replaces the file it invalidated instead of leaving two roots in a
    // folder with no way to tell which one the machine will actually accept.
    val target = File(directory, "Wailo-Local-Root.pem")
    val failure = runCatching { target.writeText(certificate.pem) }.exceptionOrNull()
    if (failure != null) return@withContext "Could not save the certificate: ${failure.message}"
    // Best-effort: on macOS this hands the file to Keychain Access, which is the whole point. Where the
    // desktop can't open it, the path above is still the answer, so a failure here is not one worth saying.
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().takeIf { it.isSupported(Desktop.Action.OPEN) }?.open(target)
    }
    "Saved to ${target.path}."
}

/**
 * Shows the native modal [FileDialog] and suspends until it's dismissed. The dialog is opened via
 * [EventQueue.invokeLater] — on a *fresh* EDT event rather than inline — so its nested modal event loop
 * never runs inside Compose's current render/flush pass. Opening it inline (this is called from the
 * Compose composition scope) re-enters Compose's coroutine dispatcher mid-flush and crashes it with a
 * ClassCastException / ConcurrentModificationException.
 */
private suspend fun awaitNativeFileDialog(
    owner: Frame?,
    title: String,
    mode: Int = FileDialog.LOAD,
    defaultFileName: String? = null,
    filter: FilenameFilter? = null,
): File? = suspendCancellableCoroutine { cont ->
    EventQueue.invokeLater {
        val dialog = FileDialog(owner, title, mode).apply {
            isMultipleMode = false
            defaultFileName?.let { file = it }
            filter?.let { filenameFilter = it }
        }
        dialog.isVisible = true // blocks the EDT (nested modal loop) until the user chooses or cancels
        val name = dialog.file
        val dir = dialog.directory
        cont.resume(if (name != null && dir != null) File(dir, name) else null)
    }
}
