import Foundation

/// Where the desktop's address comes from when the host app doesn't pass one to `Wailo.start`.
///
/// Backed by `UserDefaults` for two reasons at once. It survives relaunches, so a device pointed at a
/// Mac stays pointed at it — the whole point of ADR-0035 is that changing the address must not need a
/// rebuild. And because `UserDefaults` folds `-WailoHost <ip>` launch arguments into its argument
/// domain, the same key doubles as an Xcode-scheme override with no extra code: editing scheme
/// arguments relaunches without recompiling.
///
/// `nil` means "discover it" (see `WailoDiscovery`) and is the default, so a fresh install on a
/// physical device finds the desktop without anyone typing an IP.
enum WailoHostStore {

    static let hostKey = "WailoHost"
    static let portKey = "WailoPort"
    static let usbPortKey = "WailoUsbPort"

    private static var defaults: UserDefaults { .standard }

    static var host: String? {
        get { defaults.string(forKey: hostKey).flatMap(normalize) }
        set {
            if let normalized = newValue.flatMap(normalize) {
                defaults.set(normalized, forKey: hostKey)
            } else {
                defaults.removeObject(forKey: hostKey)
            }
        }
    }

    static var port: Int? {
        get { validPort(defaults.integer(forKey: portKey)) }
        set {
            if let port = newValue.flatMap(validPort) {
                defaults.set(port, forKey: portKey)
            } else {
                defaults.removeObject(forKey: portKey)
            }
        }
    }

    /// The device-side port Studio dials over USB. Unlike the LAN address there is no discovery to fall
    /// back on — usbmux only forwards to a port number — so this must match what Studio is configured to
    /// dial, and `nil` means the shared default both sides ship with.
    static var usbPort: Int? {
        get { validPort(defaults.integer(forKey: usbPortKey)) }
        set {
            if let port = newValue.flatMap(validPort) {
                defaults.set(port, forKey: usbPortKey)
            } else {
                defaults.removeObject(forKey: usbPortKey)
            }
        }
    }

    /// Clearing only removes the persisted value — a `-WailoHost` launch argument lives in the
    /// argument domain, which `removeObject` cannot touch and which outranks it on the next read.
    static func clear() {
        host = nil
        port = nil
        usbPort = nil
    }

    /// Validated on read as well as on write, because the launch-argument domain is not ours to
    /// sanitize: `-WailoHost <typo>` is written by Xcode, and a value that cannot be dialled has to read
    /// as "no override" rather than reach the transport. Canonical text, so what is stored re-parses to
    /// the same address (see `WailoAddress`).
    private static func normalize(_ raw: String) -> String? {
        WailoAddress(raw)?.description
    }

    private static func validPort(_ raw: Int) -> Int? {
        (1...65535).contains(raw) ? raw : nil
    }
}
