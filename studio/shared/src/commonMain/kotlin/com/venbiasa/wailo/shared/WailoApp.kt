package com.venbiasa.wailo.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.shared.theme.TextScale
import com.venbiasa.wailo.shared.theme.WailoTheme
import com.venbiasa.wailo.shared.ui.BreakpointInspector
import com.venbiasa.wailo.shared.ui.ToolPanelLayout
import com.venbiasa.wailo.shared.ui.WailoViewer

/**
 * Root of the desktop inspector: a live, tailing request list over a request/response detail panel.
 *
 * Stateless over its inputs — the host ([com.venbiasa.wailo.desktop]) owns the engine and maps
 * captured rows into [entries]. [zoneOffsetMillis] converts each exchange's epoch timestamp to the
 * host's local wall clock (kept out of commonMain, which has no `java.time`). [textScale] is the
 * host-owned text-size multiplier (Cmd +/-); it rides on `fontScale` so only `sp` text resizes.
 * [darkTheme] and [onToggleDarkTheme] are likewise host-owned so the choice can persist across launches.
 * [openFilterSignal] is a counter the host bumps on Cmd/Ctrl+F to open the traffic list's filter bar. The
 * shortcut is owned by the host's window because Compose only routes key events to whatever holds focus,
 * and clicking a plain surface seeds none — so a handler inside this tree can't be relied on to run.
 * [listenAddress] is where the capture server accepts device connections and [listenPort] is the port
 * within it, editable in the settings panel; [listening] says whether the server actually bound (false
 * greys nothing out but marks the address as dead, since no traffic can arrive), and [onApplyPort] asks
 * the host to move the server — only the host can try the bind, so it reports the verdict back through
 * [portError] rather than this guessing which ports are usable. [onRetryListen] rebinds it where it is,
 * offered next to the address while [listening] is false: the engine retakes a port it lost on its own,
 * but one it never got is held by something only the user knows the end of. It suspends so the retry can
 * show as in-flight rather than as a button that appears to do nothing for a second. [devices] combines Hello-identified LAN
 * sessions with host-discovered USB devices for the Devices panel; [usbSupported] controls its platform
 * guidance — and whether the USB port is offered at all — without leaking platform APIs into `shared`.
 * [usbPort] is the device-side port Studio dials over USB and [onApplyUsbPort] moves it (rejected values
 * come back through [usbPortError]); it has to be set to match the SDK by hand, since usbmux forwards to a
 * port without advertising one. [adbSupported] says whether the host found the Android platform-tools, and
 * so whether an attached phone is forwarded for the user or has to be reached over the network; it needs no
 * port of its own, because a reverse mapping puts the device on [listenPort] at both ends.
 * [maxRetained] is how many captured exchanges the engine holds before the
 * oldest fall off, and [onApplyMaxRetained] moves that cap (out-of-range values come back through
 * [maxRetainedError]); lowering it discards traffic, which is why it is applied on commit and not per
 * keystroke. [mcpAccess] is whether AI tools may reach the capture at all and [mcpRedactSecrets] whether
 * what they read has its credentials stripped (ADR-0059); both belong to the daemon rather than to this
 * window, so [onMcpAccessChange]/[onMcpRedactSecretsChange] report a flip and the value comes back from
 * there. [pairing] and [onPairingAction] drive the Wi-Fi trust surface (ADR-0039):
 * the host owns the keys and the QR rendering, so this only shows what it is given. [capturing] reflects whether
 * traffic is being recorded, and [onToggleCapture]/[onClear] drive the top bar (the engine lives in the
 * host, not here). [bookmarks] are the persisted, host-owned bookmarked hosts; [onAddBookmark]/
 * [onRemoveBookmark] let the viewer mutate that set (the host owns its persistence, ADR-0013).
 * [captureFilter] is the host-owned, persisted capture filter (the feature master + the allow/block host
 * lists + each list's on/off switch) the host pushes to devices, which gate whole exchanges at the source
 * (ADR-0029/0030); [onCaptureFilterChange] hands back a new filter for any change (add/remove a host, flip
 * a list, or flip the feature master — off captures everything and disables the lists, state kept).
 * [mapLocalNodes] are the host-owned, persisted Map Local layout (groups + rules, in priority order) the
 * right-side tool panel renders (ADR-0021/0026); [onMapLocalLayoutChange] hands back a new layout for any
 * structural change, and [onLoadMapLocalBody]/[onSaveMapLocalBody] read/persist a rule's authored body as
 * bytes (the host owns all file IO). [onPickMapLocalFile] opens the host's file picker for a body file
 * (JSON/text or image). [mapLocalEnabled]/[onMapLocalEnabledChange] are the Map Local feature master
 * (ADR-0030): off, the host pushes no rules and the panel disables its switches, state kept.
 * [onSeedFromMapLocalRule] is a Map Local row's right-click "Seed…", which copies that rule into the Seed
 * list — the host's job, since it owns both layouts and the body files (ADR-0041).
 * [onExportRules]/[onImportRules] write and read the portable rule archive from the Map Local panel's
 * overflow menu; the host owns the file dialog, the bytes, and the merge, and returns the one line the
 * panel shows (blank when the user cancels).
 * [breakpointNodes] are the host-owned, persisted breakpoints layout (groups +
 * rules, in priority order) the same tool panel renders (ADR-0026/0027); [onBreakpointLayoutChange] hands
 * back a new layout for any structural change, and [breakpointsEnabled]/[onBreakpointsEnabledChange] are
 * that feature's master (ADR-0030, same semantics). The requests/responses devices are holding at a
 * breakpoint are edited in a separate window ([WailoBreakpointWindowContent], ADR-0034), which
 * [onOpenBreakpointWindow] raises from the panel with nothing paused. The
 * panel's open state and any row-seeded draft are the viewer's own transient state.
 * [seedNodes] are the host-owned, persisted Seed layout — the canned responses that answer paused
 * exchanges (ADR-0041) — rendered by their own panel; [onSeedLayoutChange] hands back a new layout,
 * [onLoadSeedBody]/[onSaveSeedBody] read/persist a seed's body bytes, and [seedsEnabled]/
 * [onSeedsEnabledChange] are that feature's master. [openSeedPanelSignal] is a counter the host bumps
 * after importing a Map Local rule, to reveal the import by opening the Seed panel.
 * [toolPanelWidthRatio] is the host-owned, persisted width of that docked panel expressed as a
 * fraction of the window (so it scales with the window rather than pinning to a fixed dp);
 * [onToolPanelWidthRatioChange] hands back a new fraction as the user drags the panel's resize handle.
 */
@Composable
fun WailoApp(
    entries: List<FlowEntry>,
    zoneOffsetMillis: Int = 0,
    darkTheme: Boolean = isSystemInDarkTheme(),
    onToggleDarkTheme: () -> Unit = {},
    textScale: Float = TextScale.Default,
    openFilterSignal: Int = 0,
    listenAddress: String = "",
    listenPort: Int = 0,
    listening: Boolean = true,
    onRetryListen: suspend () -> Unit = {},
    portError: String? = null,
    onApplyPort: (Int) -> Unit = {},
    devices: List<DeviceInfo> = emptyList(),
    usbSupported: Boolean = false,
    usbPort: Int = 0,
    usbPortError: String? = null,
    onApplyUsbPort: (Int) -> Unit = {},
    adbSupported: Boolean = false,
    maxRetained: Int = 0,
    maxRetainedError: String? = null,
    onApplyMaxRetained: (Int) -> Unit = {},
    mcpAccess: Boolean = true,
    onMcpAccessChange: (Boolean) -> Unit = {},
    mcpRedactSecrets: Boolean = true,
    onMcpRedactSecretsChange: (Boolean) -> Unit = {},
    pairing: PairingState = PairingState(),
    onPairingAction: (PairingAction) -> Unit = {},
    capturing: Boolean = true,
    onToggleCapture: () -> Unit = {},
    onClear: () -> Unit = {},
    bookmarks: List<String> = emptyList(),
    onAddBookmark: (String) -> Unit = {},
    onRemoveBookmark: (String) -> Unit = {},
    captureFilter: CaptureFilterState = CaptureFilterState(),
    onCaptureFilterChange: (CaptureFilterState) -> Unit = {},
    mapLocalNodes: List<MapLocalNode> = emptyList(),
    onMapLocalLayoutChange: (List<MapLocalNode>) -> Unit = {},
    onLoadMapLocalBody: suspend (MapLocalRuleDef) -> ByteArray = { ByteArray(0) },
    onSaveMapLocalBody: suspend (MapLocalRuleDef, ByteArray) -> Unit = { _, _ -> },
    onPickMapLocalFile: suspend () -> PickedFile? = { null },
    mapLocalEnabled: Boolean = true,
    onMapLocalEnabledChange: (Boolean) -> Unit = {},
    onSeedFromMapLocalRule: (MapLocalRuleDef) -> Unit = {},
    onExportRules: suspend () -> String = { "" },
    onImportRules: suspend () -> String = { "" },
    breakpointNodes: List<BreakpointNode> = emptyList(),
    onBreakpointLayoutChange: (List<BreakpointNode>) -> Unit = {},
    breakpointsEnabled: Boolean = true,
    onBreakpointsEnabledChange: (Boolean) -> Unit = {},
    onOpenBreakpointWindow: () -> Unit = {},
    seedNodes: List<SeedNode> = emptyList(),
    onSeedLayoutChange: (List<SeedNode>) -> Unit = {},
    onLoadSeedBody: suspend (SeedRuleDef) -> ByteArray = { ByteArray(0) },
    onSaveSeedBody: suspend (SeedRuleDef, ByteArray) -> Unit = { _, _ -> },
    seedsEnabled: Boolean = true,
    onSeedsEnabledChange: (Boolean) -> Unit = {},
    openSeedPanelSignal: Int = 0,
    /**
     * The bundled proxy's daemon-owned state (ADR-0070). Studio is a client of it like every other
     * frontend — [onProxyEnabledChange], [onApplyProxyPort] and [onProxySetupAction] ask the daemon, and
     * what comes back is what this shows, including a port it refused to bind.
     */
    proxy: ProxyState = ProxyState(),
    onProxyEnabledChange: (Boolean) -> Unit = {},
    onApplyProxyPort: (Int) -> Unit = {},
    onProxySetupAction: (ProxySetupAction) -> Unit = {},
    toolPanelWidthRatio: Float = ToolPanelLayout.DefaultWidthRatio,
    onToolPanelWidthRatioChange: (Float) -> Unit = {},
    /**
     * How a body view gets its bytes. Rows arrive without them (ADR-0069), so this is what turns a
     * [FlowEntry]'s handle into something to render. Defaults to reading nothing, which is what a
     * preview composed outside a running Studio should show.
     */
    bodyLoader: BodyLoader = BodyLoader { _, _, _ -> ByteArray(0) },
) {
    WailoTheme(darkTheme = darkTheme) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, density.fontScale * textScale),
            LocalBodyLoader provides bodyLoader,
        ) {
            WailoViewer(
                entries = entries,
                zoneOffsetMillis = zoneOffsetMillis,
                darkTheme = darkTheme,
                onToggleDarkTheme = onToggleDarkTheme,
                openFilterSignal = openFilterSignal,
                listenAddress = listenAddress,
                listenPort = listenPort,
                listening = listening,
                onRetryListen = onRetryListen,
                portError = portError,
                onApplyPort = onApplyPort,
                devices = devices,
                usbSupported = usbSupported,
                usbPort = usbPort,
                usbPortError = usbPortError,
                adbSupported = adbSupported,
                onApplyUsbPort = onApplyUsbPort,
                maxRetained = maxRetained,
                maxRetainedError = maxRetainedError,
                onApplyMaxRetained = onApplyMaxRetained,
                mcpAccess = mcpAccess,
                onMcpAccessChange = onMcpAccessChange,
                mcpRedactSecrets = mcpRedactSecrets,
                onMcpRedactSecretsChange = onMcpRedactSecretsChange,
                pairing = pairing,
                onPairingAction = onPairingAction,
                capturing = capturing,
                onToggleCapture = onToggleCapture,
                onClear = onClear,
                bookmarks = bookmarks,
                onAddBookmark = onAddBookmark,
                onRemoveBookmark = onRemoveBookmark,
                captureFilter = captureFilter,
                onCaptureFilterChange = onCaptureFilterChange,
                mapLocalNodes = mapLocalNodes,
                onMapLocalLayoutChange = onMapLocalLayoutChange,
                onLoadMapLocalBody = onLoadMapLocalBody,
                onSaveMapLocalBody = onSaveMapLocalBody,
                onPickMapLocalFile = onPickMapLocalFile,
                mapLocalEnabled = mapLocalEnabled,
                onMapLocalEnabledChange = onMapLocalEnabledChange,
                onSeedFromMapLocalRule = onSeedFromMapLocalRule,
                onExportRules = onExportRules,
                onImportRules = onImportRules,
                breakpointNodes = breakpointNodes,
                onBreakpointLayoutChange = onBreakpointLayoutChange,
                breakpointsEnabled = breakpointsEnabled,
                onBreakpointsEnabledChange = onBreakpointsEnabledChange,
                onOpenBreakpointWindow = onOpenBreakpointWindow,
                seedNodes = seedNodes,
                onSeedLayoutChange = onSeedLayoutChange,
                onLoadSeedBody = onLoadSeedBody,
                onSaveSeedBody = onSaveSeedBody,
                seedsEnabled = seedsEnabled,
                onSeedsEnabledChange = onSeedsEnabledChange,
                openSeedPanelSignal = openSeedPanelSignal,
                proxy = proxy,
                onProxyEnabledChange = onProxyEnabledChange,
                onApplyProxyPort = onApplyProxyPort,
                onProxySetupAction = onProxySetupAction,
                toolPanelWidthRatio = toolPanelWidthRatio,
                onToolPanelWidthRatioChange = onToolPanelWidthRatioChange,
            )
        }
    }
}

/**
 * The contents of the standalone breakpoint window (ADR-0034): the paused-traffic inspector on its own
 * top-level window rather than a modal over [WailoApp]. The window is user-owned (ADR-0041) — the host
 * opens it from the Breakpoints panel or when a hold needs a human, and only the user closes it — so it
 * is routinely composed with [pausedFlows] empty, which is how seeds are armed before their traffic
 * arrives.
 *
 * It carries its own theme so it matches the main window's appearance: [darkTheme] mirrors the host's
 * choice and [textScale] rides on `fontScale` exactly as in [WailoApp], so Cmd +/- resizes this window's
 * text too. [onResumeBreakpoint]/[onAbortBreakpoint] resolve a hold by correlation id (Resume applies the
 * edits or proceeds unchanged; Abort fails the app's call). Concurrent holds are shown as a queue the
 * user resolves in any order.
 *
 * [seeds] is the armed queue of canned responses, which the daemon owns — which is why it survives this
 * window closing and reopening, and why it can change while nothing here did (ADR-0067). [onFillSeeds]
 * arms it from the enabled seed rules, [onClearSeeds] empties it, and [onLoadSeedBody] reads a seed's
 * stored body for its preview. A seed is spent the moment a matching hold arrives, so a hold answered
 * that way never reaches this window.
 *
 * [onBringToFront] raises this window: the host owns the OS window, so the inspector asks for it when a
 * hold arrives while the window is buried behind another.
 */
@Composable
fun WailoBreakpointWindowContent(
    pausedFlows: List<PausedFlow>,
    seeds: List<SeedRuleDef> = emptyList(),
    onFillSeeds: () -> Unit = {},
    onClearSeeds: () -> Unit = {},
    onLoadSeedBody: suspend (SeedRuleDef) -> ByteArray = { ByteArray(0) },
    onBringToFront: () -> Unit = {},
    darkTheme: Boolean = isSystemInDarkTheme(),
    textScale: Float = TextScale.Default,
    onResumeBreakpoint: (String, HttpRequest?, HttpResponse?) -> Unit = { _, _, _ -> },
    onAbortBreakpoint: (String) -> Unit = {},
) {
    WailoTheme(darkTheme = darkTheme) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, density.fontScale * textScale),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                BreakpointInspector(
                    flows = pausedFlows,
                    seeds = seeds,
                    onFillSeeds = onFillSeeds,
                    onClearSeeds = onClearSeeds,
                    onLoadSeedBody = onLoadSeedBody,
                    onBringToFront = onBringToFront,
                    onResume = onResumeBreakpoint,
                    onAbort = onAbortBreakpoint,
                )
            }
        }
    }
}
