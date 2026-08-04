import CryptoKit
import Foundation

enum WailoFrameError: Error {
    /// A `seq` that did not advance. Over TCP frames arrive in order, so this is a replay or an
    /// injection rather than a network hiccup, and the session is not worth continuing.
    case outOfOrder
    case malformed
}

/// Seals and opens the frames of one WiFi session (ADR-0039).
///
/// Authentication alone would still leave the stream readable to anyone sniffing a WPA2-PSK network,
/// and rewritable by an on-path relay that passes the handshake through untouched — nothing binds the
/// proven identity to the data channel. Sealing under a key neither of those attackers can derive is
/// what closes both.
///
/// AES-256-GCM rather than ChaCha20-Poly1305 because `javax.crypto` only offers the latter at Android
/// API 28 against an SDK floor of 24; AES-GCM is on every supported level and hardware-accelerated on
/// anything that matters. Loopback sessions are never sealed — a peer on 127.0.0.1 cannot be another
/// machine, so there is no key and nothing to protect.
final class WailoFrameCodec {

    /// The high half of the GCM nonce. The two directions differ so that device and Studio counters
    /// cannot collide under the one session key, which is the failure GCM does not survive.
    enum Direction: UInt32 {
        case deviceToStudio = 1
        case studioToDevice = 2
    }

    private static let tagLength = 16

    private let key: SymmetricKey
    private let sealing: Direction
    private let opening: Direction
    private var nextSeq: UInt64 = 0
    private var lastOpened: UInt64?

    init(sessionKey: SymmetricKey, sealing: Direction, opening: Direction) {
        self.key = sessionKey
        self.sealing = sealing
        self.opening = opening
    }

    func seal(_ plaintext: Data) throws -> (seq: UInt64, ciphertext: Data) {
        let seq = nextSeq
        nextSeq += 1
        let box = try AES.GCM.seal(plaintext, using: key, nonce: Self.nonce(sealing, seq))
        // ciphertext ++ tag is what `AES/GCM/NoPadding` emits on the JVM, so both sides read the same
        // bytes without either having to know the other's SealedBox layout. Copied into a fresh buffer
        // rather than concatenated: CryptoKit hands back slices of its own storage, whose indices do
        // not start at zero, and every caller downstream reasonably assumes they do.
        var wire = Data(capacity: box.ciphertext.count + Self.tagLength)
        wire.append(contentsOf: box.ciphertext)
        wire.append(contentsOf: box.tag)
        return (seq, wire)
    }

    func open(seq: UInt64, ciphertext: Data) throws -> Data {
        if let lastOpened, seq <= lastOpened { throw WailoFrameError.outOfOrder }
        guard ciphertext.count > Self.tagLength else { throw WailoFrameError.malformed }

        let split = ciphertext.index(ciphertext.endIndex, offsetBy: -Self.tagLength)
        let box = try AES.GCM.SealedBox(
            nonce: Self.nonce(opening, seq),
            ciphertext: ciphertext[..<split],
            tag: ciphertext[split...]
        )
        let plaintext = try AES.GCM.open(box, using: key)
        lastOpened = seq
        return plaintext
    }

    /// 4-byte direction ++ 8-byte big-endian counter. `seq` travels in the clear, but GCM folds the
    /// nonce into the tag, so altering it in flight just fails the open.
    private static func nonce(_ direction: Direction, _ seq: UInt64) -> AES.GCM.Nonce {
        var bytes = Data()
        bytes.append(contentsOf: withUnsafeBytes(of: direction.rawValue.bigEndian) { Data($0) })
        bytes.append(contentsOf: withUnsafeBytes(of: seq.bigEndian) { Data($0) })
        // A 12-byte nonce is GCM's own size; this cannot throw.
        return try! AES.GCM.Nonce(data: bytes)
    }
}
