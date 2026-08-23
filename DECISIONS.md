# Decisions (ADR index)

One line per ADR. Bodies live in [`docs/decisions/`](docs/decisions/). Append-only: to change a
decision, add a new ADR that supersedes the old one — never rewrite history.

This file stays an index so an agent can load the whole decision surface in one read. Put the
reasoning in the ADR file, not here.

| ADR | Date | Status | Title |
|---|---|---|---|
| [0001](docs/decisions/ADR-0001-in-app-sdk-interception-instead-of-a-system-proxy.md) | 2026-07-05 | accepted | In-app SDK interception instead of a system proxy |
| [0002](docs/decisions/ADR-0002-protobuf-square-wire-as-the-wire-protocol.md) | 2026-07-05 | accepted | Protobuf (Square Wire) as the wire protocol |
| [0003](docs/decisions/ADR-0003-headless-engine-separate-from-the-ui.md) | 2026-07-05 | accepted | Headless engine separate from the UI |
| [0004](docs/decisions/ADR-0004-websocket-over-adb-reverse-for-the-first-transport.md) | 2026-07-05 | accepted | WebSocket over adb reverse for the first transport |
| [0005](docs/decisions/ADR-0005-follow-current-kmp-wizard-conventions-add-sibling.md) | 2026-07-05 | accepted | Follow current KMP-wizard conventions, add sibling modules |
| [0006](docs/decisions/ADR-0006-ios-core-sharing-decision-deferred.md) | 2026-07-05 | accepted | iOS core-sharing decision deferred |
| [0007](docs/decisions/ADR-0007-the-device-side-capture-model-is-the-protobuf-type.md) | 2026-07-06 | accepted | The device-side capture model is the protobuf type; delivery is a port |
| [0008](docs/decisions/ADR-0008-transport-framing-envelope-and-an-interim-desktop.md) | 2026-07-06 | accepted | Transport framing (Envelope) and an interim desktop list before the M3 viewer |
| [0009](docs/decisions/ADR-0009-capture-third-party-and-non-okhttp-traffic-via-auto.md) | 2026-07-06 | accepted | Capture third-party and non-OkHttp traffic via auto-instrumentation (build-time ASM on Android, swizzle on iOS) |
| [0010](docs/decisions/ADR-0010-the-ios-interceptor-is-a-native-swift-sdk-resolves.md) | 2026-07-08 | accepted | The iOS interceptor is a native Swift SDK (resolves ADR-0006) |
| [0011](docs/decisions/ADR-0011-fold-core-into-sdk-android-drop-device-side-kotlin.md) | 2026-07-08 | accepted | Fold `core` into `sdk-android`; drop device-side Kotlin Multiplatform (amends invariant #4) |
| [0012](docs/decisions/ADR-0012-sample-matrix-sample-android-sample-ios-real-app.md) | 2026-07-08 | accepted | Sample matrix — `sample-android`, `sample-ios` (real app + CLI harness), and a planned `sample-kmp` |
| [0013](docs/decisions/ADR-0013-the-m3-desktop-viewer-overview-where-it-lives-its.md) | 2026-07-11 | accepted | The M3 desktop viewer (overview) — where it lives, its seam, and its look |
| [0014](docs/decisions/ADR-0014-pin-the-toolchain-to-the-target-consumer-app-gradle.md) | 2026-07-12 | accepted | Pin the toolchain to the target consumer app (Gradle 8.11.1 / AGP 8.10.1 / Kotlin 2.2.21) |
| [0015](docs/decisions/ADR-0015-split-the-repo-into-two-builds-consumer-pinned-sdk.md) | 2026-07-12 | accepted | Split the repo into two builds — consumer-pinned SDK + a modern `studio` desktop build |
| [0016](docs/decisions/ADR-0016-dark-mode-is-first-class-every-new-design-must-work.md) | 2026-07-15 | accepted | Dark mode is first-class — every new design must work in light and dark |
| [0017](docs/decisions/ADR-0017-ios-zero-install-auto-start-load-hook-in-a-dynamic.md) | 2026-07-15 | accepted | iOS zero-install auto-start — `+load` hook in a dynamic SDK (implements ADR-0009's iOS half) |
| [0018](docs/decisions/ADR-0018-device-side-websocket-reconnection-is-self-healing.md) | 2026-07-17 | accepted | Device-side WebSocket reconnection is self-healing (delegate + generation + ping keepalive); OS reachability deferred |
| [0019](docs/decisions/ADR-0019-map-local-device-caches-match-metadata-only-fetches.md) | 2026-07-17 | accepted | Map Local — device caches match-metadata only, fetches bodies lazily; rules are versioned + acked (anti-entropy) |
| [0020](docs/decisions/ADR-0020-map-local-response-body-editor-inline-app-managed.md) | 2026-07-18 | accepted | Map Local response-body editor — inline app-managed bodies + a Swing code editor behind a portable seam |
| [0021](docs/decisions/ADR-0021-map-local-is-a-docked-right-side-tool-panel-single.md) | 2026-07-18 | accepted | Map Local is a docked right-side tool panel, single-method, seeded exactly from a row |
| [0022](docs/decisions/ADR-0022-map-local-panel-navigates-via-navigation-3-add-is-a.md) | 2026-07-18 | accepted | Map Local panel navigates via Navigation 3; Add is a FAB; the nav layer is multiplatform-ready |
| [0023](docs/decisions/ADR-0023-replace-the-swing-code-editor-with-a-compose-native.md) | 2026-07-19 | accepted | Replace the Swing code editor with a Compose-native, viewport-virtualized editor |
| [0024](docs/decisions/ADR-0024-sunset-the-swing-rsyntaxtextarea-code-editor.md) | 2026-07-21 | accepted | Sunset the Swing / RSyntaxTextArea code editor |
| [0025](docs/decisions/ADR-0025-the-device-sdk-is-a-live-tap-drop-traffic-captured.md) | 2026-07-22 | accepted | The device SDK is a live tap — drop traffic captured while disconnected instead of buffering a replay backlog (amends ADR-0018) |
| [0026](docs/decisions/ADR-0026-map-local-rules-gain-single-level-groups-list-order.md) | 2026-07-22 | accepted | Map Local rules gain single-level groups; list order is match priority; drag-and-drop is hand-rolled |
| [0027](docs/decisions/ADR-0027-interactive-breakpoints-pause-a-matching-request.md) | 2026-07-24 | accepted | Interactive breakpoints — pause a matching request/response on-device, edit it live on the desktop, resume-or-abort (iOS-first; realizes ADR-0019's anticipated extension) |
| [0028](docs/decisions/ADR-0028-breakpoint-rules-gain-map-locals-groups-by.md) | 2026-07-24 | accepted | Breakpoint rules gain Map Local's groups by generalizing the layout core into a shared generic (both panels are one grouped, drag-orderable list) |
| [0029](docs/decisions/ADR-0029-capture-allowlist-blocklist-a-device-side-whole.md) | 2026-07-25 | accepted | Capture allowlist/blocklist — a device-side, whole-exchange capture filter replaces the per-host "unlock bodies" model (supersedes the informal Capture Allowlist) |
| [0030](docs/decisions/ADR-0030-per-feature-master-switch-one-on-off-above-each.md) | 2026-07-25 | accepted | Per-feature master switch — one on/off above each interception feature (capture filter, Map Local, breakpoints), gating what's pushed, never the saved state |
| [0031](docs/decisions/ADR-0031-android-reaches-device-side-parity-map-local.md) | 2026-07-27 | accepted | Android reaches device-side parity — Map Local, breakpoints, and the capture filter ported from iOS over the existing wire contract (realizes the deferrals in ADR-0019/0027/0029) |
| [0032](docs/decisions/ADR-0032-breakpoint-request-edit-re-runs-map-local.md) | 2026-07-28 | accepted | Breakpoint request-edit re-runs Map Local (precedence v2) — an edited request that now matches a Map Local rule is served locally, not sent to the network (closes ADR-0027's deferred request-edit-then-rematch) |
| [0033](docs/decisions/ADR-0033-breakpoints-take-precedence-over-map-local-and-a.md) | 2026-07-28 | accepted | Breakpoints take precedence over Map Local, and a Map Local match *sources* the breakpoint's response (precedence v3, superseding ADR-0027's "Map Local is never broken" and subsuming ADR-0032) |
| [0034](docs/decisions/ADR-0034-the-paused-traffic-editor-is-a-dedicated-window.md) | 2026-07-29 | accepted | The paused-traffic editor is a dedicated window with an any-order queue, not a modal (supersedes ADR-0027's modal `BreakpointEditor` + one-at-a-time FIFO queue) |
| [0035](docs/decisions/ADR-0035-the-ios-desktop-address-is-resolved-at-runtime.md) | 2026-07-30 | accepted | The iOS desktop address is resolved at runtime — Bonjour discovery plus a persisted override reachable from an opt-in on-device panel (closes ADR-0004's deferred mDNS, for iOS) |
| [0036](docs/decisions/ADR-0036-the-capture-servers-port-is-changeable-at-runtime.md) | 2026-07-30 | accepted | The capture server's port is changeable at runtime from a studio Settings panel — the engine rebinds in place rather than being rebuilt |
| [0037](docs/decisions/ADR-0037-macos-studio-connects-to-physical-ios-apps-through.md) | 2026-07-30 | accepted | macOS Studio connects to physical iOS apps through the built-in usbmuxd, with USB preferred and LAN as fallback |
| [0038](docs/decisions/ADR-0038-the-on-device-panel-is-themed-from-the-shared.md) | 2026-07-30 | accepted | The on-device panel is themed from the shared design tokens, and the iOS SDK stays native Swift |
| [0039](docs/decisions/ADR-0039-wi-fi-sessions-are-mutually-authenticated-and.md) | 2026-08-04 | accepted | Wi-Fi sessions are mutually authenticated and encrypted; loopback and USB are not |
| [0040](docs/decisions/ADR-0040-wi-fi-is-trust-on-first-use-by-default-pairing-is.md) | 2026-08-04 | accepted | Wi-Fi is trust-on-first-use by default; pairing is the opt-in strict mode |
| [0041](docs/decisions/ADR-0041-seeds-are-desktop-side-canned-responses-that-a.md) | 2026-08-06 | accepted | Seeds are desktop-side canned responses that a breakpoint hold spends, in order |
| [0042](docs/decisions/ADR-0042-a-breakpoint-rule-list-has-no-order-to-drag.md) | 2026-08-06 | accepted | A breakpoint rule list has no order to drag |
| [0043](docs/decisions/ADR-0043-one-pane-shared-by-holds-and-seeds-and-an-arriving.md) | 2026-08-06 | accepted | One pane, shared by holds and seeds — and an arriving hold takes it only when the user isn't already in one |
| [0044](docs/decisions/ADR-0044-fill-answers-the-holds-already-waiting.md) | 2026-08-06 | accepted | Fill answers the holds already waiting |
| [0045](docs/decisions/ADR-0045-a-seed-is-authored-from-the-traffic-it-will-stand.md) | 2026-08-06 | accepted | A seed is authored from the traffic it will stand in for |
| [0046](docs/decisions/ADR-0046-nothing-on-the-device-may-need-a-relaunch-to-recover.md) | 2026-08-07 | accepted | Nothing on the device may need a relaunch to recover |
| [0047](docs/decisions/ADR-0047-connect-is-one-list-of-desktops-and-forget-is-how.md) | 2026-08-07 | accepted | Connect is one list of desktops, and Forget is how one stops being reached |
| [0048](docs/decisions/ADR-0048-android-gets-its-own-copy-of-the-handshake-not-a.md) | 2026-08-08 | accepted | Android gets its own copy of the handshake, not a shared one — and the pairing key lives in the Android Keystore |
| [0049](docs/decisions/ADR-0049-the-android-on-device-panel-is-a-separate-sdk.md) | 2026-08-08 | accepted | The Android on-device panel is a separate `sdk-android-panel` artifact with a launcher icon of its own |
| [0050](docs/decisions/ADR-0050-studio-installs-the-adb-reverse-route-itself-and-an.md) | 2026-08-08 | accepted | Studio installs the `adb reverse` route itself, and an attached Android device is a row in the Devices panel |
| [0051](docs/decisions/ADR-0051-a-setting-too-big-for-java-util-prefs-is-split.md) | 2026-08-09 | accepted | A setting too big for `java.util.prefs` is split across keys, not moved out of it |
| [0052](docs/decisions/ADR-0052-attached-android-devices-are-watched-over-host.md) | 2026-08-09 | accepted | Attached Android devices are watched over `host:track-devices`, not polled (supersedes ADR-0050's poll loop) |
| [0053](docs/decisions/ADR-0053-the-usb-listener-rebinds-on-every-return-from-the.md) | 2026-08-09 | accepted | The USB listener rebinds on every return from the background, because a dead `NWListener` does not say it is dead (amends ADR-0037 and ADR-0046) |
| [0054](docs/decisions/ADR-0054-the-traffic-lists-filter-is-a-matcher-and-the-box.md) | 2026-08-11 | accepted | The traffic list's filter is a matcher, and the box and the pills are two front-ends over one evaluator |
| [0055](docs/decisions/ADR-0055-headless-host-module-cli-frontend-over-the-engine.md) | 2026-05-12 | accepted | Headless `host` module + CLI frontend over the engine (extends ADR-0003) |
| [0056](docs/decisions/ADR-0056-one-headless-server-owns-capture-state-cli.md) | 2026-05-12 | accepted | One headless server owns capture state; CLI invocations use an authenticated loopback control channel (amends ADR-0055) |
| [0057](docs/decisions/ADR-0057-mcp-is-a-client-owned-stdio-frontend-with-its-own.md) | 2026-08-13 | accepted | MCP is a client-owned stdio frontend with its own headless capture process (extends ADR-0055/0056) |
| [0058](docs/decisions/ADR-0058-one-persistent-local-daemon-is-shared-by-studio-cli.md) | 2026-08-13 | accepted | One persistent local daemon is shared by Studio, CLI, and MCP (supersedes ADR-0056/0057 process ownership) |
| [0059](docs/decisions/ADR-0059-ai-tool-access-is-a-revocable-daemon-setting-with.md) | 2026-08-13 | accepted | AI tool access is a revocable daemon setting with redaction on by default, and the control port is discovered |
| [0060](docs/decisions/ADR-0060-wi-fi-authentication-is-identity-first-with-per-studio-device-aliases.md) | 2026-08-14 | accepted | Wi-Fi authentication is identity-first, with per-Studio device aliases |
| [0061](docs/decisions/ADR-0061-map-local-and-breakpoint-rules-persist-on-the-daemon.md) | 2026-08-16 | partly superseded | Daemon-owned configuration is durable; traffic and holds stay a session (its opaque layout blob superseded by ADR-0081) |
| [0062](docs/decisions/ADR-0062-the-daemon-exits-once-nothing-refers-to-it.md) | 2026-08-16 | accepted | The daemon exits once nothing refers to it, and a reference is a held socket |
| [0063](docs/decisions/ADR-0063-studio-owns-the-menu-bar-item-and-outlives-its-window.md) | 2026-08-20 | superseded | Studio owns the menu bar item and outlives its own window (superseded by ADR-0065) |
| [0064](docs/decisions/ADR-0064-one-global-tools-gate-above-the-per-feature-masters.md) | 2026-08-20 | superseded | One global tools gate above the per-feature masters, owned by the daemon (superseded by ADR-0066) |
| [0065](docs/decisions/ADR-0065-the-menu-bar-item-is-the-daemons-own-companion-process.md) | 2026-08-20 | accepted | The menu bar item is the daemon's own companion process, not Studio's window (supersedes ADR-0063) |
| [0066](docs/decisions/ADR-0066-the-menu-bar-lists-each-daemon-owned-master-instead-of-a-gate.md) | 2026-08-20 | accepted | The menu bar lists each daemon-owned master instead of a gate above them (supersedes ADR-0064) |
| [0067](docs/decisions/ADR-0067-the-daemon-owns-the-seed-library-and-spends-it.md) | 2026-08-20 | accepted | The daemon owns the seed library and spends it, so Seeds reach CLI, MCP, and the menu bar (supersedes ADR-0041's desktop-side spend and ADR-0066's Seeds carve-out) |
| [0068](docs/decisions/ADR-0068-the-menu-bar-item-ships-a-drawn-asset-per-state.md) | 2026-08-21 | accepted | The menu bar item ships a drawn asset per state instead of deriving one from the app mark (replaces ADR-0065's runtime derivation) |
| [0069](docs/decisions/ADR-0069-captured-bodies-live-in-an-encrypted-session-spool.md) | 2026-08-21 | accepted | Captured bodies live in an encrypted session spool, and an exchange only holds a reference (amends ADR-0061's in-memory traffic) |
| [0070](docs/decisions/ADR-0070-the-proxy-is-an-additive-daemon-owned-capture-source.md) | 2026-08-22 | accepted | The proxy is an additive, daemon-owned capture source in a bundled `:proxy` module (extends ADR-0001/0058) |
| [0071](docs/decisions/ADR-0071-https-starts-locked-and-connect-is-an-opaque-tunnel.md) | 2026-08-22 | accepted | HTTPS starts locked — `CONNECT` is an opaque tunnel until a host is explicitly unlocked (extends ADR-0070) |
| [0072](docs/decisions/ADR-0072-a-hold-is-a-route-so-one-rule-set-serves-both-capture-paths.md) | 2026-08-22 | accepted | A hold carries a decision route, so one rule set serves both capture paths (extends ADR-0067/0070) |
| [0073](docs/decisions/ADR-0073-the-local-root-is-keychain-only-and-leaves-are-minted-per-host.md) | 2026-08-22 | accepted | The local root lives in the Keychain and never leaves it; leaves are minted per unlocked host (implements ADR-0071's deferred half) |
| [0074](docs/decisions/ADR-0074-the-lan-bind-is-opt-in-because-it-is-an-open-relay.md) | 2026-08-22 | partly superseded | The proxy's LAN bind is opt-in, persisted, and named as an open relay (extends ADR-0070; its default reversed by ADR-0077) |
| [0075](docs/decisions/ADR-0075-the-system-proxy-takeover-snapshots-to-disk-and-chains-upstream.md) | 2026-08-22 | accepted | The system proxy takeover snapshots to disk and chains through whatever was already there (extends ADR-0070/0074) |
| [0076](docs/decisions/ADR-0076-the-proxy-port-serves-its-own-setup-page.md) | 2026-08-22 | accepted | The proxy port answers a browser with its own setup page, and never mints a root to do it (completes ADR-0074's device half) |
| [0077](docs/decisions/ADR-0077-the-proxy-binds-every-interface-by-default-and-starting-it-is-the-only-gate.md) | 2026-08-22 | accepted | The proxy binds every interface by default; starting it is the only gate (supersedes ADR-0074's loopback default) |
| [0078](docs/decisions/ADR-0078-the-takeover-record-belongs-to-the-machine-and-stopping-sweeps-it.md) | 2026-08-22 | accepted | The system proxy takeover is machine state, and stopping the proxy sweeps for it (fixes ADR-0075) |
| [0079](docs/decisions/ADR-0079-comparing-two-exchanges-canonicalizes-both-then-diffs-them-as-text.md) | 2026-08-23 | accepted | Comparing two exchanges canonicalizes both and then diffs them as text, in `shared` where only Studio can reach it |
| [0080](docs/decisions/ADR-0080-rules-export-to-one-portable-file-and-importing-merges-by-id.md) | 2026-08-23 | accepted | Authored rules export to one portable file, and importing merges by id rather than replacing |
| [0081](docs/decisions/ADR-0081-the-daemon-owns-rule-grouping-and-every-frontend-reads-it-back.md) | 2026-08-23 | accepted | The daemon owns rule grouping, and every frontend reads back the layout it wrote (supersedes ADR-0061's opaque layout blob) |
| [0082](docs/decisions/ADR-0082-the-capture-filter-master-is-daemon-state-and-folds-only-on-the-way-out.md) | 2026-08-23 | accepted | The capture filter's master is daemon state, and the fold happens only on the way to devices (extends ADR-0030 to the last frontend-only master) |
