import SwiftUI
import WailoSDK

@main
struct WailoKmpSampleApp: App {
    init() {
        // Wailo is wired in the iOS shell, not in shared Kotlin. Because the shared
        // module's Ktor Darwin engine sits on URLSession, this single call captures its traffic too.
        // The desktop's address is resolved at runtime (Bonjour, or an override set from the debug
        // panel), so no host is pinned here.
        Wailo.start(appId: "com.venbiasa.wailo.sample.kmp.ios", deviceName: "wailo-kmp-sample")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
