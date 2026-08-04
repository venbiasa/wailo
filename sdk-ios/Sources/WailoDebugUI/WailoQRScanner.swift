#if canImport(UIKit)
import AVFoundation
import SwiftUI
import UIKit

/// Why the camera can't be used, phrased as the thing the developer has to go do.
///
/// Modelled rather than collapsed to a bool because the three cases need three different actions —
/// edit a plist, tap Allow, use the typed code instead — and a scanner that just shows a black
/// rectangle for all of them is the kind of failure people file as "pairing is broken".
enum WailoCameraAvailability: Equatable {

    case ready
    /// The host app declares no `NSCameraUsageDescription`. Asking anyway would terminate the app, so
    /// this is checked *before* requesting access rather than discovered by crashing in a debug build.
    case missingUsageDescription
    case denied
    /// No capture device — the Simulator, most of the time. Not worth an apology: the Simulator reaches
    /// Studio over loopback, which never pairs.
    case unavailable

    var message: String {
        switch self {
        case .ready:
            return ""
        case .missingUsageDescription:
            return "Add NSCameraUsageDescription to this app's Info.plist to scan a pairing QR. "
                + "Until then, use the typed code below."
        case .denied:
            return "Camera access is off for this app. Turn it on in Settings, or use the typed code below."
        case .unavailable:
            return "No camera on this device. Use the typed code below."
        }
    }
}

enum WailoCamera {

    /// Whether scanning can even be offered. Deliberately does not prompt: the panel calls this while
    /// laying out, and a permission dialog thrown up by a layout pass is its own bug.
    static var availability: WailoCameraAvailability {
        guard Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") != nil else {
            return .missingUsageDescription
        }
        guard AVCaptureDevice.default(for: .video) != nil else { return .unavailable }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .denied, .restricted: return .denied
        default: return .ready
        }
    }

    /// Prompts if the user has not been asked yet. Safe only because [availability] has already
    /// established that the usage description exists.
    static func requestAccess(_ completion: @escaping (Bool) -> Void) {
        AVCaptureDevice.requestAccess(for: .video) { granted in
            DispatchQueue.main.async { completion(granted) }
        }
    }
}

/// A live camera preview that reports the first QR it reads.
///
/// Scoped to `wailo://` payloads: the panel is often opened in an app whose own screens are covered in
/// unrelated barcodes, and reporting one of those as a failed pairing is worse than ignoring it.
struct WailoQRScanner: UIViewControllerRepresentable {

    let onScan: (String) -> Void

    func makeUIViewController(context: Context) -> WailoScannerController {
        WailoScannerController(onScan: onScan)
    }

    func updateUIViewController(_ controller: WailoScannerController, context: Context) {
        controller.onScan = onScan
    }
}

final class WailoScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {

    var onScan: (String) -> Void

    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    // One scan per presentation. The metadata callback fires every frame the code is in view, and
    // without this a single QR would re-pair dozens of times while the user lowers the phone.
    private var handled = false

    init(onScan: @escaping (String) -> Void) {
        self.onScan = onScan
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureSession()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        handled = false
        start()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        // Leaving the camera running behind a dismissed panel keeps the privacy indicator lit in an
        // app that is not, as far as its user can tell, using the camera.
        stop()
    }

    private func configureSession() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        // Set only after the output is attached; the available types are empty before that, so an
        // earlier assignment throws.
        output.metadataObjectTypes = [.qr]

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        preview = layer
    }

    private func start() {
        guard !session.isRunning else { return }
        // `startRunning` blocks until the device is configured, which is long enough to drop frames
        // off the main thread if it is called there.
        DispatchQueue.global(qos: .userInitiated).async { [session] in session.startRunning() }
    }

    private func stop() {
        guard session.isRunning else { return }
        DispatchQueue.global(qos: .userInitiated).async { [session] in session.stopRunning() }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput objects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !handled else { return }
        let payload = objects
            .compactMap { ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }
            .first { $0.hasPrefix("wailo://") }
        guard let payload else { return }
        handled = true
        stop()
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onScan(payload)
    }
}
#endif
