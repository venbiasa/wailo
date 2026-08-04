import Foundation

/// Objective-C-callable bridge to the Swift entry point, used only by the pre-`main` auto-start hook
/// (`WailoAutoStart`'s `+load`). `Wailo` is a Swift `enum` and can't be `@objc`, so this thin `NSObject`
/// exposes the one call the hook needs. The hook resolves it by name (`NSClassFromString`), so this must
/// keep the `@objc(WailoBootstrap)` runtime name in sync with the string in `WailoAutoStart.m`.
@objc(WailoBootstrap)
public final class WailoBootstrap: NSObject {

    /// Zero-config default start (localhost:8899, bundle id, device name) — the iOS counterpart of
    /// Android's `WailoStartupProvider`, including its debug gate.
    @objc public static func autoStart() {
        guard isDebuggableBuild else { return }
        Wailo.start()
    }

    /// Mirrors Android's `FLAG_DEBUGGABLE` gate rather than trusting `#if DEBUG` alone, because that
    /// flag describes how *the SDK* was compiled and the question here is about the host app: a release
    /// app linking a debug-built package would otherwise still auto-start. `get-task-allow` is the
    /// entitlement Xcode grants development builds and strips from distribution ones, so it answers the
    /// question the way the App Store sees it.
    ///
    /// Without this the hook fired in every configuration, and a shipped app would dial out and stream
    /// its users' traffic to whatever answered — the follow-up ADR-0017 left open.
    private static var isDebuggableBuild: Bool {
        #if DEBUG
        return true
        #elseif targetEnvironment(simulator)
        // The Simulator has no provisioning profile to read, and nothing ships from it.
        return true
        #else
        return provisioningProfileAllowsDebugging
        #endif
    }

    /// `SecTaskCopyValueForEntitlement` would answer this directly but exists only on macOS, so read
    /// the embedded profile instead — it is the same entitlement, just before codesign folds it in.
    /// A distribution build has either no profile at all or one with `get-task-allow` false; both read
    /// as "do not start".
    private static var provisioningProfileAllowsDebugging: Bool {
        let profile = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision")
            ?? Bundle.main.url(forResource: "embedded", withExtension: "provisionprofile")
        guard let profile,
              let raw = try? Data(contentsOf: profile),
              // The file is a signed CMS envelope wrapping a plist; the plist is the readable part.
              let text = String(data: raw, encoding: .isoLatin1),
              let start = text.range(of: "<?xml"),
              let end = text.range(of: "</plist>", range: start.upperBound..<text.endIndex) else {
            return false
        }
        let plist = text[start.lowerBound..<end.upperBound]
        guard let key = plist.range(of: "<key>get-task-allow</key>") else { return false }
        return plist[key.upperBound...]
            .drop(while: { $0.isWhitespace })
            .hasPrefix("<true/>")
    }
}
