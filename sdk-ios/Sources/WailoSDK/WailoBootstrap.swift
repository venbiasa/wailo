import Foundation

/// Objective-C-callable bridge to the Swift entry point, used only by the pre-`main` auto-start hook
/// (`WailoAutoStart`'s `+load`). `Wailo` is a Swift `enum` and can't be `@objc`, so this thin `NSObject`
/// exposes the one call the hook needs. The hook resolves it by name (`NSClassFromString`), so this must
/// keep the `@objc(WailoBootstrap)` runtime name in sync with the string in `WailoAutoStart.m`.
@objc(WailoBootstrap)
public final class WailoBootstrap: NSObject {

    /// Zero-config default start (localhost:8899, bundle id, device name) — the iOS counterpart of
    /// Android's `WailoStartupProvider`. `Wailo.start()` is idempotent, so a later explicit
    /// `Wailo.start(host:)` from the host cleanly replaces this default rather than double-connecting.
    ///
    /// Not yet gated to debug builds (Android gates on `FLAG_DEBUGGABLE`); tightening this to match is a
    /// follow-up before any release consumer ships it.
    @objc public static func autoStart() {
        Wailo.start()
    }
}
