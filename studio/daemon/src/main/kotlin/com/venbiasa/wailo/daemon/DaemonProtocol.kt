package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.BodyRef
import com.venbiasa.wailo.engine.CaptureSource
import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.engine.ConnectedDevice
import com.venbiasa.wailo.engine.DeviceTransport
import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.engine.pairing.PairedDevice
import com.venbiasa.wailo.engine.pairing.RefusedDevice
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.HostSeed
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.CaptureFilter
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okio.ByteString.Companion.toByteString

internal const val DAEMON_CONTROL_PROTOCOL_VERSION = 9

/**
 * The one command whose socket is not answered and closed. The daemon holds it open and counts it as a
 * reference for as long as the peer lives, so a client that is SIGKILLed releases its reference the way
 * a clean shutdown does — which an explicit detach message would miss, leaking the count forever
 * (ADR-0062).
 */
internal const val PRESENCE_COMMAND = "presence"

/**
 * What kind of frontend a presence connection belongs to. The count alone answers "is anyone here"
 * (ADR-0062), but the menu bar agent has to ask a narrower question — is there a *Studio* to raise, or
 * does Show Studio have to start one (ADR-0065).
 */
const val CLIENT_KIND_UNKNOWN = "unknown"

const val CLIENT_KIND_STUDIO = "studio"

@Serializable
internal data class PresenceRequest(val kind: String = CLIENT_KIND_UNKNOWN)

@Serializable
internal data class RpcRequest(
    val command: String,
    val payload: JsonElement = JsonNull,
)

@Serializable
internal data class RpcResponse(
    val ok: Boolean,
    val payload: JsonElement = JsonNull,
    val error: String? = null,
)

@Serializable
internal data class PollRequest(
    val lastExchangeId: String? = null,
    val hasExchanges: Boolean = false,
    val mapLocalHash: String? = null,
    val breakpointHash: String? = null,
    val seedHash: String? = null,
    val holdsHash: String? = null,
)

@Serializable
internal data class PollResponse(
    val resetExchanges: Boolean,
    val firstExchangeId: String?,
    val exchanges: List<CapturedExchangeDto>,
    val exchangesCaughtUp: Boolean,
    val listening: Boolean,
    val capturePort: Int,
    val lanAddress: String,
    val capturing: Boolean,
    val maxRetained: Int,
    val connectedDevices: List<ConnectedDeviceDto>,
    val captureFilterBase64: String,
    val holdsHash: String,
    val holds: List<PausedExchangeDto>? = null,
    val mapLocalEnabled: Boolean,
    val mapLocalHash: String,
    val mapLocalRules: List<MapLocalRuleDto>? = null,
    val mapLocalLayout: String? = null,
    val breakpointsEnabled: Boolean,
    val breakpointHash: String,
    val breakpointRules: List<BreakpointRuleDto>? = null,
    val breakpointLayout: String? = null,
    val seedsEnabled: Boolean,
    val seedHash: String,
    val seeds: List<SeedRuleDto>? = null,
    val seedLayout: String? = null,
    // The armed queue as ids into the library above, not whole seeds: it changes on every spend, so a
    // poll that carried the bodies again would re-send them on each answered hold. Session state, so it
    // rides outside the hash-gated snapshot (ADR-0067).
    val armedSeedIds: List<String> = emptyList(),
    // Which of [holds] the daemon has finished deciding about, so a frontend can tell a hold no seed
    // wanted from one whose spend has not run yet (ADR-0067).
    val triagedHoldIds: List<String> = emptyList(),
    val pairing: PairingDto,
    val mcpAccess: Boolean,
    val mcpRedactSecrets: Boolean,
    // Whether a Studio is holding a presence connection, so the menu bar agent knows if Show Studio can
    // raise one or has to launch it (ADR-0065).
    val studioAttached: Boolean = false,
    // Monotonic counters rather than events: a frontend acts on an increase, so a request cannot be lost
    // between polls and a frontend that starts late does not replay an old one.
    val showStudioRequests: Int = 0,
    val quitRequests: Int = 0,
    val usbSupported: Boolean,
    val usbPort: Int,
    val usbDevices: List<UsbDeviceDto>,
    val adbSupported: Boolean,
    val adbExecutable: String? = null,
    val adbDevices: List<AdbDeviceDto>,
    val proxy: ProxyStatusDto? = null,
)

/**
 * A row as it crosses the control channel: the exchange's metadata, plus handles for bodies the daemon
 * is holding. The bytes stay behind `read_body` — a poll that inlined them would push a session's entire
 * traffic through JSON, twice (base64 in, decode out), to draw a list of URLs (ADR-0069).
 */
@Serializable
internal data class CapturedExchangeDto(
    val deviceName: String,
    val appId: String,
    val platform: String,
    val exchangeBase64: String,
    val requestBody: BodyRefDto? = null,
    val responseBody: BodyRefDto? = null,
    // Which capture path produced the row (ADR-0070). Defaulted, and read leniently, so a value this
    // build does not know becomes an SDK row rather than an unparseable poll.
    val source: String = CaptureSource.SDK.name,
) {
    fun toDomain() = CapturedExchange(
        deviceName = deviceName,
        appId = appId,
        platform = platform,
        exchange = HttpExchange.ADAPTER.decode(exchangeBase64.decodeBase64()),
        requestBody = requestBody?.toDomain(),
        responseBody = responseBody?.toDomain(),
        source = runCatching { CaptureSource.valueOf(source) }.getOrDefault(CaptureSource.SDK),
    )
}

@Serializable
internal data class ProxyStatusDto(
    val running: Boolean,
    val port: Int,
    val connections: Int,
    val exchanges: Long,
    val error: String? = null,
    val caInstalled: Boolean = false,
    val caFingerprint: String = "",
    val caExpiresEpochMs: Long = 0,
    val decryptHosts: List<String> = emptyList(),
    val lan: Boolean = false,
    val lanAddress: String = "",
) {
    fun toDomain() = ProxyStatus(
        running = running,
        port = port,
        connections = connections,
        exchanges = exchanges,
        error = error,
        caInstalled = caInstalled,
        caFingerprint = caFingerprint,
        caExpiresEpochMs = caExpiresEpochMs,
        decryptHosts = decryptHosts,
        lan = lan,
        lanAddress = lanAddress,
    )
}

@Serializable
internal data class SetProxyRequest(val enabled: Boolean, val port: Int? = null)

/** Replaces the allowlist wholesale rather than adding to it, so revoking is the same call as granting. */
@Serializable
internal data class SetProxyDecryptRequest(val hosts: List<String>)

/**
 * The local root as a frontend may see it (ADR-0073). [pem] is the certificate, never the key — there is
 * no shape of this message that carries one. [error] explains an absent root, which otherwise reads
 * exactly like decryption simply being off.
 */
@Serializable
internal data class ProxyCertificateDto(
    val installed: Boolean,
    val commonName: String = "",
    val sha256: String = "",
    val expiresEpochMs: Long = 0,
    val pem: String = "",
    val error: String? = null,
) {
    fun toDomain() = ProxyCertificate(
        installed = installed,
        commonName = commonName,
        sha256 = sha256,
        expiresEpochMs = expiresEpochMs,
        pem = pem,
        error = error,
    )
}

@Serializable
internal data class BodyRefDto(val id: String, val size: Long) {
    fun toDomain() = BodyRef(id = id, size = size)
}

/**
 * A bounded slice of one spooled body. [size] rides along so the daemon can locate the last chunk
 * without an index; a handle from a row that has since been evicted simply reads empty.
 */
@Serializable
internal data class ReadBodyRequest(
    val id: String,
    val size: Long,
    val offset: Long,
    val length: Int,
)

@Serializable
internal data class ReadBodyResponse(val bytesBase64: String)

@Serializable
internal data class ConnectedDeviceDto(
    val connectionId: String,
    val deviceName: String,
    val appId: String,
    val platform: String,
    val transport: String,
    val loopback: Boolean,
) {
    fun toDomain() = ConnectedDevice(
        connectionId = connectionId,
        deviceName = deviceName,
        appId = appId,
        platform = platform,
        transport = DeviceTransport.valueOf(transport),
        loopback = loopback,
    )
}

@Serializable
internal data class PausedExchangeDto(
    val correlationId: String,
    val deviceName: String,
    val appId: String,
    val platform: String,
    val phase: String,
    val requestBase64: String? = null,
    val responseBase64: String? = null,
) {
    fun toDomain() = PausedExchange(
        correlationId = correlationId,
        deviceName = deviceName,
        appId = appId,
        platform = platform,
        phase = BreakpointPhase.valueOf(phase),
        request = requestBase64?.let { HttpRequest.ADAPTER.decode(it.decodeBase64()) },
        response = responseBase64?.let { HttpResponse.ADAPTER.decode(it.decodeBase64()) },
    )
}

@Serializable
internal data class HeaderDto(val name: String, val value: String) {
    fun toDomain() = Header(name = name, value_ = value)
}

@Serializable
internal data class MapLocalRuleDto(
    val id: String,
    val enabled: Boolean,
    val urlPattern: String,
    val methods: List<String>,
    val statusCode: Int,
    val headers: List<HeaderDto>,
    val bodyBase64: String,
    val bodyAvailable: Boolean = true,
    // Defaulted so this stays an additive field: `DaemonJson` ignores unknown keys, so a daemon and a
    // frontend built either side of this change still talk without a control-protocol bump.
    val name: String = "",
) {
    fun toDomain() = HostMapLocalRule(
        id = id,
        name = name,
        enabled = enabled,
        urlPattern = urlPattern,
        methods = methods,
        statusCode = statusCode,
        headers = headers.map(HeaderDto::toDomain),
        body = bodyBase64.decodeBase64(),
        bodyAvailable = bodyAvailable,
    )
}

@Serializable
internal data class BreakpointRuleDto(
    val id: String,
    val enabled: Boolean,
    val urlPattern: String,
    val methods: List<String>,
    val onRequest: Boolean,
    val onResponse: Boolean,
) {
    fun toDomain() = HostBreakpointRule(
        id = id,
        enabled = enabled,
        urlPattern = urlPattern,
        methods = methods,
        onRequest = onRequest,
        onResponse = onResponse,
    )
}

/**
 * A seed carries its body inline, like [MapLocalRuleDto] and unlike a device-side rule: it is never
 * pushed anywhere, it is spent here, and the daemon has to be able to answer a hold with no Studio to
 * ask for the bytes (ADR-0067).
 */
@Serializable
internal data class SeedRuleDto(
    val id: String,
    val enabled: Boolean,
    val urlPattern: String,
    val method: String,
    val statusCode: Int,
    val headers: List<HeaderDto>,
    val bodyBase64: String,
    val bodyAvailable: Boolean = true,
) {
    fun toDomain() = HostSeed(
        id = id,
        enabled = enabled,
        urlPattern = urlPattern,
        method = method,
        statusCode = statusCode,
        headers = headers.map(HeaderDto::toDomain),
        body = bodyBase64.decodeBase64(),
        bodyAvailable = bodyAvailable,
    )
}

@Serializable
internal data class PairingDto(
    val supported: Boolean,
    val requirePairing: Boolean,
    val studioId: String,
    val offer: PairingOfferDto? = null,
    val devices: List<PairedDeviceDto>,
    val refusals: List<RefusedDeviceDto>,
    val suspectedClones: Set<String>,
)

@Serializable
internal data class PairingOfferDto(
    val code: String,
    val expiresAtEpochMs: Long,
    val qrPayload: String,
)

@Serializable
internal data class PairedDeviceDto(
    val deviceId: String,
    val name: String,
    val pairedAtEpochMs: Long,
    val lastSeenEpochMs: Long,
    val trustedOnFirstUse: Boolean,
)

@Serializable
internal data class RefusedDeviceDto(val deviceId: String, val reason: String)

@Serializable
internal data class UsbDeviceDto(
    val udid: String,
    val status: String,
    val error: String? = null,
)

@Serializable
internal data class AdbDeviceDto(
    val serial: String,
    val name: String,
    val status: String,
    val error: String? = null,
)

/**
 * The same pair a poll carries, fetchable on its own so an MCP tool call can authorize against the
 * daemon's current answer instead of a projection that is up to one poll interval stale (ADR-0059).
 */
@Serializable
internal data class McpSettingsDto(val access: Boolean, val redactSecrets: Boolean)

@Serializable
internal data class BooleanValue(val value: Boolean)

@Serializable
internal data class IntValue(val value: Int)

@Serializable
internal data class StringValue(val value: String)

@Serializable
internal data class OptionalStringValue(val value: String? = null)

@Serializable
internal data class IdRequest(val id: String)

@Serializable
internal data class CaptureFilterRequest(
    val allowlistEnabled: Boolean,
    val allowPatterns: List<String>,
    val blocklistEnabled: Boolean,
    val blockPatterns: List<String>,
)

@Serializable
internal data class ReplaceMapLocalRequest(
    val enabled: Boolean,
    val rules: List<MapLocalRuleDto>,
    val layout: String? = null,
)

@Serializable
internal data class ReplaceBreakpointsRequest(
    val enabled: Boolean,
    val rules: List<BreakpointRuleDto>,
    val layout: String? = null,
)

@Serializable
internal data class ReplaceSeedsRequest(
    val enabled: Boolean,
    val rules: List<SeedRuleDto>,
    val layout: String? = null,
)

@Serializable
internal data class ResumeHoldRequest(
    val correlationId: String,
    val editedRequestBase64: String? = null,
    val editedResponseBase64: String? = null,
)

@Serializable
internal data class PairingActionRequest(
    val action: String,
    val deviceId: String? = null,
    val enabled: Boolean? = null,
)

internal fun CapturedExchange.toDto() = CapturedExchangeDto(
    deviceName = deviceName,
    appId = appId,
    platform = platform,
    exchangeBase64 = exchange.encode().encodeBase64(),
    requestBody = requestBody?.toDto(),
    responseBody = responseBody?.toDto(),
    source = source.name,
)

internal fun ProxyStatus.toDto() = ProxyStatusDto(
    running = running,
    port = port,
    connections = connections,
    exchanges = exchanges,
    error = error,
    caInstalled = caInstalled,
    caFingerprint = caFingerprint,
    caExpiresEpochMs = caExpiresEpochMs,
    decryptHosts = decryptHosts,
    lan = lan,
    lanAddress = lanAddress,
)

internal fun CertificateAuthorityInfo?.toDto(error: String? = null) = ProxyCertificateDto(
    installed = this != null,
    commonName = this?.commonName.orEmpty(),
    sha256 = this?.sha256.orEmpty(),
    expiresEpochMs = this?.notAfterEpochMs ?: 0,
    pem = this?.pem.orEmpty(),
    error = error.takeIf { this == null },
)

internal fun BodyRef.toDto() = BodyRefDto(id = id, size = size)

internal fun ConnectedDevice.toDto() = ConnectedDeviceDto(
    connectionId = connectionId,
    deviceName = deviceName,
    appId = appId,
    platform = platform,
    transport = transport.name,
    loopback = loopback,
)

internal fun PausedExchange.toDto() = PausedExchangeDto(
    correlationId = correlationId,
    deviceName = deviceName,
    appId = appId,
    platform = platform,
    phase = phase.name,
    requestBase64 = request?.encode()?.encodeBase64(),
    responseBase64 = response?.encode()?.encodeBase64(),
)

internal fun HostMapLocalRule.toDto() = MapLocalRuleDto(
    id = id,
    enabled = enabled,
    urlPattern = urlPattern,
    methods = methods,
    statusCode = statusCode,
    headers = headers.map { HeaderDto(it.name, it.value_) },
    bodyBase64 = bodyCopy().encodeBase64(),
    bodyAvailable = bodyAvailable,
    name = name,
)

internal fun HostBreakpointRule.toDto() = BreakpointRuleDto(
    id = id,
    enabled = enabled,
    urlPattern = urlPattern,
    methods = methods,
    onRequest = onRequest,
    onResponse = onResponse,
)

internal fun HostSeed.toDto() = SeedRuleDto(
    id = id,
    enabled = enabled,
    urlPattern = urlPattern,
    method = method,
    statusCode = statusCode,
    headers = headers.map { HeaderDto(it.name, it.value_) },
    bodyBase64 = bodyCopy().encodeBase64(),
    bodyAvailable = bodyAvailable,
)

internal fun PairedDevice.toDto() = PairedDeviceDto(
    deviceId = deviceId,
    name = name,
    pairedAtEpochMs = pairedAtEpochMs,
    lastSeenEpochMs = lastSeenEpochMs,
    trustedOnFirstUse = trustedOnFirstUse,
)

internal fun RefusedDevice.toDto() = RefusedDeviceDto(deviceId, reason)

internal fun HttpRequest.encodeBase64(): String = encode().encodeBase64()

internal fun HttpResponse.encodeBase64(): String = encode().encodeBase64()

internal fun String.decodeRequest(): HttpRequest = HttpRequest.ADAPTER.decode(decodeBase64())

internal fun String.decodeResponse(): HttpResponse = HttpResponse.ADAPTER.decode(decodeBase64())

internal fun CaptureFilter.encodeBase64(): String = encode().encodeBase64()

internal fun String.decodeCaptureFilter(): CaptureFilter = CaptureFilter.ADAPTER.decode(decodeBase64())

internal fun ByteArray.encodeBase64(): String = Base64.getEncoder().encodeToString(this)

internal fun String.decodeBase64(): ByteArray = Base64.getDecoder().decode(this)

internal fun ByteArray.toByteStringCopy() = toByteString()
