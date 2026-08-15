package com.venbiasa.wailo.sdk.android.panel

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.venbiasa.wailo.sdk.android.Wailo
import com.venbiasa.wailo.sdk.android.WailoAddress
import com.venbiasa.wailo.sdk.android.WailoDesktop
import com.venbiasa.wailo.sdk.android.WailoConnectionPhase
import com.venbiasa.wailo.sdk.android.WailoPairing
import com.venbiasa.wailo.sdk.android.WailoPairingCode
import com.venbiasa.wailo.sdk.android.WailoPairingInvite
import com.venbiasa.wailo.sdk.android.WailoStatus

/**
 * The four things the panel needs from a stored pairing. [WailoPairing] also carries the keys, which the
 * panel has no business holding — and reducing it here means the list logic below is exercisable without
 * a real key exchange behind it.
 */
internal data class Remembered(
    val studioId: String,
    val lastHost: String,
    val trustedOnFirstUse: Boolean,
    val refused: Boolean,
)

internal fun WailoPairing.remembered(): Remembered =
    Remembered(studioId, lastHost, trustedOnFirstUse, refused)

/**
 * A desktop worth offering under the address field: one on this network now, one this device has reached
 * before, or — the ordinary case — both at once.
 *
 * Merged rather than listed twice (ADR-0047). Discovery and the pairing store answer different questions
 * ("who is advertising" and "who do we hold a key for"), but for the person choosing an address they
 * describe the same machine, and showing it in two places made "which of these is mine" a question the
 * panel itself invented.
 */
internal data class PanelDesktop(
    val id: String,
    val studioId: String?,
    /** The mDNS instance name while it is advertising, else the address it was last reached at. */
    val name: String,
    /**
     * What Fill puts in the address field. A remembered desktop that is not advertising has no port to
     * offer, which leaves the field empty and the default in force.
     */
    val host: String,
    val port: Int?,
    val pairing: Remembered?,
    val online: Boolean,
) {
    val address: String get() = port?.let { "$host:$it" } ?: host

    /**
     * Dropped when it would only repeat the title, which is every remembered desktop that is currently
     * offline — there its name *is* its last address.
     */
    val subtitle: String? get() = address.takeIf { it != name }

    /**
     * Held only for a desktop this device has a key for: it is the one thing that can be compared against
     * what Studio prints in its own Settings when two machines look alike (ADR-0040).
     */
    val fingerprint: String? get() = pairing?.studioId

    val trustLabel: String?
        get() {
            val pairing = pairing ?: return null
            val how = if (pairing.trustedOnFirstUse) "Trusted on first contact" else "Paired"
            return if (online) how else "$how — not on this network"
        }

    val warning: String?
        get() = if (pairing?.refused == true) "This desktop no longer recognises this device." else null

    /** Forgetting is only meaningful for a desktop there is something stored about. */
    val isRemembered: Boolean get() = pairing != null

    val canFill: Boolean get() = host.isNotEmpty()
}

/**
 * The advertising desktops and the remembered ones as one list, the former first.
 *
 * Pure, and separate from [WailoPanelModel], because this is the panel's one piece of real logic: every
 * other field is a passthrough of [WailoStatus].
 */
internal fun mergeDesktops(discovered: List<WailoDesktop>, pairings: List<Remembered>): List<PanelDesktop> {
    val byId = pairings.associateBy { it.studioId }
    val matched = mutableSetOf<String>()
    val advertising = discovered.map { service ->
        val pairing = service.studioId.takeIf { it.isNotEmpty() }?.let(byId::get)
        if (pairing != null) matched += pairing.studioId
        PanelDesktop(
            id = pairing?.studioId ?: service.address,
            studioId = service.studioId.takeIf { it.isNotEmpty() } ?: pairing?.studioId,
            name = service.name,
            host = service.host,
            port = service.port,
            pairing = pairing,
            online = true,
        )
    }
    // Kept in the list even though nothing can be dialled right now: this is the only place a desktop
    // that has moved networks — or one the user is done with — can be forgotten, and hiding it would
    // mean discovery silently reconnecting to something with no way to say no.
    val offline = pairings
        .filterNot { it.studioId in matched }
        .map { pairing ->
            PanelDesktop(
                id = pairing.studioId,
                studioId = pairing.studioId,
                name = pairing.lastHost.ifEmpty { "Paired desktop" },
                host = pairing.lastHost,
                port = null,
                pairing = pairing,
                online = false,
            )
        }
        .sortedBy { it.name.lowercase() }
    return advertising + offline
}

/** What the last Connect did. Without this the button is indistinguishable before and after a tap. */
internal sealed interface ConnectAttempt {
    data object Idle : ConnectAttempt
    data class Dialling(val target: String) : ConnectAttempt
    data class Connected(val target: String) : ConnectAttempt
    data class Stopped(val target: String) : ConnectAttempt
}

/**
 * Mirrors [Wailo.status] into something Compose can bind to, and owns the fields the SDK has no opinion
 * about — what is half-typed into the address box, and why the last submit did not take.
 *
 * Phrasing lives here too, so the panel renders strings instead of deriving them.
 */
@Stable
internal class WailoPanelModel(initial: WailoStatus = Wailo.status.value) {

    var status: WailoStatus by mutableStateOf(initial)
        private set

    var host: String by mutableStateOf(initial.configuredHost.orEmpty())
    var port: String by mutableStateOf(initial.configuredPort?.toString().orEmpty())

    /**
     * Why the last Connect didn't take, per field. Reported on submit rather than while typing —
     * half-typed text is not a mistake — and cleared by the next edit.
     */
    var hostError: String? by mutableStateOf(null)
        private set
    var portError: String? by mutableStateOf(null)
        private set

    var pairingError: String? by mutableStateOf(null)
        private set

    /** Which discovered desktop a typed code is meant for. A code says nothing about who is offering it. */
    var codeTarget: WailoDesktop? by mutableStateOf(initial.discovered.firstOrNull { it.studioId.isNotEmpty() })
        private set

    var attempt: ConnectAttempt by mutableStateOf(ConnectAttempt.Idle)
        private set

    private var expectedStudioId: String? = null

    var isScanning: Boolean by mutableStateOf(false)
        private set

    /**
     * Filtered to the code's alphabet on every keystroke, so the field cannot hold something the handshake
     * would reject — a typed `O` that silently becomes a wrong key is unexplainable.
     */
    var pairingCode: String by mutableStateOf("")
        private set

    // MARK: - derived state

    val isStarted: Boolean get() = status.activeAddress != null
    val isUsingDiscovery: Boolean get() = status.configuredHost == null
    val desktops: List<PanelDesktop> get() = mergeDesktops(status.discovered, status.pairings.map { it.remembered() })

    /** Desktops a typed code can be aimed at. One too old to advertise a fingerprint has nothing to
     * derive the key against, so offering it would only produce a failure the user cannot see. */
    val pairableDesktops: List<WailoDesktop> get() = status.discovered.filter { it.studioId.isNotEmpty() }

    val statusTitle: String
        get() = when (status.phase) {
            WailoConnectionPhase.STOPPED -> if (isStarted) "Not connected" else "Not started"
            WailoConnectionPhase.DIALLING -> "Dialling"
            WailoConnectionPhase.AUTHENTICATING -> "Authenticating"
            WailoConnectionPhase.CONNECTED -> "Connected"
            WailoConnectionPhase.REFUSED -> "Refused"
            WailoConnectionPhase.IDENTITY_MISMATCH -> "Identity mismatch"
        }

    val transportLabel: String?
        get() = if (!isStarted) null else if (status.handshakeWaived) "adb" else "Wi-Fi"

    val statusDetail: String
        get() = when {
            status.phase == WailoConnectionPhase.REFUSED ->
                status.refusal ?: "Studio refused this relationship."
            status.phase == WailoConnectionPhase.IDENTITY_MISMATCH ->
                "The address is now answered by a different Studio. Confirm or reject the replacement."
            !isStarted -> "No Wailo client is running. Call Wailo.webSocketSink(...).start()."
            status.handshakeWaived ->
                "Reached on loopback, which is where adb reverse puts the desktop. The cable already " +
                    "proves which machine is on the other end, so nothing here needs pairing."

            status.phase == WailoConnectionPhase.AUTHENTICATING ->
                "Studio proved its identity; this device is proving its Studio-scoped relationship."
            status.connected && isUsingDiscovery -> "Found on this network."
            status.connected -> "Pinned to a manual address."
            isUsingDiscovery ->
                "Looking for a desktop on this network. Needs the same Wi-Fi, and a desktop this " +
                    "device already trusts — otherwise scan its QR below."
            else -> "Retrying the pinned address."
        }

    /**
     * Whether a paired Studio is what the SDK is actually talking to. A loopback link connects without
     * pairing, so "connected" alone must not be read as "pairing worked".
     */
    val isPairedConnection: Boolean
        get() = status.connected && !status.handshakeWaived && status.pairings.any { !it.refused }

    val canApply: Boolean get() = host.isNotBlank() && !isTargetActive

    /**
     * Whether the field already names what is being dialled. Connect has nothing left to do then, and an
     * enabled button that changes nothing reads as a button that did not work.
     */
    private val isTargetActive: Boolean
        get() {
            val address = WailoAddress.parse(host.trim()) ?: return false
            if (status.configuredHost != address.host) return false
            val typedPort = address.port ?: port.trim().toIntOrNull()
            return typedPort == null || typedPort == status.configuredPort
        }

    val canPairWithCode: Boolean get() = WailoPairingCode.normalize(pairingCode) != null && codeTarget != null

    // MARK: - edits

    fun editHost(text: String) {
        host = text
        expectedStudioId = null
        clearManualErrors()
    }

    fun editPort(text: String) {
        port = text
        expectedStudioId = null
        clearManualErrors()
    }

    fun editPairingCode(text: String) {
        pairingCode = WailoPairingCode.sanitize(text)
        pairingError = null
    }

    fun selectCodeTarget(desktop: WailoDesktop) {
        codeTarget = desktop
        pairingError = null
    }

    // MARK: - actions

    /**
     * Pin the typed address. An empty field means "stop pinning", which is the same as [useDiscovery].
     *
     * The address field is allowed to carry its port, because `10.0.0.2:8899` is what people type when
     * asked for an address. A port found there is moved into the port field, so the panel never shows an
     * address different from the one it is dialling.
     */
    fun apply() {
        val typedHost = host.trim()
        if (typedHost.isEmpty()) return useDiscovery()

        val address = WailoAddress.parse(typedHost)
        if (address == null) {
            hostError = "Not an address Wailo can dial. Use an IP or hostname, with an optional :port."
            return
        }

        val typedPort = port.trim()
        var resolvedPort = address.port
        if (resolvedPort == null && typedPort.isNotEmpty()) {
            val value = typedPort.toIntOrNull()
            if (value == null || value !in WailoAddress.PORT_RANGE) {
                portError = "The port has to be a number from 1 to 65535."
                return
            }
            resolvedPort = value
        }

        host = address.host
        port = resolvedPort?.toString().orEmpty()
        Wailo.setHost(address.host, resolvedPort, expectedStudioId)
        // Dialling is asynchronous, so Connect cannot report success or failure by the time it returns.
        // Saying "dialling" and letting the status line settle is honest; leaving the button looking
        // exactly as it did before the tap is what makes people press it again.
        attempt = ConnectAttempt.Dialling(address.host)
    }

    fun useDiscovery() {
        host = ""
        port = ""
        expectedStudioId = null
        Wailo.setHost(null, null)
        attempt = ConnectAttempt.Idle
        clearManualErrors()
    }

    /**
     * Fills the address field rather than dialling. A row in this list is a suggestion — the user still
     * has to say "that one", because a first contact over Wi-Fi pins whatever answers and that should
     * never happen from a stray tap (ADR-0040).
     */
    fun fill(desktop: PanelDesktop) {
        if (!desktop.canFill) return
        host = desktop.host
        port = desktop.port?.toString().orEmpty()
        expectedStudioId = desktop.studioId
        attempt = ConnectAttempt.Idle
        clearManualErrors()
    }

    fun forget(desktop: PanelDesktop) {
        Wailo.forget(desktop.pairing?.studioId ?: return)
    }

    fun retryAfterRefusal() = Wailo.retryAfterRefusal()

    fun acceptIdentityChange() {
        val change = status.identityChange ?: return
        Wailo.acceptIdentityChange()
        attempt = ConnectAttempt.Dialling(change.host)
    }

    fun rejectIdentityChange() {
        Wailo.rejectIdentityChange()
        attempt = ConnectAttempt.Idle
    }

    // MARK: - pairing

    fun startScanning() {
        pairingError = null
        isScanning = true
    }

    fun cancelScanning() {
        isScanning = false
    }

    fun cameraDenied() {
        isScanning = false
        pairingError = "Wailo needs the camera to read a pairing QR. Grant it in Settings, or type the code."
    }

    fun scanned(payload: String) {
        isScanning = false
        val invite = WailoPairingInvite.fromQr(payload)
        if (invite == null) {
            pairingError = "That QR is not a Wailo pairing code."
            return
        }
        pairingError = null
        Wailo.pair(invite)
        attempt = ConnectAttempt.Dialling(invite.host)
    }

    fun pairWithCode() {
        val target = codeTarget
        if (target == null) {
            pairingError = "Pick the desktop this code is showing on."
            return
        }
        val invite = WailoPairingInvite.fromCode(pairingCode, target.studioId, target.host, target.port)
        if (invite == null) {
            pairingError = "That code isn't right. Check what Studio is showing — it expires after two minutes."
            return
        }
        pairingCode = ""
        pairingError = null
        Wailo.pair(invite)
        attempt = ConnectAttempt.Dialling(target.host)
    }

    // MARK: - status intake

    fun onStatus(next: WailoStatus) {
        status = next
        attempt = resolvedAttempt(next)
        // Discovery churns as desktops come and go; only re-pick when the chosen one is gone, so the
        // selection does not move out from under someone mid-way through typing a code.
        if (next.discovered.none { it.studioId == codeTarget?.studioId }) {
            codeTarget = next.discovered.firstOrNull { it.studioId.isNotEmpty() }
        }
    }

    /**
     * Where the in-flight attempt has got to. Resolved from the SDK rather than tracked by the button, so
     * a connection that drops later stops claiming to be connected.
     */
    private fun resolvedAttempt(next: WailoStatus): ConnectAttempt = when (val current = attempt) {
        is ConnectAttempt.Dialling -> when {
            next.connected -> ConnectAttempt.Connected(current.target)
            // The client refuses to open a socket at all for an address it will not talk to, so an
            // attempt with nothing active behind it has already been decided against.
            next.activeAddress == null || next.identityChange?.host == current.target ->
                ConnectAttempt.Stopped(current.target)

            else -> current
        }

        is ConnectAttempt.Connected -> if (next.connected) current else ConnectAttempt.Stopped(current.target)
        else -> current
    }

    private fun clearManualErrors() {
        hostError = null
        portError = null
    }
}
