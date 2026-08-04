package com.venbiasa.wailo.shared

import androidx.compose.ui.graphics.ImageBitmap

/**
 * The viewer's read-only view of pairing (ADR-0039). `shared` must not depend on `engine`, so the host
 * flattens the engine's types into these — the same bridge the `Captured*` types cross.
 */
data class PairedDeviceInfo(
    val deviceId: String,
    val name: String,
    val pairedAtEpochMs: Long,
    val lastSeenEpochMs: Long,
    /**
     * This device's session counter went backwards, so its key is in use somewhere else. Shown rather
     * than acted on: both ends hold the same secret, and nothing here can tell which is the real one.
     */
    val suspectedClone: Boolean,
    /**
     * Let in on first contact rather than through a QR or typed code (ADR-0040). Worth distinguishing:
     * nobody proved anything at that moment beyond answering at an address the user typed.
     */
    val trustedOnFirstUse: Boolean = false,
)

/**
 * A pairing window that is currently open. Two ways in, because a camera is not always usable: the QR
 * carries a full-strength key, and the code is the fallback — weaker, which is why it expires.
 */
data class PairingOfferInfo(
    /** Rendered by the host; `shared` has no QR encoder and does not need one. */
    val qr: ImageBitmap?,
    /** Already grouped for reading aloud. */
    val code: String,
    /**
     * Counted down by the host, not here: `shared` is stateless and has no clock in `commonMain`
     * (ADR-0013). The real expiry is enforced by the engine regardless of what this says.
     */
    val remainingSeconds: Int,
)

/** A device that dialled in and was turned away, so an absent device is not mistaken for a bad network. */
data class PairingRefusal(val deviceId: String, val reason: String)

data class PairingState(
    val offer: PairingOfferInfo? = null,
    val devices: List<PairedDeviceInfo> = emptyList(),
    val refusals: List<PairingRefusal> = emptyList(),
    /** False on a host with no secure key store, where WiFi pairing is refused rather than faked. */
    val supported: Boolean = true,
    /** See [PairingAction.SetRequirePairing]. */
    val requirePairing: Boolean = false,
    /** This Studio's own fingerprint, shown wherever the user is asked to compare it against a device. */
    val studioId: String = "",
)

sealed interface PairingAction {
    data object Begin : PairingAction
    data object Cancel : PairingAction
    data class Forget(val deviceId: String) : PairingAction
    data object ForgetAll : PairingAction

    /**
     * Throw away Studio's own identity. Every device pinned the old public key, so all of them stop
     * connecting at once — the answer to "assume everything leaked", where forgetting devices one at a
     * time is not enough. Lives in Settings rather than here: it is a rare, destructive act about this
     * Studio, not about any one device.
     */
    data object ResetIdentity : PairingAction

    /**
     * Require a QR or typed code before a WiFi device is let in (ADR-0040). Off by default, where a
     * device reaching an address the user typed is taken at its word and remembered from then on.
     */
    data class SetRequirePairing(val enabled: Boolean) : PairingAction
    data class DismissRefusal(val deviceId: String) : PairingAction
}
