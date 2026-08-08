# Wailo

In-app network inspection for mobile. Wailo is an interceptor **SDK** you drop into an
Android or iOS app; it streams captured HTTP(S) traffic to a **desktop app** where you can
inspect requests and responses live. Unlike a system proxy, capture happens inside the app,
so there's no certificate juggling or device-wide proxy setup.

The same capture engine is designed to be driven headlessly (for automation via Appium) and
exposed to AI tools (via MCP) later, without rewrites.

> Status: early. Being built in small, verifiable milestones (see Roadmap).

## Architecture

Two boundaries keep the system decoupled:

- **`protocol`** - the protobuf wire schema (source of truth, code-generated with Wire). Both
  the device SDK and the desktop speak it; neither hand-writes DTOs.
- **`engine`** - a headless library owning the transport server, a multi-session capture store,
  and a query/command API. The desktop UI, the future CLI, and the future MCP server are all
  thin frontends over it.

```
[App under test] -> Wailo SDK (Android/iOS) --(protobuf over WebSocket)--> [engine] -> desktop UI / CLI / MCP
```

The repo is **two Gradle builds** joined only by `protocol`: the **SDK build** (repo root — `protocol`,
`sdk-android`, the Gradle plugin, and the samples) is pinned to a conservative toolchain so it's consumable
inside host apps, while the **`studio/` build** (`engine`, `shared`, `desktopApp`) runs a modern toolchain
and consumes `protocol` as the published `wailo-protocol` artifact (ADR-0015).

## Modules

| Module          | What it is                                                        |
| --------------- | ----------------------------------------------------------------- |
| `protocol`      | KMP library; protobuf schema + Wire-generated types               |
| `sdk-android`   | Android library; `WailoInterceptor` (OkHttp) + capture sinks + WS client - the injected SDK |
| `sdk-android-panel` | Android library; the on-device Compose panel + QR pairing — `debugImplementation` only |
| `wailo-gradle-plugin` | Build-time only; ASM plugin that auto-instruments OkHttp |
| `sdk-ios`       | Swift package; `WailoURLProtocol` (URLSession) - the injected iOS SDK |
| `engine`        | JVM library; WebSocket server + multi-session store + query API — **studio build** |
| `shared`        | JVM + Compose Multiplatform viewer UI + view models — **studio build** |
| `desktopApp`    | Compose Desktop entry point — **studio build**                     |
| `sample-android`| Sample app under test (Android), used for dogfooding/verification |
| `sample-ios`    | Sample under test (iOS): a SwiftUI app (`app`) + a headless CLI harness (`cli`) |
| `sample-kmp`    | KMP sample: shared Ktor code + Android app (`sdk-android`) + iOS app shell (`sdk-ios`) |

## Requirements

- JDK 21 (the Android Studio JBR 21 works)
- Android SDK (compileSdk 36) — for the SDK build; the `studio/` desktop build needs only JDK 21
- Gradle is provided via the wrapper (`./gradlew`, plus `studio/gradlew` for the desktop build)

## Build

Two Gradle builds (ADR-0015). The SDK build is at the repo root; the `studio/` desktop build consumes
`protocol` as a published artifact, so publish it first.

```bash
# SDK build (repo root)
./gradlew projects                       # list modules
./gradlew build                          # protocol, sdk-android, samples, plugin
./gradlew :protocol:publishToMavenLocal  # make wailo-protocol available to the studio build
./gradlew :sample-android:assembleDebug

# studio build (desktop; modern toolchain)
cd studio && ./gradlew build             # engine, shared, desktopApp
```

## Using the SDK (M1)

Add the interceptor to the app's OkHttp client. The host app owns OkHttp; the SDK
depends on it only at compile time, so no version is forced on you.

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(Wailo.interceptor())
    .build()
```

By default, captured exchanges are printed to Logcat under the `Wailo` tag. Run the
sample and watch them stream:

```bash
./gradlew :sample-android:installDebug
adb shell am start -n com.venbiasa.wailo.sample/.MainActivity
adb logcat -s Wailo
```

## Streaming to the desktop (M2)

Swap the Logcat sink for a WebSocket sink and run the desktop app as the receiver. The
device is the client; the desktop is the server on `:8899`, forwarded with `adb reverse`.

```kotlin
// stream + LogcatSink() fans out: send to the desktop and still log locally
val stream = Wailo.webSocketSink(appId = packageName, deviceName = Build.MODEL).also { it.start() }
val client = OkHttpClient.Builder()
    .addInterceptor(Wailo.interceptor(sink = stream + LogcatSink()))
    .build()
```

```bash
(cd studio && ./gradlew :desktopApp:run)      # start the desktop inspector (live list + detail)
./gradlew :sample-android:installDebug
adb shell am start -n com.venbiasa.wailo.sample/.MainActivity
```

Captured requests appear live in the desktop window. The sample already wires this up, so
running the three commands above is enough to see traffic stream across.

Studio installs the `adb reverse` route itself: it watches the adb server for attached devices and
forwards each one onto the capture port, so an Android phone shows up in the **Devices** panel as soon as
it is plugged in (ADR-0050, ADR-0052). Nothing to type. If it says no adb was found, put the Android SDK's
`platform-tools` on `PATH` or set `ANDROID_HOME` — a desktop app launched from Finder does not see your
shell's `PATH`.

If `:8899` is taken, change it in the desktop's **Settings** panel (the gear at the foot of the right
tool rail) — the server rebinds without losing what it has already captured, and the choice sticks across
restarts (ADR-0036). Attached Android devices are re-forwarded onto the new port and an iOS device using
Bonjour re-finds it, so neither needs anything done to it. The macOS USB path is independent, with its own
device port (8900 by default) in the same panel.

Off the cable, a device reaches Studio over Wi-Fi instead — see [Connecting over Wi-Fi](#connecting-over-wi-fi).

## Auto-instrumentation

`Wailo.interceptor()` only reaches OkHttp clients the app itself builds. To also capture clients
built by third-party libraries, the host app can apply the build-time ASM plugin, which rewrites
every `OkHttpClient.Builder.build()` call site (app code *and* dependencies) to route through
`WailoRuntime` — so no per-client wiring is needed and capture starts at app launch.

```kotlin
// host app build.gradle.kts
plugins {
    id("com.venbiasa.wailo")
}
```

```kotlin
// once at startup: the sink auto-instrumented clients report to
WailoRuntime.install(Wailo.webSocketSink(appId = packageName, deviceName = Build.MODEL).also { it.start() })

// no interceptor added here, yet this client is still captured
val client = OkHttpClient.Builder().build()
```

The hook is idempotent: a client that *also* wires `Wailo.interceptor()` is captured exactly once.
The `sample-android` app demonstrates both paths.

## iOS (`sdk-ios`)

The iOS SDK is a standalone Swift package that captures `URLSession` traffic via a `URLProtocol` and
streams the same protobuf `Envelope`s to the desktop. It **auto-starts** — just add the package; a `+load`
hook arms capture before `main`, so no startup code is needed (the iOS analog of Android's startup provider,
ADR-0017):

```swift
// Package.swift
.package(path: "../sdk-ios")            // or a git URL once published
```

`URLSession.shared` and sessions built by third-party libraries are captured with no wiring, because the
hook swizzles `URLSessionConfiguration.default`/`.ephemeral` (ADR-0009). To customize (device name, or to
pin a host) call `Wailo.start(...)` once at launch — it's idempotent and cleanly replaces the
auto-installed default. For a session built before capture is armed, call `Wailo.instrument(configuration)`.

**Reaching the desktop.** The Simulator shares the Mac's network stack, so `localhost:8899` reaches the
engine with no forwarding. On macOS, a USB-connected physical device is automatic: Studio talks directly
to Apple's built-in `usbmuxd`, finds every attached iPhone, and dials the SDK's device-local listener on
port 8900. Nothing extra is bundled in the DMG and neither `iproxy` nor Homebrew is required. USB carries
traffic while connected; unplugging resumes the existing LAN client (ADR-0037). The **Devices** panel in
the right tool rail shows attached, waiting-for-app, and connected states. The listener is part of both
`WailoSDK` and `WailoSDKDebug`; the latter only adds the on-device settings UI.

If 8900 collides with something in the host app, move it — `Wailo.setUsbPort(9100)`, the debug panel, or
`-WailoUsbPort 9100` in the scheme's launch arguments — and set the same number in Studio's **Settings**.
USB has no discovery to negotiate it, so a mismatch reads as *Waiting for an app*; both surfaces print the
port they are using. The link also recovers on its own after the phone sleeps or the cable is re-seated,
with no app restart.

Without USB, a physical device finds the desktop over Bonjour — the engine advertises `_wailo._tcp`, so
nothing needs to be typed and a DHCP change fixes itself (ADR-0035). The LAN address resolves at runtime,
highest precedence first:

1. a `host` passed to `Wailo.start` — pins one machine for the process
2. a saved override — set by `Wailo.setHost("192.168.1.42")`, the debug panel, or `-WailoHost 192.168.1.42`
   in the Xcode scheme's launch arguments (editing those relaunches without recompiling)
3. Bonjour discovery
4. `localhost`

None of these need a rebuild to change. `Wailo.setHost(nil)` clears the override and hands control back
to discovery. An address is free text — `192.168.1.42`, `192.168.1.42:8899`, an IPv6 literal, or a pasted
`ws://…` all resolve, and a port written into the address wins over a separate `port` — but only if it can
actually be dialled: `setHost` returns `false` and changes nothing otherwise, so a typo can neither replace
a working address nor be persisted for the next launch to read back.

**On-device panel (`WailoSDKDebug`).** Link the `WailoSDKDebug` product *instead of* `WailoSDK` in debug
builds and a **two-finger long-press on the bottom half of the screen** opens a panel showing what the SDK
is connected to and over which transport, every desktop on the network, a field to pin one by hand, and
the USB listener port. **In the Simulator, hold Option and press anywhere for a second**: the bottom-half
restriction is lifted there because Option-click's two touches are mirrored about the screen's center, so
one of them always lands in the top half. It installs itself — linking the product is the only setup, and
it follows the same design tokens as the desktop app in light and dark (ADR-0038). Release builds link
plain `WailoSDK` and get none of it, which is how UIKit/SwiftUI stay out of the shipping interceptor.

A host that wants its own way in — a debug-menu row, a shake, a hidden button — turns the gesture off and
drives the panel directly:

```swift
import WailoDebugUI

WailoDebugUI.isGestureEnabled = false          // optional; leave it on to keep both ways in
Button("Wailo") { WailoDebugUI.present() }
```

**Host app Info.plist (LAN/Bonjour only).** The USB path does not need Local Network or ATS permission.
The Wi-Fi path needs three things the Simulator doesn't; see `sample-ios/project.yml` for the full block:

| Key | Why |
|----|----|
| `NSLocalNetworkUsageDescription` | iOS 14+ gates any outgoing connection to a LAN address behind user consent |
| `NSBonjourServices` = `[_wailo._tcp]` | browsing is denied outright unless the service type is declared |
| `NSAppTransportSecurity` | the transport is plaintext `ws://` to an IP literal, which ATS blocks by default from iOS 17 |
| `NSCameraUsageDescription` | *optional* — only to scan Studio's pairing QR (ADR-0039); without it the panel offers the typed code instead, and connecting by address needs neither |

The first connection is refused while the Local Network prompt is still on screen; the client retries
every 2s, so it connects on its own a moment after you tap Allow.

The Swift protobuf types are generated from the shared schema — regenerate after editing `protocol`:

```bash
./gradlew :protocol:generateSwiftProto  # writes sdk-ios/Sources/WailoProtocol/generated
```

There are two samples. The **SwiftUI app** is a real iOS app; its Xcode project is generated by
XcodeGen from `sample-ios/project.yml` (the `.xcodeproj` is gitignored):

```bash
brew install xcodegen                    # once
cd sample-ios && xcodegen generate       # writes WailoSampleiOS.xcodeproj
open WailoSampleiOS.xcodeproj            # then run on a Simulator from Xcode
```

The **CLI harness** runs the identical `Wailo.start()` + request path headlessly (no simulator), which
is how it's smoke-tested end-to-end against the desktop:

```bash
(cd studio && ./gradlew :desktopApp:run) # start the desktop receiver
cd sample-ios/cli && swift run wailo-sample-ios-cli
```

## Connecting over Wi-Fi

Everything above reaches Studio over loopback — the Simulator, an emulator, `adb reverse`, the USB
tunnel — and loopback is exempt from all of this, since the kernel already guarantees the peer is this
machine. Wi-Fi is what the rest of this section is for. Both SDKs implement the same handshake against
the same test vectors, each keeping its long-term key where the platform keeps secrets (the Keychain,
the Android Keystore) and finding desktops with the platform's own mDNS client (`NWBrowser`,
`NsdManager`).

A Wi-Fi peer is whoever answered an mDNS advertisement, so reaching a *new* desktop is always something
you do on purpose: type its address in the on-device panel and press **Connect**, or scan the QR from
Studio's Devices panel. Tapping a row in the discovered list only fills the address field. Discovery on
its own will reconnect to a desktop this device already knows, and to nothing else — that is what stops
a colleague's Studio on the same network from catching your traffic.

The first connection to an address you typed is taken at its word and remembered, along with the
desktop's fingerprint; every later connection to that address must present the same one. If a different
desktop answers there, the panel stops and shows both fingerprints so you can decide (ADR-0040). Either
way the session is encrypted end to end.

Turn on **Only paired devices over Wi-Fi** in Studio's Settings for a shared or untrusted network: a
device then has to scan the QR or type the code before it is let in. Devices you already trust stay
connected when you switch it on.

Which desktop gets dialled is decided the same way on both platforms, highest first: an explicit host,
then a saved override, then discovery, then `localhost`. On Android the explicit host is the `host`
argument of `Wailo.webSocketSink` and the override is `Wailo.setHost("192.168.1.42")` (`null` clears it
and hands control back to discovery); the iOS equivalents are listed above. Nothing here needs a rebuild
to change.

**The on-device panel** is where a device is pointed at a desktop, and on both platforms it ships as a
debug-only artifact so that its UI framework, QR scanner and camera permission stay out of release
builds. iOS links `WailoSDKDebug` instead of `WailoSDK` (above); Android adds one dependency, and that
is the entire setup:

```kotlin
// host app build.gradle.kts
debugImplementation("com.venbiasa.wailo:wailo-android-panel:0.1.0-SNAPSHOT")
```

That gives you two ways in, both with no host code, because opening either starts the app and the SDK
arms itself (ADR-0017): a second launcher icon labelled **Wailo**, and a **Wailo** entry in the
long-press menu on your app's own icon. A host that would rather
put it behind its own affordance calls `WailoPanel.show(context)`, or embeds the `WailoPanelScreen`
composable in a screen of its own. Both panels follow the same design tokens as Studio, in light and
dark (ADR-0038/0049).

## Kotlin Multiplatform (`sample-kmp`)

`sample-kmp` shows how a KMP app adopts Wailo. The shared module (`sample-kmp/shared`) does its
networking with Ktor and contains **no Wailo code** — capture is wired at each platform's shell:

- **Android:** `sample-kmp/androidApp` applies the `wailo-gradle-plugin` and depends on `sdk-android`.
  The plugin rewrites the `OkHttpClient.build()` *inside Ktor's OkHttp engine*, so the shared code is
  captured automatically; the app only installs a sink at startup.
- **iOS:** `sample-kmp/iosApp` is an Xcode app that links the KMP `Shared` framework and the `WailoSDK`
  Swift package, then calls `Wailo.start()` in Swift. Ktor's Darwin engine sits on `URLSession`, so the
  swizzle captures the shared code.

There is no `commonMain` Wailo API by design: `sdk-ios` is a native Swift package (ADR-0010), so it is
consumed in the Swift shell, never from Kotlin. Use Ktor's **OkHttp** engine on Android — the CIO engine
would not be auto-instrumented.

```bash
# Android
./gradlew :sample-kmp:androidApp:assembleDebug

# iOS: generate the project, then build/run from Xcode (it builds Shared.framework via Gradle)
cd sample-kmp/iosApp && xcodegen generate      # writes WailoKmpSampleiOS.xcodeproj
open WailoKmpSampleiOS.xcodeproj                # run on a Simulator
```

## License

Apache-2.0. See [LICENSE](LICENSE).
