# Waylay

In-app network inspection for mobile. Waylay is an interceptor **SDK** you drop into an
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
[App under test] -> Waylay SDK (Android/iOS) --(protobuf over WebSocket)--> [engine] -> desktop UI / CLI / MCP
```

## Modules

| Module          | What it is                                                        |
| --------------- | ----------------------------------------------------------------- |
| `protocol`      | KMP library; protobuf schema + Wire-generated types               |
| `core`          | KMP library; capture model, sinks, transport ports, WS client     |
| `sdk-android`   | Android library; `WaylayInterceptor` (OkHttp) - the injected SDK  |
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

## Roadmap

- M0 - Repo structure / skeleton (builds empty) [current]
- M1 - SDK first: capture HTTP via OkHttp interceptor, verifiable in Logcat
- M2 - Transport + engine: WebSocket over `adb reverse`, multi-session store
- M3 - Desktop viewer: Compose 3-pane live request inspector
- M4 - Polish: multi-device helper, docs
- Later - CLI (Appium), MCP server, iOS SDK, wifi/mDNS discovery, rules (breakpoint / map-local / map-remote)

## License

Apache-2.0. See [LICENSE](LICENSE).
