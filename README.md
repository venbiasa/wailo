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
adb reverse tcp:8899 tcp:8899                 # route device localhost:8899 -> desktop
./gradlew :sample-android:installDebug
adb shell am start -n com.venbiasa.wailo.sample/.MainActivity
```

Captured requests appear live in the desktop window. The sample already wires this up, so
running the four commands above is enough to see traffic stream across.

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
streams the same protobuf `Envelope`s to the desktop. Add it and start it once at launch:

```swift
// Package.swift
.package(path: "../sdk-ios")            // or a git URL once published

// App / AppDelegate, as early as possible
import WailoSDK

Wailo.start(appId: Bundle.main.bundleIdentifier ?? "app", deviceName: "iPhone")
```

`URLSession.shared` is captured immediately; sessions built by third-party libraries are captured too,
because `start()` swizzles `URLSessionConfiguration.default`/`.ephemeral` (ADR-0009). For a session you
build before `start()`, call `Wailo.instrument(configuration)`.

**Reaching the desktop (iOS has no `adb reverse`).** The Simulator shares the Mac's network stack, so
the default `localhost:8899` reaches the desktop engine with no forwarding. For a physical device, pass
the Mac's LAN IP:

```swift
Wailo.start(host: "192.168.1.42")       // Mac's LAN IP; device on the same WiFi
```

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
