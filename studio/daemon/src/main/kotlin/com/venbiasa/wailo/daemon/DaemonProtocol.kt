package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.engine.ConnectedDevice
import com.venbiasa.wailo.engine.DeviceTransport
import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.engine.pairing.PairedDevice
import com.venbiasa.wailo.engine.pairing.RefusedDevice
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.host.HostMapLocalRule
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

internal const val DAEMON_CONTROL_PROTOCOL_VERSION = 2

/**
 * The one command whose socket is not answered and closed. The daemon holds it open and counts it as a
 * reference for as long as the peer lives, so a client that is SIGKILLed releases its reference the way
 * a clean shutdown does — which an explicit detach message would miss, leaking the count forever
 * (ADR-0062).
 */
internal const val PRESENCE_COMMAND = "presence"

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
    val pairing: PairingDto,
    val mcpAccess: Boolean,
    val mcpRedactSecrets: Boolean,
    val usbSupported: Boolean,
    val usbPort: Int,
    val usbDevices: List<UsbDeviceDto>,
    val adbSupported: Boolean,
    val adbExecutable: String? = null,
    val adbDevices: List<AdbDeviceDto>,
)

@Serializable
internal data class CapturedExchangeDto(
    val deviceName: String,
    val appId: String,
    val platform: String,
    val exchangeBase64: String,
) {
    fun toDomain() = CapturedExchange(
        deviceName = deviceName,
        appId = appId,
        platform = platform,
        exchange = HttpExchange.ADAPTER.decode(exchangeBase64.decodeBase64()),
    )
}

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
) {
    fun toDomain() = HostMapLocalRule(
        id = id,
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
)

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
)

internal fun HostBreakpointRule.toDto() = BreakpointRuleDto(
    id = id,
    enabled = enabled,
    urlPattern = urlPattern,
    methods = methods,
    onRequest = onRequest,
    onResponse = onResponse,
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
