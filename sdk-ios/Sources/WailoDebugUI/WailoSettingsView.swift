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
                    section("Wi-Fi pairing") { pairingCard }
                    section("Found on this network") { discoveryCard }
                    section("Manual address") { manualCard }
                    section("USB") { usbCard }
                }
                .padding(WailoTokens.Spacing.x4)
            }
        }
        .background(WailoTokens.background.edgesIgnoringSafeArea(.all))
        .sheet(isPresented: $model.isScanning) {
            WailoScannerSheet(
                onScan: model.scanned,
                onCancel: { model.isScanning = false }
            )
        }
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

    /// Wi-Fi is the one transport whose peer is whoever answered an mDNS advertisement, so it is the
    /// one that has to be paired (ADR-0039). This card sits directly under Status because an unpaired
    /// device on Wi-Fi looks exactly like a device that cannot find the desktop, and this is the
    /// answer to both.
    private var pairingCard: some View {
        Card {
            VStack(alignment: .leading, spacing: WailoTokens.Spacing.x3) {
                if let refusal = model.refusal {
                    Notice(text: refusal, tone: .warning) {
                        ActionButton(title: "Try again", style: .ghost, enabled: true, action: model.retryAfterRefusal)
                    }
                }

                if let change = model.identityChange {
                    IdentityChangeNotice(
                        change: change,
                        onAccept: model.acceptIdentityChange,
                        onReject: model.rejectIdentityChange
                    )
                }

                if model.pairings.isEmpty {
                    Text("No desktop trusted yet. Connect to one by address below and it is remembered, or scan the QR from Studio's Devices panel to prove it up front.")
                        .font(WailoTokens.Typography.bodySmall)
                        .foregroundColor(WailoTokens.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    ForEach(model.pairings, id: \.studioId) { pairing in
                        PairedRow(pairing: pairing) { model.forget(pairing) }
                    }
                }

                pairingForm

                if let message = model.pairingError {
                    Text(message)
                        .font(WailoTokens.Typography.bodySmall)
                        .foregroundColor(WailoTokens.error)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Text("Only Wi-Fi needs this. USB and the Simulator reach Studio through this machine, so they connect without pairing.")
                    .font(WailoTokens.Typography.bodySmall)
                    .foregroundColor(WailoTokens.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(WailoTokens.Spacing.x4)
        }
    }

    /// Pairing proper — the stronger path, where the desktop proves itself before the first byte
    /// instead of being taken at its word. Split out of `pairingCard` to keep either body inside what
    /// the SwiftUI type-checker will chew through in reasonable time.
    @ViewBuilder
    private var pairingForm: some View {
        ActionButton(title: "Scan pairing QR", enabled: true, action: model.startScanning)

        Text("or type the code Studio is showing")
            .font(WailoTokens.Typography.labelSmall)
            .foregroundColor(WailoTokens.onSurfaceVariant)

        HStack(alignment: .bottom, spacing: WailoTokens.Spacing.x3) {
            LabeledField(
                label: "Pairing code",
                placeholder: "ABCDE FGHJK",
                text: $model.pairingCode,
                keyboard: .asciiCapable
            )
            ActionButton(
                title: "Pair",
                style: .ghost,
                enabled: model.canPairWithCode,
                action: model.pairWithCode
            )
            .frame(width: 96)
        }

        // A code says nothing about who is offering it, so the desktop has to be named. Only shown
        // when the choice is real — with one candidate it is already made.
        if model.pairableDesktops.count > 1 {
            ForEach(model.pairableDesktops) { desktop in
                CodeTargetRow(
                    name: desktop.name,
                    selected: desktop.studioId == model.codeTarget?.studioId
                ) {
                    model.codeTarget = desktop
                }
            }
        }
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
                        Button(action: { model.fill(from: service) }) {
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
                                Text("Fill")
                                    .font(WailoTokens.Typography.labelSmall)
                                    .foregroundColor(WailoTokens.accent)
                            }
                            .padding(WailoTokens.Spacing.x4)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(PlainButtonStyle())
                    }
                    Hairline()
                    Text("Tapping fills the address below — connecting is still up to you.")
                        .font(WailoTokens.Typography.bodySmall)
                        .foregroundColor(WailoTokens.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(WailoTokens.Spacing.x4)
                }
            }
        }
    }

    /// Connect can't report a verdict synchronously, so the button says what it started and this line
    /// says where that got to.
    @ViewBuilder
    private var attemptFeedback: some View {
        let described: (String, Color)? = {
            switch model.attempt {
            case .idle:
                return nil
            case let .dialling(host):
                return ("Dialling \(host)…", WailoTokens.onSurfaceVariant)
            case let .connected(host):
                return ("Connected to \(host).", WailoTokens.success)
            case let .stopped(host):
                return ("Not connected to \(host). Check Studio is running and on this Wi-Fi.", WailoTokens.warning)
            }
        }()
        if let described {
            Text(described.0)
                .font(WailoTokens.Typography.bodySmall)
                .foregroundColor(described.1)
                .fixedSize(horizontal: false, vertical: true)
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
                        keyboard: .numbersAndPunctuation,
                        isInvalid: model.hostError != nil
                    )
                    LabeledField(
                        label: "Port",
                        placeholder: String(Wailo.defaultPort),
                        text: $model.port,
                        keyboard: .numberPad,
                        isInvalid: model.portError != nil
                    )
                    .frame(width: 84)
                }
                if let message = model.hostError ?? model.portError {
                    Text(message)
                        .font(WailoTokens.Typography.bodySmall)
                        .foregroundColor(WailoTokens.error)
                        .fixedSize(horizontal: false, vertical: true)
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
                attemptFeedback
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

private struct CodeTargetRow: View {

    let name: String
    let selected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: WailoTokens.Spacing.x2) {
                Circle()
                    .strokeBorder(WailoTokens.outline, lineWidth: 1)
                    .background(Circle().fill(selected ? WailoTokens.accent : Color.clear))
                    .frame(width: 12, height: 12)
                Text(name)
                    .font(WailoTokens.Typography.bodySmall)
                    .foregroundColor(WailoTokens.onSurface)
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PlainButtonStyle())
    }
}

private struct PairedRow: View {

    let pairing: WailoPairing
    let onForget: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: WailoTokens.Spacing.x3) {
            VStack(alignment: .leading, spacing: 2) {
                Text(pairing.lastHost.isEmpty ? "Paired desktop" : pairing.lastHost)
                    .font(WailoTokens.Typography.bodyMedium)
                    .foregroundColor(WailoTokens.onSurface)
                // The fingerprint is what actually identifies the Studio, and it is the only thing the
                // user can compare against what Studio shows when two desktops look alike.
                Text(pairing.studioId)
                    .font(WailoTokens.Typography.monoMedium)
                    .foregroundColor(WailoTokens.onSurfaceVariant)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(pairing.trustedOnFirstUse ? "Trusted on first contact" : "Paired")
                    .font(WailoTokens.Typography.labelSmall)
                    .foregroundColor(WailoTokens.onSurfaceVariant)
                if pairing.refused {
                    Text("This desktop no longer recognises this device.")
                        .font(WailoTokens.Typography.labelSmall)
                        .foregroundColor(WailoTokens.warning)
                }
            }
            Spacer()
            Button(action: onForget) {
                Text("Forget")
                    .font(WailoTokens.Typography.labelSmall)
                    .foregroundColor(WailoTokens.accent)
            }
            .buttonStyle(PlainButtonStyle())
        }
    }
}

/// A short message with its own action, tinted by severity. Boxed rather than inline so it reads as
/// something that happened, not as more help text.
private struct Notice<Action: View>: View {

    enum Tone { case warning, error }

    let text: String
    let tone: Tone
    @ViewBuilder let action: Action

    var body: some View {
        VStack(alignment: .leading, spacing: WailoTokens.Spacing.x2) {
            Text(text)
                .font(WailoTokens.Typography.bodySmall)
                .foregroundColor(tone == .warning ? WailoTokens.warning : WailoTokens.error)
                .fixedSize(horizontal: false, vertical: true)
            action
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(WailoTokens.Spacing.x3)
        .background(WailoTokens.surfaceVariant)
        .cornerRadius(WailoTokens.Radius.md)
    }
}

/// The SSH "host key has changed" moment (ADR-0040): the address is right, the machine answering it is
/// not the one this device trusted. Shows both fingerprints because that is the only thing the user can
/// actually check — against what Studio prints in its own Settings — and refuses to guess on their
/// behalf, since a Mac that changed hands and someone standing in the path look identical from here.
private struct IdentityChangeNotice: View {

    let change: WailoIdentityChange
    let onAccept: () -> Void
    let onReject: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: WailoTokens.Spacing.x2) {
            Text("A different desktop is answering at \(change.host).")
                .font(WailoTokens.Typography.bodySmall)
                .foregroundColor(WailoTokens.error)
                .fixedSize(horizontal: false, vertical: true)
            fingerprint(label: "Trusted", value: change.expected)
            fingerprint(label: "Answering now", value: change.actual)
            Text("Accept only if you expected this desktop to change — otherwise someone may be sitting between you and it.")
                .font(WailoTokens.Typography.bodySmall)
                .foregroundColor(WailoTokens.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: WailoTokens.Spacing.x2) {
                ActionButton(title: "Accept new desktop", enabled: true, action: onAccept)
                ActionButton(title: "Keep the old one", style: .ghost, enabled: true, action: onReject)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(WailoTokens.Spacing.x3)
        .background(WailoTokens.surfaceVariant)
        .cornerRadius(WailoTokens.Radius.md)
    }

    private func fingerprint(label: String, value: String) -> some View {
        HStack(alignment: .top, spacing: WailoTokens.Spacing.x2) {
            Text(label)
                .font(WailoTokens.Typography.labelSmall)
                .foregroundColor(WailoTokens.onSurfaceVariant)
                .frame(width: 104, alignment: .leading)
            Text(value)
                .font(WailoTokens.Typography.monoMedium)
                .foregroundColor(WailoTokens.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Full-screen camera with a way out. Presented as a sheet so dismissing it tears the capture session
/// down with the controller rather than leaving the camera live behind the panel.
private struct WailoScannerSheet: View {

    let onScan: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        ZStack(alignment: .top) {
            WailoQRScanner(onScan: onScan)
                .edgesIgnoringSafeArea(.all)
            HStack {
                Text("Scan Studio's pairing QR")
                    .font(WailoTokens.Typography.titleSmall)
                    .foregroundColor(.white)
                Spacer()
                Button(action: onCancel) {
                    Text("Cancel")
                        .font(WailoTokens.Typography.labelLarge)
                        .foregroundColor(.white)
                }
                .buttonStyle(PlainButtonStyle())
            }
            .padding(WailoTokens.Spacing.x4)
            // Fixed white on a scrim rather than tokens: this sits over a camera feed, not over the
            // panel's background, so the light scheme's near-black text would be unreadable.
            .background(Color.black.opacity(0.55))
        }
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
    var isInvalid = false

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
                    .strokeBorder(borderColor, lineWidth: editing || isInvalid ? 2 : 1)
            )
        }
    }

    /// Editing outranks the rejection: the ring has to follow the caret while the user fixes the value
    /// the message is complaining about.
    private var borderColor: Color {
        if editing { return WailoTokens.accent }
        return isInvalid ? WailoTokens.error : WailoTokens.outline
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
