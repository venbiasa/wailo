import SwiftUI
import WailoSDK

@main
struct WailoSampleApp: App {
    init() {
        // Start Wailo once at launch — the same call a production app makes in its App/AppDelegate.
        // No `host:` on purpose: leaving it unset lets the SDK resolve the desktop at runtime (saved
        // override, then Bonjour, then localhost for the Simulator), so a physical device needs no
        // rebuild when DHCP moves the Mac. Two-finger long-press the bottom half (Option-press in the
        // Simulator) to override it.
        Wailo.start(appId: "com.venbiasa.wailo.sampleios", deviceName: "wailo-sample-ios")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
