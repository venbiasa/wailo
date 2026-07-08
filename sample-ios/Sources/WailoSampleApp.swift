import SwiftUI
import WailoSDK

@main
struct WailoSampleApp: App {
    init() {
        // Start Wailo once at launch — the same call a production app makes in its App/AppDelegate.
        // From the Simulator, localhost:8899 reaches the desktop engine directly (iOS has no
        // adb-reverse); for a physical device pass the Mac's LAN IP via `host:`.
        Wailo.start(appId: "com.venbiasa.wailo.sampleios", deviceName: "wailo-sample-ios")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
