package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.daemon.provision.CaTrust
import com.venbiasa.wailo.daemon.provision.ProxyTarget
import com.venbiasa.wailo.daemon.provision.ProxyTargetKind
import com.venbiasa.wailo.daemon.provision.ProxyTargetOutcome
import com.venbiasa.wailo.daemon.provision.ProxyTargetScan
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

internal const val DAEMON_CONTROL_PROTOCOL_VERSION = 16

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
    val captureFilterHash: String? = null,
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
    // The filter as authored — each list's own armed state, not the form folded through
    // [captureFilterEnabled] that devices apply. A frontend needs both to render the panel (ADR-0082).
    // Hash-gated like the rule families: it was the one payload re-encoded and re-sent on every tick
    // whether or not anything had touched it.
    val captureFilterHash: String,
    val captureFilterBase64: String? = null,
    val captureFilterEnabled: Boolean,
    // Which hosts the user marked as worth watching. Daemon state so an agent or a CLI session can read
    // what the user cares about with no window open (ADR-0084).
    val bookmarkedHosts: List<String>,
    val holdsHash: String,
    val holds: List<PausedExchangeDto>? = null,
    val mapLocalEnabled: Boolean,
    val mapLocalHash: String,
    // The grouped structure, not a flat list beside an opaque blob (ADR-0081). Every frontend reads its
    // groups from here, so a rule an agent filed into one is in that group for the panel too.
    val mapLocalNodes: List<DaemonRuleNode<MapLocalRuleDto>>? = null,
    val breakpointsEnabled: Boolean,
    val breakpointHash: String,
    val breakpointNodes: List<DaemonRuleNode<BreakpointRuleDto>>? = null,
    val seedsEnabled: Boolean,
    val seedHash: String,
    val seedNodes: List<DaemonRuleNode<SeedRuleDto>>? = null,
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
    val systemProxy: Boolean = false,
    val systemProxySupported: Boolean = false,
    val chainedTo: String = "",
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
        systemProxy = systemProxy,
        systemProxySupported = systemProxySupported,
        chainedTo = chainedTo,
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

/**
 * A rule names its body by [bodySize] and [bodyHash] rather than carrying it (ADR-0086). The bytes are
 * the daemon's, fetched with `read_rule_body`; inlining them meant a rule's switch could not be flipped
 * without rewriting and re-sending every fixture in the set.
 */
@Serializable
internal data class MapLocalRuleDto(
    val id: String,
    val enabled: Boolean,
    val urlPattern: String,
    val method: String = "",
    /**
     * What builds before ADR-0087 wrote, when a rule could name several methods. Read on the way up from
     * an older `map-local.json` and collapsed last-wins; never written back, since `explicitNulls = false`
     * omits a null. Dropping the field instead would let an ignored key silently widen a GET-only rule to
     * every method — the one migration failure that cannot be noticed by looking at the panel.
     */
    val methods: List<String>? = null,
    val statusCode: Int,
    val headers: List<HeaderDto>,
    val bodySize: Int = 0,
    val bodyHash: String = "",
    // Defaulted so this stays an additive field: `DaemonJson` ignores unknown keys, so a daemon and a
    // frontend built either side of this change still talk without a control-protocol bump.
    val name: String = "",
    // How every body was stored before this rule got a file of its own. Read on the way up from an older
    // `map-local.json` and written into the body store, never written back — see `migrateInlineBodies`.
    val bodyBase64: String = "",
) {
    // [enabled] is overridden when the rule sits in a group, whose own switch gates it (see
    // `effectiveEnabled`). The authored value stays on the DTO so a frontend can still show the rule's
    // own state, and turning the group back on restores it.
    fun toDomain(enabled: Boolean = this.enabled, body: ByteArray = ByteArray(0)) = HostMapLocalRule(
        id = id,
        name = name,
        enabled = enabled,
        urlPattern = urlPattern,
        method = collapsedMethod(method, methods),
        statusCode = statusCode,
        headers = headers.map(HeaderDto::toDomain),
        body = body,
        // The reference, not the bytes handed in: the daemon derives both when it adopts a layout, so
        // they describe what it stores whether or not this caller was given a copy.
        bodySize = bodySize,
        bodyHash = bodyHash,
    )
}

@Serializable
internal data class BreakpointRuleDto(
    val id: String,
    val enabled: Boolean,
    val urlPattern: String,
    val method: String = "",
    /** Pre-ADR-0087 form, read and collapsed exactly as on [MapLocalRuleDto]. */
    val methods: List<String>? = null,
    val onRequest: Boolean,
    val onResponse: Boolean,
) {
    fun toDomain(enabled: Boolean = this.enabled) = HostBreakpointRule(
        id = id,
        enabled = enabled,
        urlPattern = urlPattern,
        method = collapsedMethod(method, methods),
        onRequest = onRequest,
        onResponse = onResponse,
    )
}

/**
 * One method from either shape, last-wins (ADR-0087). The scalar is authoritative when it says anything;
 * a legacy list contributes its last entry, which is the same rewrite Studio performed on the next save.
 */
private fun collapsedMethod(method: String, legacy: List<String>?): String =
    method.ifBlank { legacy?.lastOrNull { it.isNotBlank() }.orEmpty() }

/**
 * The rule with its method narrowed and the legacy list dropped, applied where a persisted file is read
 * (ADR-0087). Doing it there rather than only on the way to the host is what keeps the wide shape out of
 * the copy the daemon holds — a poll that reported a blank scalar beside a list nobody looks at would
 * read in a frontend as "any method", turning a migration into a rule that answers more than it did.
 */
internal fun MapLocalRuleDto.collapsingMethod(): MapLocalRuleDto =
    if (methods == null) this else copy(method = collapsedMethod(method, methods), methods = null)

internal fun BreakpointRuleDto.collapsingMethod(): BreakpointRuleDto =
    if (methods == null) this else copy(method = collapsedMethod(method, methods), methods = null)

/**
 * A seed names its body the way [MapLocalRuleDto] does. The bytes stay with the daemon rather than the
 * frontend either way: a seed is never pushed anywhere, it is spent here, and a hold has to be
 * answerable with no Studio to ask for them (ADR-0067).
 */
@Serializable
internal data class SeedRuleDto(
    val id: String,
    val enabled: Boolean,
    val urlPattern: String,
    val method: String,
    val statusCode: Int,
    val headers: List<HeaderDto>,
    val bodySize: Int = 0,
    val bodyHash: String = "",
    val bodyBase64: String = "",
) {
    fun toDomain(enabled: Boolean = this.enabled, body: ByteArray = ByteArray(0)) = HostSeed(
        id = id,
        enabled = enabled,
        urlPattern = urlPattern,
        method = method,
        statusCode = statusCode,
        headers = headers.map(HeaderDto::toDomain),
        body = body,
        bodySize = bodySize,
        bodyHash = bodyHash,
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

@Serializable
internal data class ProxyTargetDto(
    val id: String,
    val name: String,
    val kind: String,
    val proxySet: Boolean = false,
    val trust: String = CaTrust.NONE.name,
    val certificateCurrent: Boolean = false,
    val cleanupPending: Boolean = false,
    val actionRequired: String = "",
    val detail: String = "",
) {
    fun toDomain() = ProxyTarget(
        id = id,
        name = name,
        kind = runCatching { ProxyTargetKind.valueOf(kind) }.getOrDefault(ProxyTargetKind.ANDROID_EMULATOR),
        proxySet = proxySet,
        trust = runCatching { CaTrust.valueOf(trust) }.getOrDefault(CaTrust.NONE),
        certificateCurrent = certificateCurrent,
        cleanupPending = cleanupPending,
        actionRequired = actionRequired,
        detail = detail,
    )
}

@Serializable
internal data class ProxyTargetsDto(
    val supported: Boolean,
    val targets: List<ProxyTargetDto>,
    val error: String? = null,
) {
    fun toDomain() = ProxyTargetScan(
        supported = supported,
        targets = targets.map { it.toDomain() },
        error = error,
    )
}

@Serializable
internal data class ProxyTargetRequest(val id: String)

@Serializable
internal data class ProxyTargetOutcomeDto(
    val target: ProxyTargetDto? = null,
    val proxySet: Boolean = false,
    val trust: String = CaTrust.NONE.name,
    val certificateCurrent: Boolean = false,
    val cleanupPending: Boolean = false,
    val actionRequired: String = "",
    val note: String = "",
    val error: String? = null,
) {
    fun toDomain() = ProxyTargetOutcome(
        target = target?.toDomain(),
        proxySet = proxySet,
        trust = runCatching { CaTrust.valueOf(trust) }.getOrDefault(CaTrust.NONE),
        certificateCurrent = certificateCurrent,
        cleanupPending = cleanupPending,
        actionRequired = actionRequired,
        note = note,
        error = error,
    )
}

internal fun ProxyTarget.toDto() = ProxyTargetDto(
    id = id,
    name = name,
    kind = kind.name,
    proxySet = proxySet,
    trust = trust.name,
    certificateCurrent = certificateCurrent,
    cleanupPending = cleanupPending,
    actionRequired = actionRequired,
    detail = detail,
)

internal fun ProxyTargetOutcome.toDto() = ProxyTargetOutcomeDto(
    target = target?.toDto(),
    proxySet = proxySet,
    trust = trust.name,
    certificateCurrent = certificateCurrent,
    cleanupPending = cleanupPending,
    actionRequired = actionRequired,
    note = note,
    error = error,
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
    // The master rides with the lists so a frontend that edits both applies them as one. Sent apart, the
    // poll between the two calls reports a filter that is half this edit and half the last one, and a
    // frontend that adopts what the daemon reports would take that back as the user's intent (ADR-0082).
    // Null leaves the master alone, which is what a list-only edit from the CLI or an agent means.
    val enabled: Boolean? = null,
)

/**
 * One host, not the whole list: two frontends bookmarking at the same time would each send a list built
 * from what they last read, and the later write would drop the other's host (ADR-0084).
 */
@Serializable
internal data class BookmarkRequest(
    val host: String,
    val bookmarked: Boolean,
)

/**
 * A whole layout, and only the bodies it is changing (ADR-0086). [bodies] is base64 by rule id; a rule
 * absent from it keeps whatever the daemon already holds, which is what makes reordering or toggling
 * cost nothing. A rule the daemon has never seen and that names no body here has an empty one.
 */
@Serializable
internal data class ReplaceMapLocalRequest(
    val enabled: Boolean,
    val nodes: List<DaemonRuleNode<MapLocalRuleDto>>,
    val bodies: Map<String, String> = emptyMap(),
)

@Serializable
internal data class ReplaceBreakpointsRequest(
    val enabled: Boolean,
    val nodes: List<DaemonRuleNode<BreakpointRuleDto>>,
)

@Serializable
internal data class ReplaceSeedsRequest(
    val enabled: Boolean,
    val nodes: List<DaemonRuleNode<SeedRuleDto>>,
    val bodies: Map<String, String> = emptyMap(),
)

/**
 * An upsert that can also place the rule, so a headless frontend can file into a group rather than only
 * appending loose rules. A null [groupId] leaves an existing rule where it is; an empty one moves it out
 * to the top level. The group must already exist — it is addressed by id (ADR-0081).
 *
 * [body] carries its bytes inline, unlike a layout publish: this call *is* about one rule, so there is
 * nothing to be saved by naming the body instead. Null leaves the stored body alone, which is what an
 * edit that only moves or renames the rule means.
 */
@Serializable
internal data class UpsertMapLocalRequest(
    val rule: MapLocalRuleDto,
    val groupId: String? = null,
    val body: String? = null,
)

@Serializable
internal data class UpsertBreakpointRequest(
    val rule: BreakpointRuleDto,
    val groupId: String? = null,
)

@Serializable
internal data class UpsertSeedRequest(
    val seed: SeedRuleDto,
    val groupId: String? = null,
    val body: String? = null,
)

/**
 * A bounded slice of one authored rule body, the counterpart of [ReadBodyRequest] for the fixtures a
 * frontend wrote rather than the traffic it captured (ADR-0086).
 */
@Serializable
internal data class ReadRuleBodyRequest(
    val family: String,
    val id: String,
    val offset: Long = 0,
    val length: Int,
)

@Serializable
internal data class ReadRuleBodyResponse(val bytesBase64: String)

/** Which panel's groups a group command addresses; the three share one set of commands. */
@Serializable
internal data class SetRuleGroupRequest(
    val family: String,
    val group: DaemonRuleGroup,
)

@Serializable
internal data class RemoveRuleGroupRequest(
    val family: String,
    val id: String,
    val withRules: Boolean = false,
)

/**
 * A new order for one container: the rules inside [groupId], or the top level when it is absent. Ids the
 * container does not hold are rejected rather than skipped, so a typo cannot half-apply.
 */
@Serializable
internal data class SetRuleOrderRequest(
    val family: String,
    val groupId: String? = null,
    val ids: List<String> = emptyList(),
)

@Serializable
internal data class ListRuleGroupsRequest(val family: String)

@Serializable
internal data class RuleGroupListDto(val groups: List<DaemonRuleGroup>)

const val RULE_FAMILY_MAP_LOCAL = "map_local"

const val RULE_FAMILY_BREAKPOINTS = "breakpoints"

const val RULE_FAMILY_SEEDS = "seeds"

val RULE_FAMILIES = listOf(RULE_FAMILY_MAP_LOCAL, RULE_FAMILY_BREAKPOINTS, RULE_FAMILY_SEEDS)

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
    systemProxy = systemProxy,
    systemProxySupported = systemProxySupported,
    chainedTo = chainedTo,
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

/**
 * The match set the engine actually serves: flattened to priority order, with each group's switch folded
 * into the rules under it, and each body read back out of [bodies]. This is the only projection devices
 * ever see — the grouping above it is authoring structure and never reaches the wire.
 */
internal fun List<DaemonRuleNode<MapLocalRuleDto>>.toHostRules(
    bodies: (String) -> ByteArray,
): List<HostMapLocalRule> = flatMap { node ->
    node.rules.map { it.toDomain(effectiveEnabled(it.enabled, node.group), bodies(it.id)) }
}

internal fun List<DaemonRuleNode<BreakpointRuleDto>>.toHostBreakpointRules(): List<HostBreakpointRule> =
    flatMap { node -> node.rules.map { it.toDomain(effectiveEnabled(it.enabled, node.group)) } }

internal fun List<DaemonRuleNode<SeedRuleDto>>.toHostSeeds(
    bodies: (String) -> ByteArray,
): List<HostSeed> = flatMap { node ->
    node.rules.map { it.toDomain(effectiveEnabled(it.enabled, node.group), bodies(it.id)) }
}

internal fun HostMapLocalRule.toDto() = MapLocalRuleDto(
    id = id,
    enabled = enabled,
    urlPattern = urlPattern,
    method = method,
    statusCode = statusCode,
    headers = headers.map { HeaderDto(it.name, it.value_) },
    // Carried, not recomputed: a frontend publishing a layout holds bytes only for the rules it is
    // changing, and the daemon derives the reference for every rule as it adopts them anyway.
    bodySize = bodySize,
    bodyHash = bodyHash,
    name = name,
)

internal fun HostBreakpointRule.toDto() = BreakpointRuleDto(
    id = id,
    enabled = enabled,
    urlPattern = urlPattern,
    method = method,
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
    bodySize = bodySize,
    bodyHash = bodyHash,
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
