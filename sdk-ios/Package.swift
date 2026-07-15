// swift-tools-version:5.9
import PackageDescription

// The iOS interceptor SDK. Kept isolated (invariant #3): it depends only on the Wire Swift runtime
// and Foundation — never on engine/shared/desktopApp. The protobuf types in Sources/WailoProtocol
// are generated from the shared schema via `./gradlew :protocol:generateSwiftProto`.
let package = Package(
    name: "WailoSDK",
    platforms: [
        .iOS(.v13),
        .macOS(.v10_15),
    ],
    products: [
        // Dynamic on purpose: the WailoAutoStart `+load` hook must be present in a loaded image to fire
        // before `main`. A static product could dead-strip an unreferenced `+load` (the classic Firebase
        // `-ObjC` footgun); a dynamic image is always loaded, so zero-install auto-start is reliable with
        // no host build flags — the iOS analog of Android's manifest-merged WailoStartupProvider (ADR-0009).
        .library(name: "WailoSDK", type: .dynamic, targets: ["WailoSDK", "WailoAutoStart"]),
    ],
    dependencies: [
        // Pinned to the same Wire version as gradle/libs.versions.toml so the generated code and
        // the runtime never drift. Bump both together.
        .package(url: "https://github.com/square/wire", exact: "5.3.5"),
    ],
    targets: [
        .target(
            name: "WailoProtocol",
            dependencies: [.product(name: "Wire", package: "wire")]
        ),
        .target(
            name: "WailoSDK",
            dependencies: [
                "WailoProtocol",
                // ProtoEncoder lives in the Wire runtime; the transport encodes Envelopes with it.
                .product(name: "Wire", package: "wire"),
            ]
        ),
        // Objective-C `+load` hook that auto-starts capture before `main`. Separate target because a
        // SwiftPM target is single-language; it reaches the Swift entry point by runtime name lookup, so
        // it needs no headers from WailoSDK (the dependency is only for build/link ordering).
        .target(
            name: "WailoAutoStart",
            dependencies: ["WailoSDK"]
        ),
        .testTarget(
            name: "WailoSDKTests",
            dependencies: [
                "WailoSDK",
                "WailoProtocol",
                .product(name: "Wire", package: "wire"),
            ]
        ),
    ]
)
