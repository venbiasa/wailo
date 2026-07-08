// swift-tools-version:5.9
import PackageDescription

// Headless CI smoke harness for the iOS SDK: exercises the whole capture->stream path from the
// command line (no simulator needed). The SwiftUI app in ../ makes the identical `Wailo.start()`
// call inside a real iOS app; this target keeps the path runnable where no simulator is available.
let package = Package(
    name: "wailo-sample-ios-cli",
    platforms: [
        .macOS(.v10_15),
    ],
    dependencies: [
        .package(path: "../../sdk-ios"),
    ],
    targets: [
        .executableTarget(
            name: "wailo-sample-ios-cli",
            dependencies: [.product(name: "WailoSDK", package: "sdk-ios")]
        ),
    ]
)
