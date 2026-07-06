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

## Modules

| Module          | What it is                                                        |
| --------------- | ----------------------------------------------------------------- |
| `protocol`      | KMP library; protobuf schema + Wire-generated types               |
| `core`          | KMP library; capture model, sinks, transport ports, WS client     |
| `sdk-android`   | Android library; `WailoInterceptor` (OkHttp) - the injected SDK  |
| `engine`        | JVM library; WebSocket server + multi-session store + query API   |
| `shared`        | KMP; Compose Multiplatform viewer UI + view models                |
| `desktopApp`    | Compose Desktop entry point                                       |
| `sample-android`| Sample app under test, used for dogfooding/verification           |

## Requirements

- JDK 21 (the Android Studio JBR 21 works)
- Android SDK (compileSdk 36)
- Gradle is provided via the wrapper (`./gradlew`)

## Build

```bash
./gradlew projects        # list modules
./gradlew build           # build everything
./gradlew :sample-android:assembleDebug
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
./gradlew :desktopApp:run                     # start the desktop receiver (live text list)
adb reverse tcp:8899 tcp:8899                 # route device localhost:8899 -> desktop
./gradlew :sample-android:installDebug
adb shell am start -n com.venbiasa.wailo.sample/.MainActivity
```

Captured requests appear live in the desktop window. The sample already wires this up, so
running the four commands above is enough to see traffic stream across.

## Roadmap

- M0 - Repo structure / skeleton (builds empty) [done]
- M1 - SDK first: capture HTTP via OkHttp interceptor, verifiable in Logcat [done]
- M2 - Transport + engine: WebSocket over `adb reverse`, multi-session store [done]
- M3 - Desktop viewer: Compose live request inspector [current: text list done; 3-pane next, moving into `shared`]
- M4 - Polish: multi-device helper, docs
- Later - CLI (Appium), MCP server, iOS SDK, wifi/mDNS discovery, rules (breakpoint / map-local / map-remote)

## License

Apache-2.0. See [LICENSE](LICENSE).
