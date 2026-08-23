package com.venbiasa.wailo.mcp

import com.venbiasa.wailo.daemon.DaemonRuleGroup
import com.venbiasa.wailo.daemon.RULE_FAMILIES
import com.venbiasa.wailo.daemon.RULE_FAMILY_BREAKPOINTS
import com.venbiasa.wailo.daemon.RULE_FAMILY_MAP_LOCAL
import com.venbiasa.wailo.daemon.RULE_FAMILY_SEEDS
import com.venbiasa.wailo.engine.BodyRef
import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.HostSeed
import com.venbiasa.wailo.host.urlPatternMatches
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString

internal data class McpToolResponse(
    val text: String,
    val data: Map<String, Any?>,
    val isError: Boolean = false,
)

/**
 * Pure MCP-facing command surface over the shared daemon. The local-host constructor is retained for
 * hermetic service tests; production MCP processes always use [DaemonMcpBackend].
 * into MCP content, which keeps every tool callable in unit tests without a JSON-RPC transport.
 */
internal class WailoMcpService(
    private val backend: McpBackend,
) {
    constructor(host: HeadlessHost, capturePort: Int) : this(LocalMcpBackend(host, capturePort))

    suspend fun call(name: String, rawArguments: Map<String, Any?>): McpToolResponse {
        // Authorize against the daemon's current answer, and fail closed if it cannot be reached: a
        // permission we could not confirm is not one we should act on. Every tool is gated, status
        // included — revoked has to mean the agent learns nothing about the traffic, not that it learns
        // a little. The message carries the remedy so a refusal is self-explanatory rather than looking
        // like a broken daemon.
        try {
            backend.refreshSettings()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return failure("Could not confirm Wailo AI tool access: ${failure.message ?: "daemon unreachable"}")
        }
        if (!backend.mcpAccess) return failure(ACCESS_REFUSED)
        val arguments = ToolArguments(rawArguments)
        return try {
            when (name) {
                "status" -> status()
                "list_exchanges" -> listExchanges(arguments)
                "search_traffic" -> searchTraffic(arguments)
                "get_exchange" -> getExchange(arguments)
                "wait_for_exchange" -> waitForExchange(arguments)
                "list_devices" -> listDevices()
                "wait_for_device" -> waitForDevice(arguments)
                "list_holds" -> listHolds(arguments)
                "wait_for_hold" -> waitForHold(arguments)
                "clear_capture" -> clearCapture()
                "set_capturing" -> setCapturing(arguments)
                "set_max_retained" -> setMaxRetained(arguments)
                "set_map_local" -> setMapLocal(arguments)
                "remove_map_local" -> removeMapLocal(arguments)
                "list_map_local" -> listMapLocal()
                "get_map_local" -> getMapLocal(arguments)
                "set_map_local_enabled" -> setMapLocalEnabled(arguments)
                "set_capture_filter" -> setCaptureFilter(arguments)
                "set_capture_filter_enabled" -> setCaptureFilterEnabled(arguments)
                "list_capture_filter" -> listCaptureFilter()
                "set_breakpoint" -> setBreakpoint(arguments)
                "remove_breakpoint" -> removeBreakpoint(arguments)
                "list_breakpoints" -> listBreakpoints()
                "set_breakpoints_enabled" -> setBreakpointsEnabled(arguments)
                "set_seed" -> setSeed(arguments)
                "remove_seed" -> removeSeed(arguments)
                "list_seeds" -> listSeeds()
                "get_seed" -> getSeed(arguments)
                "set_seeds_enabled" -> setSeedsEnabled(arguments)
                "set_rule_group" -> setRuleGroup(arguments)
                "remove_rule_group" -> removeRuleGroup(arguments)
                "list_rule_groups" -> listRuleGroups(arguments)
                "fill_seeds" -> fillSeeds()
                "clear_seed_queue" -> clearSeedQueue()
                "resume_hold" -> resumeHold(arguments)
                "abort_hold" -> abortHold(arguments)
                else -> failure("Unknown Wailo tool: $name")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ToolFailure) {
            failure(failure.message.orEmpty())
        } catch (failure: Exception) {
            failure("Wailo tool failed: ${failure.message ?: failure::class.simpleName}")
        }
    }

    private fun status(): McpToolResponse {
        val data = mapOf(
            "listening" to backend.listening,
            "capture_port" to backend.capturePort,
            "lan_address" to backend.lanAddress,
            "capturing" to backend.capturing,
            "max_retained" to backend.maxRetained,
            "exchange_count" to backend.exchanges.size,
            "connected_device_count" to backend.connectedDevices.value.size,
            "hold_count" to backend.holds.size,
            "map_local_enabled" to backend.mapLocalEnabled,
            "map_local_rule_count" to backend.mapLocalRules.size,
            "breakpoints_enabled" to backend.breakpointsEnabled,
            "breakpoint_rule_count" to backend.breakpointRules.size,
            "seeds_enabled" to backend.seedsEnabled,
            "seed_count" to backend.seeds.size,
            "armed_seed_count" to backend.seedQueue.size,
            // The hosts the user singled out — a standing hint about which traffic is worth looking at
            // first, available here because it is daemon state rather than a Studio preference (ADR-0084).
            "bookmarked_hosts" to backend.bookmarkedHosts,
            // So a caller reading "<wailo:redacted>" knows the value was withheld rather than that the
            // app really sent that.
            "redacting_secrets" to backend.redactSecrets,
        )
        return success(
            "Wailo is listening on ${backend.lanAddress}:${backend.capturePort}; " +
                "${backend.connectedDevices.value.size} device(s), ${backend.exchanges.size} exchange(s).",
            data,
        )
    }

    private fun listExchanges(arguments: ToolArguments): McpToolResponse {
        val limit = arguments.int("limit", DEFAULT_LIST_LIMIT).inRange("limit", 1..MAX_LIST_LIMIT)
        val rows = backend.exchanges.takeLast(limit)
        return exchangeListResult(rows)
    }

    private fun searchTraffic(arguments: ToolArguments): McpToolResponse {
        val filter = arguments.trafficFilter()
        val limit = arguments.int("limit", DEFAULT_LIST_LIMIT).inRange("limit", 1..MAX_LIST_LIMIT)
        val rows = backend.searchTraffic(
            urlContains = filter.urlContains,
            urlPattern = filter.urlPattern,
            method = filter.method,
            statusCode = filter.statusCode,
            appId = filter.appId,
        ).takeLast(limit)
        return exchangeListResult(rows)
    }

    private suspend fun getExchange(arguments: ToolArguments): McpToolResponse {
        val id = arguments.requiredString("id")
        val bodyLimit = arguments.int("body_bytes", DEFAULT_BODY_LIMIT).inRange("body_bytes", 0..MAX_BODY_LIMIT)
        val row = backend.findExchangeById(id) ?: throw ToolFailure("Exchange not found: $id")
        return success(
            "${row.exchange.request?.method.orEmpty()} ${shown(row.exchange.request?.url.orEmpty())}",
            mapOf("exchange" to exchangeDetail(row, bodyLimit)),
        )
    }

    private suspend fun waitForExchange(arguments: ToolArguments): McpToolResponse {
        val filter = arguments.trafficFilter(includeApp = false)
        val timeout = arguments.int("timeout_seconds", 30).inRange("timeout_seconds", 1..MAX_WAIT_SECONDS)
        val row = backend.waitForExchange(timeout.seconds) { candidate ->
            val request = candidate.exchange.request
            val url = request?.url.orEmpty()
            val matchesUrl = when {
                filter.urlPattern != null -> urlPatternMatches(filter.urlPattern, url)
                filter.urlContains != null -> url.contains(filter.urlContains, ignoreCase = true)
                else -> true
            }
            matchesUrl &&
                (filter.method == null || request?.method.equals(filter.method, ignoreCase = true)) &&
                (filter.statusCode == null || candidate.exchange.response?.code == filter.statusCode)
        } ?: throw ToolFailure("Timed out after ${timeout}s waiting for a matching exchange")
        return success("Matched ${row.exchange.id}", mapOf("exchange" to exchangeSummary(row)))
    }

    private fun listDevices(): McpToolResponse {
        val devices = backend.connectedDevices.value.map {
            mapOf(
                "connection_id" to it.connectionId,
                "name" to it.deviceName,
                "app_id" to it.appId,
                "platform" to it.platform,
                "transport" to it.transport.name.lowercase(),
                "loopback" to it.loopback,
            )
        }
        return success("${devices.size} connected device(s)", mapOf("devices" to devices))
    }

    private suspend fun waitForDevice(arguments: ToolArguments): McpToolResponse {
        val timeout = arguments.int("timeout_seconds", 30).inRange("timeout_seconds", 1..MAX_WAIT_SECONDS)
        val appId = arguments.string("app_id")
        val deviceName = arguments.string("device_name")
        val device = withTimeoutOrNull(timeout.seconds) {
            backend.connectedDevices.first { devices ->
                devices.any {
                    (appId == null || it.appId == appId) &&
                        (deviceName == null || it.deviceName.contains(deviceName, ignoreCase = true))
                }
            }.first {
                (appId == null || it.appId == appId) &&
                    (deviceName == null || it.deviceName.contains(deviceName, ignoreCase = true))
            }
        } ?: throw ToolFailure("Timed out after ${timeout}s waiting for a matching device")
        return success(
            "Connected ${device.deviceName} (${device.appId})",
            mapOf(
                "device" to mapOf(
                    "connection_id" to device.connectionId,
                    "name" to device.deviceName,
                    "app_id" to device.appId,
                    "platform" to device.platform,
                    "transport" to device.transport.name.lowercase(),
                    "loopback" to device.loopback,
                ),
            ),
        )
    }

    private fun listHolds(arguments: ToolArguments): McpToolResponse {
        val bodyLimit = arguments.int("body_bytes", DEFAULT_BODY_LIMIT).inRange("body_bytes", 0..MAX_BODY_LIMIT)
        val holds = backend.holds.map { holdDetail(it, bodyLimit) }
        return success("${holds.size} paused exchange(s)", mapOf("holds" to holds))
    }

    private suspend fun waitForHold(arguments: ToolArguments): McpToolResponse {
        val contains = arguments.string("url_contains")
        val pattern = arguments.string("url_pattern")
        if (contains != null && pattern != null) {
            throw ToolFailure("Use only one of url_contains or url_pattern")
        }
        val method = arguments.string("method")
        val phase = arguments.string("phase")
        if (phase != null && phase !in setOf("request", "response")) {
            throw ToolFailure("phase must be request or response")
        }
        val timeout = arguments.int("timeout_seconds", 30).inRange("timeout_seconds", 1..MAX_WAIT_SECONDS)
        val bodyLimit = arguments.int("body_bytes", DEFAULT_BODY_LIMIT).inRange("body_bytes", 0..MAX_BODY_LIMIT)
        val hold = backend.waitForHold(timeout.seconds) {
            val request = it.request
            val matchesUrl = when {
                pattern != null -> urlPatternMatches(pattern, request?.url.orEmpty())
                contains != null -> request?.url.orEmpty().contains(contains, ignoreCase = true)
                else -> true
            }
            matchesUrl &&
                (method == null || request?.method.equals(method, ignoreCase = true)) &&
                (phase == null || phase == it.phaseName())
        } ?: throw ToolFailure("Timed out after ${timeout}s waiting for a matching hold")
        return success("Matched hold ${hold.correlationId}", mapOf("hold" to holdDetail(hold, bodyLimit)))
    }

    private suspend fun clearCapture(): McpToolResponse {
        backend.clear()
        return success("Captured exchanges cleared", mapOf("cleared" to true))
    }

    private suspend fun setCapturing(arguments: ToolArguments): McpToolResponse {
        val enabled = arguments.requiredBoolean("enabled")
        backend.setCapturing(enabled)
        return success("capturing=$enabled", mapOf("capturing" to enabled))
    }

    private suspend fun setMaxRetained(arguments: ToolArguments): McpToolResponse {
        val requested = arguments.requiredInt("max_retained")
            .inRange("max_retained", WailoEngine.RETAINED_RANGE)
        backend.setMaxRetained(requested)
        return success("max_retained=$requested", mapOf("max_retained" to requested))
    }

    private suspend fun setMapLocal(arguments: ToolArguments): McpToolResponse {
        val id = arguments.requiredString("id")
        val pattern = arguments.requiredString("url_pattern")
        val body = arguments.optionalBody()
        val statusCode = arguments.int("status_code", 200).inRange("status_code", 100..599)
        val groupId = arguments.string("group_id")
        backend.upsertMapLocalRule(
            HostMapLocalRule(
                id = id,
                name = arguments.string("name").orEmpty(),
                enabled = arguments.boolean("enabled", true),
                urlPattern = pattern,
                methods = arguments.strings("methods"),
                statusCode = statusCode,
                headers = arguments.headers(),
                body = body ?: ByteArray(0),
            ),
            groupId,
        )
        return success(
            "Map Local rule $id set",
            mapOf(
                "id" to id,
                "body_bytes" to (body?.size ?: 0),
                "status_code" to statusCode,
                "group_id" to backend.groupIdByRule(RULE_FAMILY_MAP_LOCAL)[id].orEmpty(),
            ),
        )
    }

    private suspend fun setRuleGroup(arguments: ToolArguments): McpToolResponse {
        val family = arguments.ruleFamily()
        val id = arguments.requiredString("id")
        val existing = backend.ruleGroups(family).firstOrNull { it.id == id }
        val group = DaemonRuleGroup(
            id = id,
            // Defaults come from the group as it stands, so setting only `enabled` is not also a rename.
            name = arguments.string("name") ?: existing?.name.orEmpty(),
            enabled = arguments.boolean("enabled", existing?.enabled ?: true),
        )
        backend.setRuleGroup(family, group)
        return success(
            "$family group $id set",
            mapOf("family" to family, "group" to group.summary()),
        )
    }

    private suspend fun removeRuleGroup(arguments: ToolArguments): McpToolResponse {
        val family = arguments.ruleFamily()
        val id = arguments.requiredString("id")
        val withRules = arguments.boolean("with_rules", false)
        if (!backend.removeRuleGroup(family, id, withRules)) throw ToolFailure("$family group not found: $id")
        return success(
            "$family group $id removed",
            mapOf("family" to family, "id" to id, "removed" to true, "with_rules" to withRules),
        )
    }

    private fun listRuleGroups(arguments: ToolArguments): McpToolResponse {
        val family = arguments.ruleFamily()
        val groups = backend.ruleGroups(family).map { it.summary() }
        return success("${groups.size} $family group(s)", mapOf("family" to family, "groups" to groups))
    }

    private fun DaemonRuleGroup.summary(): Map<String, Any?> =
        mapOf("id" to id, "name" to name, "enabled" to enabled)

    private fun ToolArguments.ruleFamily(): String {
        val family = requiredString("family")
        if (family !in RULE_FAMILIES) {
            throw ToolFailure("Unknown rule family: $family. Expected one of ${RULE_FAMILIES.joinToString()}.")
        }
        return family
    }

    private suspend fun removeMapLocal(arguments: ToolArguments): McpToolResponse {
        val id = arguments.requiredString("id")
        if (!backend.removeMapLocalRule(id)) throw ToolFailure("Map Local rule not found: $id")
        return success("Map Local rule $id removed", mapOf("id" to id, "removed" to true))
    }

    /**
     * Deliberately body-free, like [exchangeSummary]: fixtures are a curated set that grows, so carrying
     * every body here would make the cheap "what is loaded?" call scale with total fixture size.
     */
    private fun mapLocalSummary(rule: HostMapLocalRule): Map<String, Any?> = mapOf(
        "id" to rule.id,
        // Studio ids are generated, so the author's label is often the only human-readable handle.
        "name" to rule.name,
        "enabled" to rule.enabled,
        "url_pattern" to rule.urlPattern,
        "methods" to rule.methods,
        "status_code" to rule.statusCode,
        "headers" to rule.headers.map(::headerData),
        "body_bytes" to rule.bodySize,
        // Blank for an ungrouped rule. A rule in a group that is switched off already reads enabled
        // false above, since the daemon folds the group's switch in before anyone sees the rule.
        "group_id" to backend.groupIdByRule(RULE_FAMILY_MAP_LOCAL)[rule.id].orEmpty(),
    )

    private fun listMapLocal(): McpToolResponse {
        val rules = backend.mapLocalRules.map(::mapLocalSummary)
        return success(
            "${rules.size} Map Local rule(s)",
            mapOf(
                "enabled" to backend.mapLocalEnabled,
                "rules" to rules,
                "groups" to backend.ruleGroups(RULE_FAMILY_MAP_LOCAL).map { it.summary() },
            ),
        )
    }

    private fun getMapLocal(arguments: ToolArguments): McpToolResponse {
        val id = arguments.requiredString("id")
        val bodyLimit = arguments.int("body_bytes", DEFAULT_BODY_LIMIT).inRange("body_bytes", 0..MAX_BODY_LIMIT)
        val rule = backend.mapLocalRules.firstOrNull { it.id == id }
            ?: throw ToolFailure("Map Local rule not found: $id")
        return success(
            "Map Local rule $id",
            mapOf(
                // A rule can be enabled while the global toggle is off, which looks identical to a
                // non-matching pattern from the device side.
                "map_local_enabled" to backend.mapLocalEnabled,
                "rule" to mapLocalSummary(rule) +
                    mapOf("body" to renderBody(rule.bodyCopy(), rule.headers, bodyLimit)),
            ),
        )
    }

    private suspend fun setMapLocalEnabled(arguments: ToolArguments): McpToolResponse {
        val enabled = arguments.requiredBoolean("enabled")
        backend.setMapLocalEnabled(enabled)
        return success("map_local_enabled=$enabled", mapOf("enabled" to enabled))
    }

    private suspend fun setCaptureFilter(arguments: ToolArguments): McpToolResponse {
        val allow = arguments.strings("allow_patterns")
        val block = arguments.strings("block_patterns")
        backend.updateCaptureFilter(
            allowlistEnabled = allow.isNotEmpty(),
            allowPatterns = allow,
            blocklistEnabled = block.isNotEmpty(),
            blockPatterns = block,
        )
        return success(
            "Capture Filter updated: ${allow.size} allow, ${block.size} block",
            captureFilterData(),
        )
    }

    private suspend fun setCaptureFilterEnabled(arguments: ToolArguments): McpToolResponse {
        val enabled = arguments.requiredBoolean("enabled")
        backend.setCaptureFilterEnabled(enabled)
        return success("capture_filter_enabled=$enabled", captureFilterData())
    }

    private fun listCaptureFilter(): McpToolResponse =
        success("Current device-side Capture Filter", captureFilterData())

    private suspend fun setBreakpoint(arguments: ToolArguments): McpToolResponse {
        val id = arguments.requiredString("id")
        val onRequest = arguments.boolean("on_request", false)
        val onResponse = arguments.boolean("on_response", true)
        if (!onRequest && !onResponse) throw ToolFailure("A breakpoint must enable on_request, on_response, or both")
        backend.upsertBreakpointRule(
            HostBreakpointRule(
                id = id,
                enabled = arguments.boolean("enabled", true),
                urlPattern = arguments.requiredString("url_pattern"),
                methods = arguments.strings("methods"),
                onRequest = onRequest,
                onResponse = onResponse,
            ),
            arguments.string("group_id"),
        )
        return success(
            "Breakpoint $id set",
            mapOf("id" to id, "group_id" to backend.groupIdByRule(RULE_FAMILY_BREAKPOINTS)[id].orEmpty()),
        )
    }

    private suspend fun removeBreakpoint(arguments: ToolArguments): McpToolResponse {
        val id = arguments.requiredString("id")
        if (!backend.removeBreakpointRule(id)) throw ToolFailure("Breakpoint not found: $id")
        return success("Breakpoint $id removed", mapOf("id" to id, "removed" to true))
    }

    private fun listBreakpoints(): McpToolResponse {
        val groups = backend.groupIdByRule(RULE_FAMILY_BREAKPOINTS)
        val rules = backend.breakpointRules.map {
            mapOf(
                "id" to it.id,
                "enabled" to it.enabled,
                "url_pattern" to it.urlPattern,
                "methods" to it.methods,
                "on_request" to it.onRequest,
                "on_response" to it.onResponse,
                "group_id" to groups[it.id].orEmpty(),
            )
        }
        return success(
            "${rules.size} breakpoint rule(s)",
            mapOf(
                "enabled" to backend.breakpointsEnabled,
                "rules" to rules,
                "groups" to backend.ruleGroups(RULE_FAMILY_BREAKPOINTS).map { it.summary() },
            ),
        )
    }

    private suspend fun setBreakpointsEnabled(arguments: ToolArguments): McpToolResponse {
        val enabled = arguments.requiredBoolean("enabled")
        backend.setBreakpointsEnabled(enabled)
        return success("breakpoints_enabled=$enabled", mapOf("enabled" to enabled))
    }

    private suspend fun setSeed(arguments: ToolArguments): McpToolResponse {
        val id = arguments.requiredString("id")
        val body = arguments.optionalBody()
        val statusCode = arguments.int("status_code", 200).inRange("status_code", 100..599)
        backend.upsertSeed(
            HostSeed(
                id = id,
                enabled = arguments.boolean("enabled", true),
                urlPattern = arguments.requiredString("url_pattern"),
                method = arguments.string("method").orEmpty(),
                statusCode = statusCode,
                headers = arguments.headers(),
                body = body ?: ByteArray(0),
            ),
            arguments.string("group_id"),
        )
        // Writing a seed does not arm it: the library is the script, fill_seeds is where it starts.
        return success(
            "Seed $id set",
            mapOf(
                "id" to id,
                "body_bytes" to (body?.size ?: 0),
                "status_code" to statusCode,
                "armed" to false,
                "group_id" to backend.groupIdByRule(RULE_FAMILY_SEEDS)[id].orEmpty(),
            ),
        )
    }

    private suspend fun removeSeed(arguments: ToolArguments): McpToolResponse {
        val id = arguments.requiredString("id")
        if (!backend.removeSeed(id)) throw ToolFailure("Seed not found: $id")
        return success("Seed $id removed", mapOf("id" to id, "removed" to true))
    }

    /** Body-free for the same reason as [mapLocalSummary]; `get_seed` is where the bytes are. */
    private fun seedSummary(seed: HostSeed, armed: Set<String>): Map<String, Any?> = mapOf(
        "id" to seed.id,
        "enabled" to seed.enabled,
        "url_pattern" to seed.urlPattern,
        "method" to seed.method,
        "status_code" to seed.statusCode,
        "headers" to seed.headers.map(::headerData),
        "body_bytes" to seed.bodySize,
        // Being in the library is not being in play: a seed answers one hold and is then spent, so this
        // is the only field that says whether the next matching hold will actually get this response.
        "armed" to (seed.id in armed),
        "group_id" to backend.groupIdByRule(RULE_FAMILY_SEEDS)[seed.id].orEmpty(),
    )

    private fun listSeeds(): McpToolResponse {
        val armed = backend.seedQueue.mapTo(mutableSetOf()) { it.id }
        val seeds = backend.seeds.map { seedSummary(it, armed) }
        return success(
            "${seeds.size} seed(s), ${armed.size} armed",
            mapOf(
                "enabled" to backend.seedsEnabled,
                "seeds" to seeds,
                "groups" to backend.ruleGroups(RULE_FAMILY_SEEDS).map { it.summary() },
            ),
        )
    }

    private fun getSeed(arguments: ToolArguments): McpToolResponse {
        val id = arguments.requiredString("id")
        val bodyLimit = arguments.int("body_bytes", DEFAULT_BODY_LIMIT).inRange("body_bytes", 0..MAX_BODY_LIMIT)
        val seed = backend.seeds.firstOrNull { it.id == id } ?: throw ToolFailure("Seed not found: $id")
        val armed = backend.seedQueue.mapTo(mutableSetOf()) { it.id }
        return success(
            "Seed $id",
            mapOf(
                "seeds_enabled" to backend.seedsEnabled,
                "seed" to seedSummary(seed, armed) +
                    mapOf("body" to renderBody(seed.bodyCopy(), seed.headers, bodyLimit)),
            ),
        )
    }

    private suspend fun setSeedsEnabled(arguments: ToolArguments): McpToolResponse {
        val enabled = arguments.requiredBoolean("enabled")
        backend.setSeedsEnabled(enabled)
        return success("seeds_enabled=$enabled", mapOf("enabled" to enabled))
    }

    private suspend fun fillSeeds(): McpToolResponse {
        val remaining = backend.fillSeeds()
        return success(
            "$remaining seed(s) armed",
            mapOf("armed" to remaining, "hold_count" to backend.holds.size),
        )
    }

    private suspend fun clearSeedQueue(): McpToolResponse {
        backend.clearSeedQueue()
        return success("Seed queue cleared", mapOf("armed" to 0))
    }

    private suspend fun resumeHold(arguments: ToolArguments): McpToolResponse {
        val id = arguments.requiredString("correlation_id")
        val hold = backend.findHold(id) ?: throw ToolFailure("Hold not found: $id")
        val body = arguments.optionalBody()
        val headersProvided = arguments.contains("headers")
        val edited = when (hold.phase) {
            BreakpointPhase.BREAKPOINT_PHASE_REQUEST -> {
                if (arguments.contains("status_code")) {
                    throw ToolFailure("status_code only applies to response-phase holds")
                }
                val hasEdits = arguments.contains("method") || arguments.contains("url") || headersProvided || body != null
                val original = hold.request ?: throw ToolFailure("Request-phase hold has no request")
                (if (hasEdits) original.editedRequest(arguments, body, headersProvided) else null) to null
            }
            BreakpointPhase.BREAKPOINT_PHASE_RESPONSE -> {
                if (arguments.contains("method") || arguments.contains("url")) {
                    throw ToolFailure("method/url only apply to request-phase holds")
                }
                val hasEdits = arguments.contains("status_code") || headersProvided || body != null
                val original = hold.response ?: HttpResponse(code = 200)
                null to if (hasEdits) original.editedResponse(arguments, body, headersProvided) else null
            }
        }
        if (!backend.resumeHold(id, edited.first, edited.second)) throw ToolFailure("Hold no longer exists: $id")
        return success("Hold $id resumed", mapOf("correlation_id" to id, "edited" to (edited.first != null || edited.second != null)))
    }

    private suspend fun abortHold(arguments: ToolArguments): McpToolResponse {
        val id = arguments.requiredString("correlation_id")
        if (!backend.abortHold(id)) throw ToolFailure("Hold not found: $id")
        return success("Hold $id aborted", mapOf("correlation_id" to id, "aborted" to true))
    }

    private fun captureFilterData(): Map<String, Any?> {
        val filter = backend.captureFilter
        return mapOf(
            "enabled" to backend.captureFilterEnabled,
            "allowlist_enabled" to filter.allowlist_enabled,
            "allow_patterns" to filter.allow_patterns,
            "blocklist_enabled" to filter.blocklist_enabled,
            "block_patterns" to filter.block_patterns,
        )
    }

    private fun exchangeListResult(rows: List<CapturedExchange>): McpToolResponse {
        val summaries = rows.map(::exchangeSummary)
        return success("${rows.size} exchange(s)", mapOf("exchanges" to summaries))
    }

    private fun exchangeSummary(row: CapturedExchange): Map<String, Any?> = mapOf(
        "id" to row.exchange.id,
        "device_name" to row.deviceName,
        "app_id" to row.appId,
        "platform" to row.platform,
        "method" to row.exchange.request?.method,
        "url" to row.exchange.request?.url?.let(::shown),
        "status_code" to row.exchange.response?.code,
        "error" to row.exchange.error.takeIf(String::isNotBlank),
        "duration_ms" to row.exchange.duration_ms,
        "started_at_epoch_ms" to row.exchange.started_at_epoch_ms,
        "edited" to row.exchange.edited,
    )

    private suspend fun exchangeDetail(row: CapturedExchange, bodyLimit: Int): Map<String, Any?> =
        exchangeSummary(row) + mapOf(
            "request" to row.exchange.request?.let {
                requestData(it, spooled(row.requestBody, bodyLimit), row.requestBody.capturedBytes(), bodyLimit)
            },
            "response" to row.exchange.response?.let {
                responseData(it, spooled(row.responseBody, bodyLimit), row.responseBody.capturedBytes(), bodyLimit)
            },
        )

    /**
     * A hold's bytes are still inline: a breakpoint hit is an in-flight request the device is holding
     * open, not a recorded exchange, so it never went through the body store.
     */
    private fun holdDetail(hold: PausedExchange, bodyLimit: Int): Map<String, Any?> = mapOf(
        "correlation_id" to hold.correlationId,
        "device_name" to hold.deviceName,
        "app_id" to hold.appId,
        "platform" to hold.platform,
        "phase" to hold.phaseName(),
        "request" to hold.request?.let { requestData(it, it.body.toByteArray(), it.body.size.toLong(), bodyLimit) },
        "response" to hold.response?.let { responseData(it, it.body.toByteArray(), it.body.size.toLong(), bodyLimit) },
    )

    /**
     * Fetch as much of a captured body as redaction needs, which is not the same as what the agent asked
     * for: [renderBody] redacts before it truncates, because a JSON body cut to the output limit first
     * would no longer parse and would fall back to the coarser text sweep. Past [MAX_BODY_LIMIT] that
     * fallback is what happens anyway — the alternative is pulling a payload of unbounded size across
     * the control channel to redact a prefix of it.
     */
    private suspend fun spooled(ref: BodyRef?, limit: Int): ByteArray =
        if (ref == null) ByteArray(0) else backend.readBody(ref, maxOf(limit, MAX_BODY_LIMIT))

    private fun BodyRef?.capturedBytes(): Long = this?.size ?: 0L

    private fun requestData(
        request: HttpRequest,
        body: ByteArray,
        capturedBytes: Long,
        bodyLimit: Int,
    ): Map<String, Any?> = mapOf(
        "method" to request.method,
        "url" to shown(request.url),
        "headers" to shown(request.headers).map(::headerData),
        "body" to bodyData(body, capturedBytes, request.body_size, request.body_truncated, request.headers, bodyLimit),
    )

    private fun responseData(
        response: HttpResponse,
        body: ByteArray,
        capturedBytes: Long,
        bodyLimit: Int,
    ): Map<String, Any?> = mapOf(
        "status_code" to response.code,
        "message" to response.message,
        "headers" to shown(response.headers).map(::headerData),
        "body" to bodyData(body, capturedBytes, response.body_size, response.body_truncated, response.headers, bodyLimit),
    )

    private fun bodyData(
        bytes: ByteArray,
        capturedBytes: Long,
        declaredSize: Long,
        sourceTruncated: Boolean,
        headers: List<Header>,
        limit: Int,
    ): Map<String, Any?> {
        val rendered = renderBody(bytes, headers, limit)
        return rendered + mapOf(
            // A prefix fetch is a truncation too, and the caller can only tell from here: [renderBody]
            // sees the bytes it was handed and has no idea more of them exist.
            "output_truncated" to (rendered["output_truncated"] == true || bytes.size < capturedBytes),
            // Sizes describe the captured traffic, not this redacted view of it, so they stay as
            // captured — the handle knows the real length even when only a prefix of it was fetched.
            "captured_bytes" to capturedBytes,
            "declared_bytes" to declaredSize,
            "source_truncated" to sourceTruncated,
        )
    }

    /**
     * Shared by captured traffic and locally authored Map Local fixtures: a fixture is usually a copy of
     * a real response, so it has to pass the same redaction gate rather than a laxer one.
     */
    private fun renderBody(bytes: ByteArray, headers: List<Header>, limit: Int): Map<String, Any?> {
        val contentType = headers
            .firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }
            ?.value_
            .orEmpty()
            .substringBefore(';')
            .lowercase()
        val textual = contentType.startsWith("text/") ||
            contentType.contains("json") ||
            contentType.contains("xml") ||
            contentType.contains("javascript") ||
            contentType.contains("x-www-form-urlencoded")
        // Redact before truncating, then re-truncate: a limit applied first can cut a JSON body into
        // something unparseable, and the fallback for unparseable text is coarser than it needs to be.
        val shownBytes = when {
            !textual || !backend.redactSecrets -> bytes
            else -> redactBodyText(bytes.toString(Charsets.UTF_8), contentType).toByteArray(Charsets.UTF_8)
        }
        val selected = shownBytes.copyOfRange(0, minOf(shownBytes.size, limit))
        return mapOf(
            "encoding" to if (textual) "utf8" else "base64",
            "data" to if (textual) selected.toString(Charsets.UTF_8) else Base64.getEncoder().encodeToString(selected),
            "output_truncated" to (selected.size < shownBytes.size),
        )
    }

    private fun shown(url: String): String = if (backend.redactSecrets) redactUrl(url) else url

    private fun shown(headers: List<Header>): List<Header> =
        if (backend.redactSecrets) redactHeaders(headers) else headers

    private fun HttpRequest.editedRequest(
        arguments: ToolArguments,
        body: ByteArray?,
        headersProvided: Boolean,
    ) = HttpRequest(
        method = arguments.string("method") ?: method,
        url = arguments.string("url") ?: url,
        headers = if (headersProvided) arguments.headers().restoreRedacted(headers) else headers,
        body = body?.toByteString() ?: this.body,
        body_size = body?.size?.toLong() ?: body_size,
        body_truncated = if (body != null) false else body_truncated,
    )

    private fun HttpResponse.editedResponse(
        arguments: ToolArguments,
        body: ByteArray?,
        headersProvided: Boolean,
    ) = HttpResponse(
        code = arguments.intOrNull("status_code")?.inRange("status_code", 100..599) ?: code,
        message = message,
        headers = if (headersProvided) arguments.headers().restoreRedacted(headers) else headers,
        body = body?.toByteString() ?: this.body,
        body_size = body?.size?.toLong() ?: body_size,
        body_truncated = if (body != null) false else body_truncated,
    )

    private fun success(text: String, data: Map<String, Any?>): McpToolResponse = McpToolResponse(text, data)

    private fun failure(message: String): McpToolResponse =
        McpToolResponse(message, mapOf("error" to message), isError = true)

    private companion object {
        const val ACCESS_REFUSED = "Wailo AI tool access is turned off. Turn it on in Wailo Studio " +
            "under Settings \u2192 AI tool access, or run `wailo-cli set_mcp_access --on`."
        const val DEFAULT_LIST_LIMIT = 50
        const val MAX_LIST_LIMIT = 500
        const val DEFAULT_BODY_LIMIT = 4_096
        const val MAX_BODY_LIMIT = 1_000_000
        const val MAX_WAIT_SECONDS = 300
    }
}

private fun PausedExchange.phaseName(): String =
    if (phase == BreakpointPhase.BREAKPOINT_PHASE_REQUEST) "request" else "response"

private data class TrafficFilter(
    val urlContains: String?,
    val urlPattern: String?,
    val method: String?,
    val statusCode: Int?,
    val appId: String?,
)

private class ToolArguments(private val values: Map<String, Any?>) {
    fun contains(name: String): Boolean = values.containsKey(name)

    fun requiredString(name: String): String =
        string(name)?.takeIf(String::isNotBlank) ?: throw ToolFailure("$name is required")

    fun string(name: String): String? = values[name]?.let {
        it as? String ?: throw ToolFailure("$name must be a string")
    }

    fun requiredBoolean(name: String): Boolean =
        values[name] as? Boolean ?: throw ToolFailure("$name is required")

    fun boolean(name: String, default: Boolean): Boolean =
        values[name]?.let { it as? Boolean ?: throw ToolFailure("$name must be a boolean") } ?: default

    fun requiredInt(name: String): Int = intOrNull(name) ?: throw ToolFailure("$name is required")

    fun int(name: String, default: Int): Int = intOrNull(name) ?: default

    fun intOrNull(name: String): Int? = values[name]?.let {
        val number = it as? Number ?: throw ToolFailure("$name must be an integer")
        val long = number.toLong()
        if (number.toDouble() != long.toDouble() || long !in Int.MIN_VALUE..Int.MAX_VALUE) {
            throw ToolFailure("$name must be an integer")
        }
        long.toInt()
    }

    fun strings(name: String): List<String> {
        val raw = values[name] ?: return emptyList()
        val list = raw as? List<*> ?: throw ToolFailure("$name must be an array of strings")
        return list.map { it as? String ?: throw ToolFailure("$name must contain only strings") }
    }

    fun headers(): List<Header> {
        val raw = values["headers"] ?: return emptyList()
        val list = raw as? List<*> ?: throw ToolFailure("headers must be an array")
        return list.mapIndexed { index, item ->
            val entry = item as? Map<*, *> ?: throw ToolFailure("headers[$index] must be an object")
            val name = entry["name"] as? String
            val value = entry["value"] as? String
            if (name.isNullOrBlank() || value == null) {
                throw ToolFailure("headers[$index] requires string name and value")
            }
            Header(name = name, value_ = value)
        }
    }

    fun optionalBody(): ByteArray? {
        val textPresent = contains("body_text")
        val base64Present = contains("body_base64")
        if (textPresent && base64Present) throw ToolFailure("Use only one of body_text or body_base64")
        string("body_text")?.let { return it.toByteArray(Charsets.UTF_8) }
        string("body_base64")?.let {
            return try {
                Base64.getDecoder().decode(it)
            } catch (_: IllegalArgumentException) {
                throw ToolFailure("body_base64 is not valid Base64")
            }
        }
        return null
    }

    fun trafficFilter(includeApp: Boolean = true): TrafficFilter {
        val contains = string("url_contains")
        val pattern = string("url_pattern")
        if (contains != null && pattern != null) {
            throw ToolFailure("Use only one of url_contains or url_pattern")
        }
        return TrafficFilter(
            urlContains = contains,
            urlPattern = pattern,
            method = string("method"),
            statusCode = intOrNull("status_code")?.inRange("status_code", 100..599),
            appId = if (includeApp) string("app_id") else null,
        )
    }
}

private class ToolFailure(message: String) : IllegalArgumentException(message)

private fun Int.inRange(name: String, range: IntRange): Int =
    takeIf { it in range } ?: throw ToolFailure("$name must be between ${range.first} and ${range.last}")

private fun headerData(header: Header): Map<String, String> = mapOf("name" to header.name, "value" to header.value_)
