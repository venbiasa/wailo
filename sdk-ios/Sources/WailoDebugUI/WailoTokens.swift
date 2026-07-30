#if canImport(UIKit)
// GENERATED FROM tokens.json — DO NOT EDIT
// Source: ~/Documents/personal/projects/ai-assists/design-tokens/tokens.json (semantic layer).
// Regenerate rather than hand-editing; the studio's Compose theme is generated from the same file, which
// is the only reason the two surfaces agree in both schemes.
import SwiftUI
import UIKit

/// The semantic palette and metrics the on-device panel draws with.
///
/// Colors resolve per `userInterfaceStyle`, so light and dark are one value each rather than a branch at
/// every call site. Type uses the token scale's sizes and weights on the *system* face: Noto Sans would
/// have to be registered into the host app at runtime, and this SDK ships inside other people's apps.
enum WailoTokens {

    static let background = dyn(light: 0xFFFFFF, dark: 0x000000)
    static let onBackground = dyn(light: 0x0A0A0A, dark: 0xFAFAFA)
    static let surface = dyn(light: 0xFFFFFF, dark: 0x0A0A0A)
    static let surfaceContainer = dyn(light: 0xFAFAFA, dark: 0x171717)
    static let surfaceVariant = dyn(light: 0xF5F5F5, dark: 0x262626)
    static let onSurface = dyn(light: 0x0A0A0A, dark: 0xFAFAFA)
    static let onSurfaceVariant = dyn(light: 0x525252, dark: 0xA3A3A3)
    static let onSurfaceDisabled = dyn(light: 0xA3A3A3, dark: 0x525252)
    static let outline = dyn(light: 0xD4D4D4, dark: 0x404040)
    static let outlineVariant = dyn(light: 0xE5E5E5, dark: 0x262626)
    static let accent = dyn(light: 0x0A0A0A, dark: 0xFAFAFA)
    static let onAccent = dyn(light: 0xFFFFFF, dark: 0x000000)
    static let success = dyn(light: 0x16A34A, dark: 0x4ADE80)
    static let warning = dyn(light: 0xF59E0B, dark: 0xFBBF24)
    static let error = dyn(light: 0xDC2626, dark: 0xF87171)
    static let info = dyn(light: 0x2563EB, dark: 0x60A5FA)

    enum Spacing {
        static let x1: CGFloat = 4
        static let x2: CGFloat = 8
        static let x3: CGFloat = 12
        static let x4: CGFloat = 16
        static let x5: CGFloat = 20
        static let x6: CGFloat = 24
    }

    enum Radius {
        static let sm: CGFloat = 4
        static let md: CGFloat = 8
        static let lg: CGFloat = 12
    }

    /// Token sizes and weights, scaled by the reader's text-size setting.
    ///
    /// Computed, not stored: `UIFontMetrics` resolves against the trait collection at the moment it is
    /// asked, so a cached value would freeze the panel at whatever size was in force the first time it
    /// opened.
    enum Typography {
        static var titleMedium: Font { scaled(16, .medium, like: .headline) }
        static var titleSmall: Font { scaled(14, .medium, like: .subheadline) }
        static var bodyMedium: Font { scaled(14, .regular, like: .body) }
        static var bodySmall: Font { scaled(12, .regular, like: .footnote) }
        static var labelLarge: Font { scaled(14, .medium, like: .subheadline) }
        static var labelSmall: Font { scaled(11, .medium, like: .caption2) }
        static var monoMedium: Font { scaled(14, .regular, like: .body, monospaced: true) }

        private static func scaled(
            _ size: CGFloat,
            _ weight: UIFont.Weight,
            like style: UIFont.TextStyle,
            monospaced: Bool = false
        ) -> Font {
            let base = monospaced
                ? UIFont.monospacedSystemFont(ofSize: size, weight: weight)
                : UIFont.systemFont(ofSize: size, weight: weight)
            return Font(UIFontMetrics(forTextStyle: style).scaledFont(for: base))
        }
    }

    private static func dyn(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { traits in
            UIColor(rgb: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}

private extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}
#endif
