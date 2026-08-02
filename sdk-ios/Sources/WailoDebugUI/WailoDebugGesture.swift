#if canImport(UIKit)
import UIKit

/// Opens the settings panel on a two-finger long-press in the bottom half of the screen — anywhere on
/// screen in the Simulator, for the reason on `shouldReceive` below.
///
/// The gesture hangs off the *host's* own window rather than an overlay of ours, which is what keeps
/// this free of private API. A quick action would have been the more discoverable affordance, but
/// receiving one requires a scene delegate: with UIScene, `didFinishLaunchingWithOptions` launch
/// options are always nil, so a library can only intercept by swizzling — and under SwiftUI that means
/// swizzling a private internal delegate class. `addGestureRecognizer` on someone else's window is
/// plain public API by comparison.
///
/// Observing without interfering is the whole trick: `cancelsTouchesInView = false` keeps every touch
/// flowing to the host, and the delegate allows simultaneous recognition so the host's own recognizers
/// are never blocked. A false positive costs a panel you didn't ask for, never a swallowed tap.
final class WailoDebugGesture: NSObject, UIGestureRecognizerDelegate {

    static let shared = WailoDebugGesture()

    private static let touchCount = 2
    private static let pressDuration: TimeInterval = 1.0

    private var installed = false
    /// Off leaves the recognizers attached but starves them of touches, so a host that swapped in its own
    /// affordance can flip back at any time and windows that appeared meanwhile are still covered.
    var isEnabled = true
    /// Weak so a dismissed window doesn't keep us re-checking it, and so re-attachment is idempotent.
    private let attached = NSHashTable<UIWindow>.weakObjects()

    func install() {
        guard !installed else { return }
        installed = true

        let center = NotificationCenter.default
        // `+load` runs before any scene exists, so subscribe now and attach when windows appear. Hosts
        // that swap their key window later are covered by the second observer.
        center.addObserver(
            self,
            selector: #selector(sceneDidActivate(_:)),
            name: UIScene.didActivateNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(windowDidBecomeVisible(_:)),
            name: UIWindow.didBecomeVisibleNotification,
            object: nil
        )
    }

    @objc private func sceneDidActivate(_ notification: Notification) {
        guard let scene = notification.object as? UIWindowScene else { return }
        for window in scene.windows { attach(to: window) }
    }

    @objc private func windowDidBecomeVisible(_ notification: Notification) {
        guard let window = notification.object as? UIWindow else { return }
        attach(to: window)
    }

    private func attach(to window: UIWindow) {
        guard !(window is WailoDebugWindow), !attached.contains(window) else { return }
        attached.add(window)

        let recognizer = UILongPressGestureRecognizer(target: self, action: #selector(handle(_:)))
        recognizer.numberOfTouchesRequired = Self.touchCount
        recognizer.minimumPressDuration = Self.pressDuration
        recognizer.cancelsTouchesInView = false
        recognizer.delaysTouchesBegan = false
        recognizer.delaysTouchesEnded = false
        recognizer.delegate = self
        window.addGestureRecognizer(recognizer)
    }

    @objc private func handle(_ recognizer: UILongPressGestureRecognizer) {
        guard recognizer.state == .began,
              let scene = (recognizer.view as? UIWindow)?.windowScene
        else { return }
        WailoDebugOverlay.shared.present(in: scene)
    }

    // MARK: - UIGestureRecognizerDelegate

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
    ) -> Bool {
        true
    }

    /// Bottom half only. The top of the screen carries the status bar, nav bars, and iPad's multitasking
    /// pill, so ignoring it trims the remaining chance of colliding with something the host cares about.
    ///
    /// The Simulator is exempt because there the filter doesn't make the gesture awkward, it makes it
    /// unreachable: Option-clicking synthesizes two touches mirrored about the *screen's* center, so one of
    /// them always lands in the top half, and Option-Shift-dragging the pair down into the bottom half
    /// travels further than a long press's `allowableMovement` allows. No mouse can satisfy both.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard isEnabled else { return false }
        #if targetEnvironment(simulator)
        return true
        #else
        guard let view = gestureRecognizer.view else { return false }
        return touch.location(in: view).y >= view.bounds.midY
        #endif
    }
}
#endif
