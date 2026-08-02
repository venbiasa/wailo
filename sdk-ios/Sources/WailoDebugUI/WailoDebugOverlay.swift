#if canImport(UIKit)
import SwiftUI
import UIKit

/// Marker subclass so the gesture installer can tell our own window from the host's and not arm the
/// trigger on the panel it just opened.
final class WailoDebugWindow: UIWindow {}

/// Hosts the settings panel in a window of our own, above the host app's.
///
/// A window rather than a presented view controller because the SDK has no idea what the host is
/// showing — there may be no reachable top view controller, or one already presenting something. Owning
/// the window sidesteps that entirely. It is only alive while the panel is up, so there is nothing to
/// pass touches through the rest of the time.
final class WailoDebugOverlay {

    static let shared = WailoDebugOverlay()

    private var window: WailoDebugWindow?
    private var model: WailoDebugModel?

    func present(in scene: UIWindowScene) {
        guard window == nil else { return }

        let model = WailoDebugModel()
        let root = UIHostingController(
            rootView: WailoSettingsView(model: model, onClose: { [weak self] in self?.dismiss() })
        )
        let window = WailoDebugWindow(windowScene: scene)
        window.windowLevel = .alert + 1
        window.rootViewController = root
        window.makeKeyAndVisible()

        self.model = model
        self.window = window
    }

    /// The entry point for a host driving the panel itself: the gesture learns its scene from the window it
    /// fired on, while `WailoDebugUI.present()` has none to name. Hops to the main thread rather than trust
    /// the caller, since a debug hook is as likely to hang off a background callback as off a button.
    func presentInForegroundScene() {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { self.presentInForegroundScene() }
            return
        }
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first
        else { return }
        present(in: scene)
    }

    func dismiss() {
        window?.isHidden = true
        window = nil
        model = nil
    }
}
#endif
