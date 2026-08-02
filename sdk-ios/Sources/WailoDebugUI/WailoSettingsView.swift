#if canImport(UIKit)
import SwiftUI
import WailoSDK

/// The on-device panel: what the SDK is connected to, and the two ways to change it.
///
/// Ordered by the question being asked — what is happening right now, then how to reach the desktop over
/// Wi-Fi, then the USB port — because every one of these can explain "no traffic in Studio" and the user
/// is reading top to bottom looking for which.
///
/// Hand-built rather than a `Form`: the grouped list paints itself from UIKit's system palette, which no
/// amount of per-row styling overrides, and the panel has to match the studio's tokens in both schemes.
/// Everything here draws from [WailoTokens]; nothing takes a system color.
struct WailoSettingsView: View {

    @ObservedObject var model: WailoDebugModel
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: WailoTokens.Spacing.x5) {
                    section("Status") { statusCard }
                    section("Found on this network") { discoveryCard }
                    section("Manual address") { manualCard }
                    section("USB") { usbCard }
                }
                .padding(WailoTokens.Spacing.x4)
            }
        }
        .background(WailoTokens.background.edgesIgnoringSafeArea(.all))
    }

    /// The band bleeds into the top safe area: the root `VStack` is laid out inside it, so a background
    /// stopping at the inset would leave the status bar painted in the page's `background` — a visible
    /// strip above the bar, stark in dark mode where the two tokens are `#000000` and `#171717`.
    private var header: some View {
        HStack {
            Text("Wailo")
                .font(WailoTokens.Typography.titleMedium)
                .foregroundColor(WailoTokens.onSurface)
            Spacer()
            Button(action: onClose) {
                Text("Done")
                    .font(WailoTokens.Typography.labelLarge)
                    .foregroundColor(WailoTokens.accent)
            }
            .buttonStyle(PlainButtonStyle())
        }
        .padding(.horizontal, WailoTokens.Spacing.x4)
        .padding(.vertical, WailoTokens.Spacing.x3)
        .background(WailoTokens.surfaceContainer.edgesIgnoringSafeArea(.top))
        .overlay(Hairline(), alignment: .bottom)
    }

    private var statusCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: WailoTokens.Spacing.x3) {
                    Circle()
                        .fill(statusColor)
                        .frame(width: 10, height: 10)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.statusTitle)
                            .font(WailoTokens.Typography.titleSmall)
                            .foregroundColor(WailoTokens.onSurface)
                        Text(model.activeAddress)
                            .font(WailoTokens.Typography.monoMedium)
                            .foregroundColor(WailoTokens.onSurfaceVariant)
                    }
                    Spacer()
                    model.transportLabel.map(Chip.init)
                }
                .padding(WailoTokens.Spacing.x4)

                Hairline()

                Text(model.statusDetail)
                    .font(WailoTokens.Typography.bodySmall)
                    .foregroundColor(WailoTokens.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(WailoTokens.Spacing.x4)
            }
        }
    }

    private var statusColor: Color {
        if !model.isStarted { return WailoTokens.onSurfaceDisabled }
        return model.isConnected ? WailoTokens.success : WailoTokens.warning
    }

    private var discoveryCard: some View {
        Card {
            if model.discovered.isEmpty {
                Text("Nothing yet. Wailo Studio has to be running on the same Wi-Fi, and this app needs Local Network permission.")
                    .font(WailoTokens.Typography.bodySmall)
                    .foregroundColor(WailoTokens.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(WailoTokens.Spacing.x4)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(model.discovered) { service in
                        if service.id != model.discovered.first?.id { Hairline() }
                        Button(action: { model.pin(service) }) {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(service.name)
                                        .font(WailoTokens.Typography.bodyMedium)
                                        .foregroundColor(WailoTokens.onSurface)
                                    Text(service.address)
                                        .font(WailoTokens.Typography.monoMedium)
                                        .foregroundColor(WailoTokens.onSurfaceVariant)
                                }
                                Spacer()
                                Text("Use")
                                    .font(WailoTokens.Typography.labelSmall)
                                    .foregroundColor(WailoTokens.accent)
                            }
                            .padding(WailoTokens.Spacing.x4)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(PlainButtonStyle())
                    }
                }
            }
        }
    }

    private var manualCard: some View {
        Card {
            VStack(alignment: .leading, spacing: WailoTokens.Spacing.x3) {
                HStack(alignment: .top, spacing: WailoTokens.Spacing.x3) {
                    LabeledField(
                        label: "Desktop IP",
                        placeholder: "192.168.1.20",
                        text: $model.host,
                        keyboard: .numbersAndPunctuation
                    )
                    LabeledField(
                        label: "Port",
                        placeholder: String(Wailo.defaultPort),
                        text: $model.port,
                        keyboard: .numberPad
                    )
                    .frame(width: 84)
                }
                HStack(spacing: WailoTokens.Spacing.x2) {
                    ActionButton(title: "Connect", enabled: model.canApply, action: model.apply)
                    ActionButton(
                        title: "Use discovery",
                        style: .ghost,
                        enabled: !model.isUsingDiscovery,
                        action: model.useDiscovery
                    )
                }
                Text("Pinning an address turns Bonjour off. Clear it to hand discovery back control.")
                    .font(WailoTokens.Typography.bodySmall)
                    .foregroundColor(WailoTokens.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(WailoTokens.Spacing.x4)
        }
    }

    private var usbCard: some View {
        Card {
            VStack(alignment: .leading, spacing: WailoTokens.Spacing.x3) {
                HStack(alignment: .bottom, spacing: WailoTokens.Spacing.x3) {
                    LabeledField(
                        label: "Listener port",
                        placeholder: String(Wailo.defaultUsbPort),
                        text: $model.usbPort,
                        keyboard: .numberPad
                    )
                    .frame(width: 110)
                    ActionButton(title: "Apply", enabled: model.canApplyUsbPort, action: model.applyUsbPort)
                }
                Text("Wailo Studio dials this port over the cable and takes over from Wi-Fi while it is plugged in. Set the same test in Studio's settings — USB has no discovery, so a mismatch looks like the app isn't running.")
                    .font(WailoTokens.Typography.bodySmall)
                    .foregroundColor(WailoTokens.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(WailoTokens.Spacing.x4)
        }
    }

    private func section<Content: View>(
        _ title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: WailoTokens.Spacing.x2) {
            Text(title.uppercased())
                .font(WailoTokens.Typography.labelSmall)
                .foregroundColor(WailoTokens.onSurfaceVariant)
                .padding(.leading, WailoTokens.Spacing.x1)
            content()
        }
    }
}

// MARK: - building blocks

private struct Card<Content: View>: View {

    @ViewBuilder let content: Content

    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(WailoTokens.surface)
            .cornerRadius(WailoTokens.Radius.lg)
            .overlay(
                RoundedRectangle(cornerRadius: WailoTokens.Radius.lg)
                    .stroke(WailoTokens.outlineVariant, lineWidth: 1)
            )
    }
}

private struct Hairline: View {
    var body: some View {
        Rectangle()
            .fill(WailoTokens.outlineVariant)
            .frame(height: 1)
    }
}

private struct Chip: View {

    let title: String

    var body: some View {
        Text(title)
            .font(WailoTokens.Typography.labelSmall)
            .foregroundColor(WailoTokens.onSurfaceVariant)
            .padding(.horizontal, WailoTokens.Spacing.x2)
            .padding(.vertical, WailoTokens.Spacing.x1)
            .background(WailoTokens.surfaceVariant)
            .cornerRadius(WailoTokens.Radius.sm)
    }
}

/// A text field that stays on the palette: SwiftUI's placeholder is a system gray, so it is drawn here
/// instead of passed to `TextField`.
///
/// Outline-only over the card, matching the studio's compact fields. A filled box would draw the input in
/// `surfaceVariant` behind `onSurfaceDisabled` — the exact pair `ActionButton` uses for its *disabled*
/// state — so a field awaiting input reads as one that refuses it. The focus ring carries the affordance
/// instead, and the caret is tinted off the system blue for the same reason the rest of the panel is.
private struct LabeledField: View {

    let label: String
    let placeholder: String
    @Binding var text: String
    let keyboard: UIKeyboardType

    @State private var editing = false

    var body: some View {
        VStack(alignment: .leading, spacing: WailoTokens.Spacing.x1) {
            Text(label)
                .font(WailoTokens.Typography.labelSmall)
                .foregroundColor(WailoTokens.onSurfaceVariant)
            ZStack(alignment: .leading) {
                if text.isEmpty {
                    Text(placeholder)
                        .font(WailoTokens.Typography.monoMedium)
                        .foregroundColor(WailoTokens.onSurfaceVariant)
                }
                // `onEditingChanged` rather than `@FocusState`, which needs iOS 15; this ships to iOS 13.
                TextField("", text: $text, onEditingChanged: { editing = $0 })
                    .font(WailoTokens.Typography.monoMedium)
                    .foregroundColor(WailoTokens.onSurface)
                    .keyboardType(keyboard)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                    .accentColor(WailoTokens.accent)
            }
            .padding(.horizontal, WailoTokens.Spacing.x3)
            .padding(.vertical, WailoTokens.Spacing.x2)
            .background(WailoTokens.surface)
            .cornerRadius(WailoTokens.Radius.sm)
            .overlay(
                RoundedRectangle(cornerRadius: WailoTokens.Radius.sm)
                    .strokeBorder(
                        editing ? WailoTokens.accent : WailoTokens.outline,
                        lineWidth: editing ? 2 : 1
                    )
            )
        }
    }
}

private struct ActionButton: View {

    enum Style { case primary, ghost }

    let title: String
    var style: Style = .primary
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(WailoTokens.Typography.labelLarge)
                .foregroundColor(foreground)
                .padding(.horizontal, WailoTokens.Spacing.x4)
                .padding(.vertical, WailoTokens.Spacing.x3)
                .frame(maxWidth: .infinity)
                .background(background)
                .cornerRadius(WailoTokens.Radius.md)
                .overlay(
                    RoundedRectangle(cornerRadius: WailoTokens.Radius.md)
                        .stroke(style == .ghost ? WailoTokens.outline : Color.clear, lineWidth: 1)
                )
        }
        .buttonStyle(PlainButtonStyle())
        .disabled(!enabled)
    }

    private var foreground: Color {
        guard enabled else { return WailoTokens.onSurfaceDisabled }
        return style == .primary ? WailoTokens.onAccent : WailoTokens.onSurface
    }

    private var background: Color {
        switch style {
        case .primary:
            return enabled ? WailoTokens.accent : WailoTokens.surfaceVariant
        case .ghost:
            return WailoTokens.surface
        }
    }
}
#endif
