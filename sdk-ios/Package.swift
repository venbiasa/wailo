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
        .library(name: "WailoSDK", targets: ["WailoSDK"]),
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
