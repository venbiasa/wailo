import Foundation

/// An opt-in on-device panel for re-pointing Wailo at a different desktop, reached by a two-finger
/// long-press on the bottom half of the screen — or by whatever affordance the host wires to `present()`.
///
/// Reached only through the `WailoSDKDebug` product (ADR-0035). The interceptor ships inside
/// third-party apps and must stay small and dependency-light (invariant #3); UI in it would link
/// UIKit/SwiftUI into every consumer and put a debug surface one bug away from a release build. Keeping
/// the panel in a product release builds never link gives that separation without a compile-time flag.
///
/// Installation needs no host code: the `WailoDebugUIAutoStart` `+load` hook calls `install()` before
/// `main`, exactly as `WailoAutoStart` does for capture itself.
public enum WailoDebugUI {

    /// Arms the gesture. Idempotent, and safe to call before `UIApplication` exists — it only subscribes
    /// to scene notifications and attaches once there is a window to attach to.
    public static func install() {
        #if canImport(UIKit)
        WailoDebugGesture.shared.install()
        #endif
    }

    /// Whether the built-in long-press opens the panel. Set it to `false` to replace the trigger with one
    /// of the host's own — a debug-menu row, a shake, a hidden button — and call `present()` from there.
    /// Settable at any point, including before the first window exists, and reversible.
    public static var isGestureEnabled: Bool {
        get {
            #if canImport(UIKit)
            return WailoDebugGesture.shared.isEnabled
            #else
            return false
            #endif
        }
        set {
            #if canImport(UIKit)
            WailoDebugGesture.shared.isEnabled = newValue
            #endif
        }
    }

    /// Opens the panel over the frontmost scene. Callable from any thread, a no-op while it is already up,
    /// and independent of `isGestureEnabled` so a host can keep both ways in. Closing stays the panel's own
    /// job via its Done button.
    public static func present() {
        #if canImport(UIKit)
        WailoDebugOverlay.shared.presentInForegroundScene()
        #endif
    }
}

/// Objective-C-callable bridge for the pre-`main` hook, resolved by runtime name from
/// `WailoDebugUIAutoStart.m`. Mirrors `WailoBootstrap`; keep the `@objc` name in sync with that string.
@objc(WailoDebugUIBootstrap)
public final class WailoDebugUIBootstrap: NSObject {

    @objc public static func install() {
        WailoDebugUI.install()
    }
}
