package com.venbiasa.wailo.cli

import com.venbiasa.wailo.daemon.DaemonClient
import com.venbiasa.wailo.daemon.DaemonRuleGroup
import com.venbiasa.wailo.daemon.RULE_FAMILIES
import com.venbiasa.wailo.daemon.RULE_FAMILY_BREAKPOINTS
import com.venbiasa.wailo.daemon.RULE_FAMILY_MAP_LOCAL
import com.venbiasa.wailo.daemon.RULE_FAMILY_SEEDS
import com.venbiasa.wailo.daemon.groupIdByRule
import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.HostSeed
import com.venbiasa.wailo.host.urlPatternMatches
import com.venbiasa.wailo.host.summarizeExchanges
import com.venbiasa.wailo.host.withEdits
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

/** CLI frontend over the shared local daemon (ADR-0058). */
fun main(args: Array<String>) = runBlocking {
    val parsed = parseArgs(args) ?: run {
        printUsage()
        exitProcess(2)
    }
    when (parsed.command) {
        "help", "--help", "-h" -> {
            printUsage()
            exitProcess(0)
        }
        "serve" -> runServe(parsed)
        else -> runRemote(parsed)
    }
}

private suspend fun runServe(parsed: ParsedArgs) {
    val daemon = try {
        DaemonClient.connect(
            initialCapturePort = parsed.port.takeIf { parsed.portSpecified },
            holdPresence = parsed.keep,
        )
    } catch (failure: Exception) {
        System.err.println("Could not start the Wailo daemon: ${failure.message}")
        exitProcess(1)
    }
    if (parsed.portSpecified && !daemon.rebind(parsed.port)) {
        daemon.close()
        System.err.println("Could not bind Wailo capture to port ${parsed.port}")
        exitProcess(1)
    }
    println(
        "Wailo daemon is running; capture on ${daemon.capturePort.value}, " +
            "control on 127.0.0.1:${daemon.controlPort ?: 0}",
    )
    if (!parsed.keep) {
        daemon.close()
        return
    }
    println("Holding it open; press Ctrl-C to release it.")
    try {
        awaitCancellation()
    } finally {
        daemon.close()
    }
}

private suspend fun runRemote(parsed: ParsedArgs) {
    val daemon = try {
        DaemonClient.connect(
            initialCapturePort = parsed.port.takeIf {
                parsed.portSpecified && parsed.command == "rebind"
            },
            // The waits are the only commands that outlive a moment, so they are the only ones that may
            // pin the daemon; a one-shot that did would take it down again the instant it printed.
            holdPresence = parsed.command in BLOCKING_COMMANDS,
        )
    } catch (failure: Exception) {
        System.err.println("Could not connect to the Wailo daemon: ${failure.message}")
        exitProcess(2)
    }
    val result = try {
        dispatch(daemon, parsed)
    } catch (failure: Exception) {
        CommandResult(
            "Wailo command failed: ${failure.message}",
            exitCode = 1,
        )
    } finally {
        daemon.close()
    }
    if (result.message.isNotEmpty()) println(result.message)
    exitProcess(result.exitCode)
}

private val BLOCKING_COMMANDS = setOf(
    "wait_exchange", "wait-exchange",
    "wait_hold", "wait-hold",
    "wait_device", "wait-device",
)

internal data class CommandResult(val message: String, val exitCode: Int = 0)

internal suspend fun dispatch(host: DaemonClient, args: ParsedArgs): CommandResult {
    return when (args.command) {
        "list_exchanges", "list-exchanges" ->
            CommandResult(summarizeExchanges(host.listExchanges(), args.limit))
        "search_traffic", "search-traffic" -> {
            val rows = host.searchTraffic(
                urlContains = args.urlContains,
                urlPattern = args.urlPattern,
                method = args.method,
                statusCode = args.statusCode,
                appId = args.appId,
            )
            CommandResult(
                if (rows.isEmpty()) {
                    "(no matches)"
                } else {
                    summarizeExchanges(rows, args.limit)
                },
            )
        }
        "get_exchange", "get-exchange" -> {
            val id = args.id ?: return CommandResult("get_exchange requires --id", exitCode = 2)
            val row = host.findExchangeById(id)
                ?: return CommandResult("exchange not found: $id", exitCode = 1)
            val req = row.exchange.request
            val res = row.exchange.response
            CommandResult(
                buildString {
                    appendLine("id=${row.exchange.id}")
                    appendLine("device=${row.deviceName} app=${row.appId} platform=${row.platform}")
                    appendLine("request ${req?.method} ${req?.url}")
                    req?.headers?.forEach { appendLine("  > ${it.name}: ${it.value_}") }
                    appendLine("response ${res?.code ?: row.exchange.error}")
                    res?.headers?.forEach { appendLine("  < ${it.name}: ${it.value_}") }
                    // One extra byte past the ceiling is what tells "exactly this long" from "longer
                    // than we printed" without pulling a payload of unbounded size off the daemon.
                    val shownRef = row.responseBody ?: row.requestBody
                    val fetched = shownRef?.let { host.readBody(it, length = args.bodyChars + 1) }
                    if (fetched != null && fetched.isNotEmpty()) {
                        appendLine("--- body ---")
                        append(String(fetched, 0, minOf(fetched.size, args.bodyChars), Charsets.UTF_8))
                        if (shownRef.size > args.bodyChars) append("\n… truncated")
                    }
                }.trimEnd(),
            )
        }
        "set_map_local", "set-map-local" -> setMapLocal(host, args)
        "remove_map_local", "remove-map-local" -> {
            val id = args.id ?: return CommandResult("remove_map_local requires --id", exitCode = 2)
            if (host.removeMapLocalRule(id)) {
                CommandResult("removed Map Local rule $id")
            } else {
                CommandResult("Map Local rule not found: $id", exitCode = 1)
            }
        }
        "list_map_local", "list-map-local" -> {
            val rules = host.mapLocalRules.value
            val groups = host.nodesFor(RULE_FAMILY_MAP_LOCAL).groupIdByRule { it }
            CommandResult(
                if (rules.isEmpty()) {
                    "(no Map Local rules)"
                } else {
                    rules.joinToString("\n") { rule ->
                        val methods = rule.methods.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "*"
                        val group = groups[rule.id]?.let { "\t[$it]" }.orEmpty()
                        "${rule.id}\t${if (rule.enabled) "on" else "off"}\t$methods\t${rule.statusCode}\t${rule.bodySize}B\t${rule.urlPattern}$group"
                    }
                },
            )
        }
        "get_map_local", "get-map-local" -> {
            val id = args.id ?: return CommandResult("get_map_local requires --id", exitCode = 2)
            val rule = host.mapLocalRules.value.firstOrNull { it.id == id }
                ?: return CommandResult("Map Local rule not found: $id", exitCode = 1)
            val group = host.nodesFor(RULE_FAMILY_MAP_LOCAL).groupIdByRule { it }[id]
            CommandResult(
                buildString {
                    appendLine("id=${rule.id}")
                    if (rule.name.isNotBlank()) appendLine("name=${rule.name}")
                    appendLine("enabled=${rule.enabled} map_local_enabled=${host.mapLocalEnabled.value}")
                    appendLine("url_pattern=${rule.urlPattern}")
                    appendLine("method=${rule.methods.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "*"}")
                    appendLine("status=${rule.statusCode}")
                    group?.let { appendLine("group=$it") }
                    rule.headers.forEach { appendLine("  < ${it.name}: ${it.value_}") }
                    append(ruleBody(host, RULE_FAMILY_MAP_LOCAL, rule.id, rule.bodySize, args.bodyChars))
                }.trimEnd(),
            )
        }
        "set_rule_group", "set-rule-group" -> setRuleGroup(host, args)
        "remove_rule_group", "remove-rule-group" -> removeRuleGroup(host, args)
        "list_rule_groups", "list-rule-groups" -> listRuleGroups(host, args)
        "set_map_local_enabled", "set-map-local-enabled" -> {
            val enabled = args.flag
                ?: return CommandResult("set_map_local_enabled requires --on or --off", exitCode = 2)
            host.setMapLocalEnabled(enabled)
            CommandResult("map_local_enabled=$enabled")
        }
        "set_breakpoint", "set-breakpoint" -> setBreakpoint(host, args)
        "remove_breakpoint", "remove-breakpoint" -> {
            val id = args.id ?: return CommandResult("remove_breakpoint requires --id", exitCode = 2)
            if (host.removeBreakpointRule(id)) {
                CommandResult("removed breakpoint $id")
            } else {
                CommandResult("breakpoint not found: $id", exitCode = 1)
            }
        }
        "list_breakpoints", "list-breakpoints" -> {
            val rules = host.breakpointRules.value
            val groups = host.nodesFor(RULE_FAMILY_BREAKPOINTS).groupIdByRule { it }
            CommandResult(
                buildString {
                    appendLine("breakpoints_enabled=${host.breakpointsEnabled.value}")
                    if (rules.isEmpty()) {
                        append("(no breakpoints)")
                    } else {
                        rules.forEach { rule ->
                            val methods = rule.methods.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "*"
                            val group = groups[rule.id]?.let { "\t[$it]" }.orEmpty()
                            appendLine(
                                "${rule.id}\t${if (rule.enabled) "on" else "off"}\t$methods\t" +
                                    "${phaseLabel(rule.onRequest, rule.onResponse)}\t${rule.urlPattern}$group",
                            )
                        }
                    }
                }.trimEnd(),
            )
        }
        "set_breakpoints_enabled", "set-breakpoints-enabled" -> {
            val enabled = args.flag
                ?: return CommandResult("set_breakpoints_enabled requires --on or --off", exitCode = 2)
            host.setBreakpointsEnabled(enabled)
            CommandResult("breakpoints_enabled=$enabled")
        }
        "set_seed", "set-seed" -> setSeed(host, args)
        "remove_seed", "remove-seed" -> {
            val id = args.id ?: return CommandResult("remove_seed requires --id", exitCode = 2)
            if (host.removeSeed(id)) {
                CommandResult("removed seed $id")
            } else {
                CommandResult("seed not found: $id", exitCode = 1)
            }
        }
        "list_seeds", "list-seeds" -> {
            val seeds = host.seeds.value
            val armed = host.seedQueue.value.map { it.id }.toSet()
            CommandResult(
                buildString {
                    // The master gates the whole list, so an armed seed under `seeds_enabled=false` is one
                    // that will not fire — without this line the armed column reads as a promise.
                    appendLine("seeds_enabled=${host.seedsEnabled.value}")
                    if (seeds.isEmpty()) {
                        append("(no seeds)")
                    } else {
                        seeds.forEach { seed ->
                            val method = seed.method.takeIf { it.isNotBlank() } ?: "*"
                            val state = if (seed.enabled) "on" else "off"
                            // A seed is spent when it answers a hold, so "in the library" and "still
                            // waiting to fire" are different questions a scripted run needs both of.
                            val queued = if (seed.id in armed) "armed" else "-"
                            appendLine(
                                "${seed.id}\t$state\t$method\t${seed.statusCode}\t" +
                                    "${seed.bodySize}B\t$queued\t${seed.urlPattern}",
                            )
                        }
                    }
                }.trimEnd(),
            )
        }
        "get_seed", "get-seed" -> {
            val id = args.id ?: return CommandResult("get_seed requires --id", exitCode = 2)
            val seed = host.seeds.value.firstOrNull { it.id == id }
                ?: return CommandResult("seed not found: $id", exitCode = 1)
            val group = host.nodesFor(RULE_FAMILY_SEEDS).groupIdByRule { it }[id]
            CommandResult(
                buildString {
                    appendLine("id=${seed.id}")
                    appendLine("enabled=${seed.enabled} seeds_enabled=${host.seedsEnabled.value}")
                    appendLine("url_pattern=${seed.urlPattern}")
                    appendLine("method=${seed.method.takeIf { it.isNotBlank() } ?: "*"}")
                    appendLine("status=${seed.statusCode}")
                    appendLine("armed=${host.seedQueue.value.any { it.id == seed.id }}")
                    group?.let { appendLine("group=$it") }
                    seed.headers.forEach { appendLine("  < ${it.name}: ${it.value_}") }
                    append(ruleBody(host, RULE_FAMILY_SEEDS, seed.id, seed.bodySize, args.bodyChars))
                }.trimEnd(),
            )
        }
        "set_seeds_enabled", "set-seeds-enabled" -> {
            val enabled = args.flag
                ?: return CommandResult("set_seeds_enabled requires --on or --off", exitCode = 2)
            host.setSeedsEnabled(enabled)
            CommandResult("seeds_enabled=$enabled")
        }
        "fill_seeds", "fill-seeds" -> {
            val armed = host.fillSeeds()
            CommandResult("armed $armed seed(s)")
        }
        "clear_seed_queue", "clear-seed-queue" -> {
            host.clearSeedQueue()
            CommandResult("seed queue cleared")
        }
        "set_capture_filter", "set-capture-filter" -> {
            val current = host.captureFilter.value
            // Patterns replace the list they are given for, and a list arms itself by having any. Naming
            // only the switch keeps that list's patterns: disarming a populated allowlist is a state
            // Studio can hold (ADR-0082), and having to resend the list to reach it is not holding it.
            val allow = if (args.allowPatterns.isNotEmpty() || args.allowlistEnabled == null) {
                args.allowPatterns
            } else {
                current.allow_patterns
            }
            val block = if (args.blockPatterns.isNotEmpty() || args.blocklistEnabled == null) {
                args.blockPatterns
            } else {
                current.block_patterns
            }
            val allowlist = args.allowlistEnabled ?: allow.isNotEmpty()
            val blocklist = args.blocklistEnabled ?: block.isNotEmpty()
            host.updateCaptureFilter(
                allowlistEnabled = allowlist,
                allowPatterns = allow,
                blocklistEnabled = blocklist,
                blockPatterns = block,
            )
            CommandResult(
                "capture_filter allow=${allow.size} allowlist=$allowlist " +
                    "block=${block.size} blocklist=$blocklist" +
                    // An armed allowlist admits only what it lists, so an empty one admits nothing. Studio
                    // can author that too, so it is said out loud rather than refused.
                    if (allowlist && allow.isEmpty()) "\nAn armed allowlist with no patterns captures nothing." else "",
            )
        }
        "set_capture_filter_enabled", "set-capture-filter-enabled" -> {
            val enabled = args.flag
                ?: return CommandResult("set_capture_filter_enabled requires --on or --off", exitCode = 2)
            host.setCaptureFilterEnabled(enabled)
            CommandResult("capture_filter_enabled=$enabled")
        }
        "clear_capture_filter", "clear-capture-filter" -> {
            host.updateCaptureFilter(false, emptyList(), false, emptyList())
            CommandResult("capture_filter cleared")
        }
        "list_capture_filter", "list-capture-filter" -> {
            val filter = host.captureFilter.value
            CommandResult(
                buildString {
                    appendLine("enabled=${host.captureFilterEnabled.value}")
                    appendLine("allowlist=${filter.allowlist_enabled}")
                    filter.allow_patterns.forEach { appendLine("  allow $it") }
                    appendLine("blocklist=${filter.blocklist_enabled}")
                    filter.block_patterns.forEach { appendLine("  block $it") }
                }.trimEnd(),
            )
        }
        "list_devices", "list-devices" -> {
            val devices = host.listDevices()
            CommandResult(
                if (devices.isEmpty()) {
                    "(no devices)"
                } else {
                    devices.joinToString("\n") {
                        "${it.connectionId}\t${it.deviceName}\t${it.appId}\t${it.platform}\t${it.transport}"
                    }
                },
            )
        }
        "list_holds", "list-holds" -> {
            val holds = host.listHolds()
            CommandResult(
                if (holds.isEmpty()) {
                    "(no holds)"
                } else {
                    // A body per row would bury the list, so the many-holds view stays one line each
                    // unless --body-chars is asked for by name; wait_hold returns one and shows it.
                    val bodyChars = if (args.bodyCharsSpecified) args.bodyChars else 0
                    holds.joinToString("\n") { holdSummary(it, bodyChars) }
                },
            )
        }
        "resume_hold", "resume-hold" -> resumeHold(host, args)
        "abort_hold", "abort-hold" -> {
            val id = args.id ?: return CommandResult("abort_hold requires --id (correlation id)", exitCode = 2)
            if (host.abortHold(id)) {
                CommandResult("aborted $id")
            } else {
                CommandResult("hold not found: $id", exitCode = 1)
            }
        }
        "clear_capture", "clear-capture", "clear" -> {
            host.clear()
            CommandResult("cleared")
        }
        "set_capturing", "set-capturing" -> {
            val enabled = args.flag ?: return CommandResult("set_capturing requires --on or --off", exitCode = 2)
            host.setCapturing(enabled)
            CommandResult("capturing=$enabled")
        }
        "wait_exchange", "wait-exchange" -> {
            val row = host.waitForExchange(timeout = args.timeoutSeconds.seconds) { candidate ->
                val request = candidate.exchange.request
                val url = request?.url.orEmpty()
                val matchesUrl = when {
                    args.urlPattern != null -> urlPatternMatches(args.urlPattern, url)
                    args.urlContains != null -> url.contains(args.urlContains, ignoreCase = true)
                    else -> true
                }
                matchesUrl &&
                    (args.method == null || request?.method.equals(args.method, ignoreCase = true)) &&
                    (args.statusCode == null || candidate.exchange.response?.code == args.statusCode)
            } ?: return CommandResult("timeout waiting for exchange", exitCode = 1)
            CommandResult("${row.exchange.id}\t${row.exchange.request?.method}\t${row.exchange.response?.code}\t${row.exchange.request?.url}")
        }
        "wait_hold", "wait-hold" -> {
            val phase = args.phase
            if (phase != null && phase !in setOf("request", "response")) {
                return CommandResult("--phase must be request or response", exitCode = 2)
            }
            val hold = host.waitForHold(timeout = args.timeoutSeconds.seconds) { candidate ->
                val request = candidate.request
                val url = request?.url.orEmpty()
                val matchesUrl = when {
                    args.urlPattern != null -> urlPatternMatches(args.urlPattern, url)
                    args.urlContains != null -> url.contains(args.urlContains, ignoreCase = true)
                    else -> true
                }
                matchesUrl &&
                    (args.method == null || request?.method.equals(args.method, ignoreCase = true)) &&
                    (phase == null || phase == candidate.phaseName())
            } ?: return CommandResult("timeout waiting for hold", exitCode = 1)
            CommandResult(holdSummary(hold, args.bodyChars))
        }
        "wait_device", "wait-device" -> {
            val device = host.waitForDevice(timeout = args.timeoutSeconds.seconds) { candidate ->
                (args.appId == null || candidate.appId == args.appId) &&
                    (args.deviceName == null || candidate.deviceName.contains(args.deviceName, ignoreCase = true))
            } ?: return CommandResult("timeout waiting for device", exitCode = 1)
            CommandResult(
                "${device.connectionId}\t${device.deviceName}\t${device.appId}\t" +
                    "${device.platform}\t${device.transport}",
            )
        }
        "set_max_retained", "set-max-retained" -> {
            val value = args.count ?: return CommandResult("set_max_retained requires --count N", exitCode = 2)
            if (value !in WailoEngine.RETAINED_RANGE) {
                return CommandResult(
                    "--count must be between ${WailoEngine.RETAINED_RANGE.first} and " +
                        "${WailoEngine.RETAINED_RANGE.last}",
                    exitCode = 2,
                )
            }
            host.setMaxRetained(value)
            CommandResult("max_retained=$value")
        }
        "set_usb_port", "set-usb-port" -> {
            if (!args.portSpecified) return CommandResult("set_usb_port requires --port N", exitCode = 2)
            host.setUsbPort(args.port)
            CommandResult("usb_port=${args.port}")
        }
        "rebind" -> {
            val ok = host.rebind(args.port)
            CommandResult(if (ok) "rebound to ${args.port}" else "rebind failed for ${args.port}", exitCode = if (ok) 0 else 1)
        }
        "set_mcp_access", "set-mcp-access", "mcp_access", "mcp-access" -> {
            val enabled = args.flag ?: return CommandResult("set_mcp_access requires --on or --off", exitCode = 2)
            host.setMcpAccess(enabled)
            CommandResult("mcp_access=$enabled")
        }
        "set_mcp_redaction", "set-mcp-redaction", "mcp_redaction", "mcp-redaction" -> {
            val enabled = args.flag ?: return CommandResult("set_mcp_redaction requires --on or --off", exitCode = 2)
            host.setMcpRedactSecrets(enabled)
            CommandResult("mcp_redaction=$enabled")
        }
        "set_proxy", "set-proxy", "proxy" -> {
            val enabled = args.flag ?: return CommandResult("set_proxy requires --on or --off", exitCode = 2)
            val status = host.setProxyEnabled(enabled, args.port.takeIf { args.portSpecified })
            CommandResult(
                if (status.running) {
                    // The bind is reported at the start, not only when it is changed: since ADR-0077 the
                    // wide one is the default, so this may be the first and only time the user is told.
                    "proxy=on address=${status.reachableAddress} " +
                        "bind=${if (status.lan) "lan" else "loopback"}" +
                        if (status.lan) {
                            "\nAnything that can reach this machine can use it as a proxy while it runs. " +
                                "set_proxy_lan --off keeps it to this machine."
                        } else {
                            ""
                        }
                } else {
                    "proxy=off" + (status.error?.let { " error=$it" } ?: "")
                },
                // A start that could not bind is a failure the caller has to be able to branch on.
                exitCode = if (enabled && !status.running) 1 else 0,
            )
        }
        "proxy_status", "proxy-status" -> host.proxy.value.let { status ->
            CommandResult(
                "proxy=${if (status.running) "on" else "off"} address=${status.reachableAddress} " +
                    "bind=${if (status.lan) "lan" else "loopback"} " +
                    "clients=${status.connections} exchanges=${status.exchanges} " +
                    "ca=${if (status.caInstalled) "installed" else "none"} " +
                    "decrypt=${status.decryptHosts.ifEmpty { listOf("none") }.joinToString(",")} " +
                    "system=${if (status.systemProxy) "on" else "off"}" +
                    (status.chainedTo.takeIf { it.isNotEmpty() }?.let { " via=$it" } ?: "") +
                    (status.error?.let { " error=$it" } ?: ""),
            )
        }
        "set_system_proxy", "set-system-proxy" -> {
            val enabled = args.flag ?: return CommandResult("set_system_proxy requires --on or --off", exitCode = 2)
            val status = host.setSystemProxy(enabled)
            if (enabled && !status.systemProxy) {
                return CommandResult(
                    "system=off error=${status.error ?: "this machine's proxy settings could not be changed"}",
                    exitCode = 1,
                )
            }
            CommandResult(
                if (status.systemProxy) {
                    "system=on address=${status.reachableAddress}" +
                        (status.chainedTo.takeIf { it.isNotEmpty() }?.let { " via=$it" } ?: "") +
                        "\nThis machine now goes through Wailo. It is restored when the proxy stops."
                } else {
                    "system=off (settings restored)"
                },
            )
        }
        "set_proxy_lan", "set-proxy-lan" -> {
            val enabled = args.flag ?: return CommandResult("set_proxy_lan requires --on or --off", exitCode = 2)
            val status = host.setProxyLan(enabled)
            CommandResult(
                if (status.lan) {
                    "bind=lan address=${status.reachableAddress}\n" +
                        "Anything that can reach this machine can now use it as a proxy. " +
                        "Use it on a network you trust, and turn it off when you are done.\n" +
                        "A device gets the certificate and its steps at http://${status.reachableAddress}"
                } else {
                    "bind=loopback address=${status.reachableAddress}"
                },
            )
        }
        "set_proxy_decrypt", "set-proxy-decrypt" -> {
            // --off is "relock everything", so revoking never means remembering what to omit.
            val hosts = if (args.flag == false) emptyList() else args.hosts
            if (hosts.isEmpty() && args.flag != false) {
                return CommandResult("set_proxy_decrypt requires --host PATTERN, or --off to relock", exitCode = 2)
            }
            val status = host.setProxyDecryptHosts(hosts)
            CommandResult("decrypt=${status.decryptHosts.ifEmpty { listOf("none") }.joinToString(",")}")
        }
        "proxy_ca", "proxy-ca" -> host.proxyCertificate().let { certificate ->
            if (!certificate.installed) {
                return CommandResult(
                    "no local root: ${certificate.error ?: "this machine has nowhere to keep the key"}",
                    exitCode = 1,
                )
            }
            args.out?.let { path -> Files.writeString(Path.of(path), certificate.pem) }
            CommandResult(
                "ca=${certificate.commonName} sha256=${certificate.sha256} " +
                    "expires=${certificate.expiresEpochMs}" +
                    (args.out?.let { " written=$it" } ?: "\n${certificate.pem.trimEnd()}"),
            )
        }
        "rotate_proxy_ca", "rotate-proxy-ca" -> host.rotateProxyCertificate().let { certificate ->
            if (!certificate.installed) {
                return CommandResult("rotate failed: ${certificate.error ?: "no local root"}", exitCode = 1)
            }
            CommandResult("ca=${certificate.commonName} sha256=${certificate.sha256} (reinstall it to keep decrypting)")
        }
        "remove_proxy_ca", "remove-proxy-ca" -> {
            host.removeProxyCertificate()
            CommandResult("ca=removed (also remove it from the system trust store)")
        }
        "status", "daemon_status", "daemon-status" -> CommandResult(
            "listening=${host.listening.value} port=${host.capturePort.value} " +
                "devices=${host.connectedDevices.value.size} exchanges=${host.exchanges.value.size} " +
                "capturing=${host.capturing.value} " +
                "proxy=${if (host.proxy.value.running) "on:${host.proxy.value.port}" else "off"} " +
                "bookmarks=${host.bookmarkedHosts.value.size} " +
                "mcp_access=${host.mcpAccess.value} mcp_redaction=${host.mcpRedactSecrets.value}",
        )
        // Pairing without a window (ADR-0058): the one-shot half only. Approving an offer is a trust
        // decision made while looking at a code on a screen, so `begin` stays where that screen is.
        "set_require_pairing", "set-require-pairing" -> {
            val enabled = args.flag
                ?: return CommandResult("set_require_pairing requires --on or --off", exitCode = 2)
            host.setRequirePairing(enabled)
            CommandResult("require_pairing=$enabled")
        }
        "list_paired", "list-paired" -> host.pairing.value.let { pairing ->
            CommandResult(
                buildString {
                    appendLine("supported=${pairing.supported} require_pairing=${pairing.requirePairing}")
                    if (pairing.devices.isEmpty()) {
                        appendLine("(no paired devices)")
                    } else {
                        pairing.devices.forEach {
                            appendLine(
                                "${it.deviceId}\t${it.name}\t" +
                                    "${if (it.trustedOnFirstUse) "trusted-on-first-use" else "paired"}\t" +
                                    "last_seen=${it.lastSeenEpochMs}",
                            )
                        }
                    }
                    // A refusal is why a device that looks connected sends nothing, so it belongs in the
                    // answer here too rather than only in the window that happens to be open.
                    pairing.refusals.forEach { appendLine("refused ${it.deviceId}: ${it.reason}") }
                }.trimEnd(),
            )
        }
        "forget_device", "forget-device" -> {
            val id = args.id ?: return CommandResult("forget_device requires --id", exitCode = 2)
            host.forgetDevice(id)
            CommandResult("forgot $id")
        }
        "forget_all_devices", "forget-all-devices" -> {
            host.forgetAllDevices()
            CommandResult("forgot every paired device")
        }
        "reset_identity", "reset-identity" -> {
            host.resetIdentity()
            CommandResult("identity reset; every device has to pair again")
        }
        "list_bookmarks", "list-bookmarks" -> host.bookmarkedHosts.value.let { hosts ->
            CommandResult(if (hosts.isEmpty()) "no bookmarked hosts" else hosts.joinToString("\n"))
        }
        "set_bookmark", "set-bookmark" -> {
            val target = args.hosts.singleOrNull()
                ?: return CommandResult("set_bookmark requires exactly one --host HOST", exitCode = 2)
            host.setBookmarked(target, bookmarked = args.flag ?: true)
            CommandResult("bookmark $target ${if (args.flag == false) "removed" else "set"}")
        }
        "stop", "daemon_stop", "daemon-stop" -> {
            host.stopDaemon()
            CommandResult("Wailo daemon stopped")
        }
        else -> CommandResult("unknown command: ${args.command}", exitCode = 2)
    }
}

private suspend fun setMapLocal(host: DaemonClient, args: ParsedArgs): CommandResult {
    val canned = when (val parsed = parseCannedResponse("set_map_local", "Map Local", args)) {
        is CannedResponse.Rejected -> return parsed.result
        is CannedResponse.Parsed -> parsed
    }
    host.upsertMapLocalRule(
        HostMapLocalRule(
            id = canned.id,
            name = args.name.orEmpty(),
            enabled = args.flag ?: true,
            urlPattern = canned.urlPattern,
            methods = args.method?.let(::listOf).orEmpty(),
            statusCode = canned.statusCode,
            headers = canned.headers,
            body = canned.body,
        ),
        args.groupId,
    )
    return CommandResult("Map Local rule ${canned.id} set (${canned.body.size} bytes)")
}

private suspend fun setBreakpoint(host: DaemonClient, args: ParsedArgs): CommandResult {
    val id = args.id ?: return CommandResult("set_breakpoint requires --id", exitCode = 2)
    val pattern = args.urlPattern ?: return CommandResult("set_breakpoint requires --url-pattern", exitCode = 2)
    if (id.isBlank()) return CommandResult("breakpoint id must not be blank", exitCode = 2)
    if (pattern.isBlank()) return CommandResult("breakpoint URL pattern must not be blank", exitCode = 2)
    // Naming neither phase holds the response, which is the one a breakpoint is usually set for. Naming
    // one holds only that one, so --on-request cannot quietly stop the response as well.
    val onRequest = args.onRequest ?: false
    val onResponse = args.onResponse ?: !onRequest
    host.upsertBreakpointRule(
        HostBreakpointRule(
            id = id,
            enabled = args.flag ?: true,
            urlPattern = pattern,
            methods = args.method?.let(::listOf).orEmpty(),
            onRequest = onRequest,
            onResponse = onResponse,
        ),
        args.groupId,
    )
    return CommandResult("breakpoint $id set (${phaseLabel(onRequest, onResponse)})")
}

/**
 * Resumes a hold, rewriting what continues when asked (ADR-0067). A flag that does not apply to the phase
 * the hold is in is refused rather than ignored: a script that believed it replaced a status code has to
 * hear that the request had not been answered yet.
 */
private suspend fun resumeHold(host: DaemonClient, args: ParsedArgs): CommandResult {
    val id = args.id ?: return CommandResult("resume_hold requires --id (correlation id)", exitCode = 2)
    val hold = host.findHold(id) ?: return CommandResult("hold not found: $id", exitCode = 1)
    val body = when {
        args.bodyFile != null && args.bodyText != null ->
            return CommandResult("use only one of --body-file or --body-text", exitCode = 2)
        args.bodyFile != null -> runCatching { Files.readAllBytes(Path.of(args.bodyFile)) }.getOrElse {
            return CommandResult("could not read body file ${args.bodyFile}: ${it.message}", exitCode = 1)
        }
        args.bodyText != null -> args.bodyText.toByteArray(Charsets.UTF_8)
        else -> null
    }
    val headers = if (args.headers.isEmpty()) {
        null
    } else {
        when (val parsed = parseHeaders(args.headers)) {
            is ParsedHeaders.Rejected -> return parsed.result
            is ParsedHeaders.Parsed -> parsed.headers
        }
    }
    var editedRequest: HttpRequest? = null
    var editedResponse: HttpResponse? = null
    when (hold.phase) {
        BreakpointPhase.BREAKPOINT_PHASE_REQUEST -> {
            if (args.statusCode != null) {
                return CommandResult("--status applies to a response-phase hold", exitCode = 2)
            }
            val original = hold.request
                ?: return CommandResult("request-phase hold carries no request", exitCode = 1)
            if (args.method != null || args.editedUrl != null || headers != null || body != null) {
                editedRequest = original.withEdits(args.method, args.editedUrl, headers, body)
            }
        }
        BreakpointPhase.BREAKPOINT_PHASE_RESPONSE -> {
            if (args.method != null || args.editedUrl != null) {
                return CommandResult("--method and --set-url apply to a request-phase hold", exitCode = 2)
            }
            if (args.statusCode != null && args.statusCode !in 100..599) {
                return CommandResult("status must be between 100 and 599", exitCode = 2)
            }
            if (args.statusCode != null || headers != null || body != null) {
                editedResponse = (hold.response ?: HttpResponse(code = 200))
                    .withEdits(args.statusCode, headers, body)
            }
        }
    }
    if (!host.resumeHold(id, editedRequest, editedResponse)) {
        return CommandResult("hold no longer exists: $id", exitCode = 1)
    }
    val edited = editedRequest != null || editedResponse != null
    return CommandResult("resumed $id" + if (edited) " (edited)" else "")
}

/**
 * The body tail both `get_*` verbs print. Fetched here rather than arriving with the rule (ADR-0086), so
 * a listing stays cheap and only the fixture actually being read crosses the wire.
 */
private suspend fun ruleBody(
    host: DaemonClient,
    family: String,
    id: String,
    bodySize: Int,
    limit: Int,
): String {
    if (bodySize == 0 || limit <= 0) return ""
    val bytes = host.readRuleBody(family, id, length = limit)
    return buildString {
        appendLine("--- body ($bodySize bytes) ---")
        append(String(bytes, Charsets.UTF_8))
        if (bodySize > bytes.size) append("\n… truncated")
    }
}

private fun phaseLabel(onRequest: Boolean, onResponse: Boolean): String =
    listOfNotNull("request".takeIf { onRequest }, "response".takeIf { onResponse }).joinToString("+")

private fun PausedExchange.phaseName(): String =
    if (phase == BreakpointPhase.BREAKPOINT_PHASE_REQUEST) "request" else "response"

/**
 * A hold as one line, with the held phase's body under it when [bodyChars] allows. Those bytes are inline,
 * unlike a captured exchange's: a hold is still in flight and never went through the body store.
 */
private fun holdSummary(hold: PausedExchange, bodyChars: Int): String = buildString {
    append("${hold.correlationId}\t${hold.phaseName()}\t${hold.request?.method}\t${hold.request?.url}")
    val body = if (hold.phaseName() == "request") hold.request?.body else hold.response?.body
    if (bodyChars > 0 && body != null && body.size > 0) {
        appendLine()
        append(String(body.toByteArray(), 0, minOf(body.size, bodyChars), Charsets.UTF_8))
        if (body.size > bodyChars) append("\n… truncated")
    }
}

/**
 * The group commands for all three panels (ADR-0081). One set with `--family` rather than three
 * near-identical trios, since the model behind them is the same and only the panel differs.
 */
private suspend fun setRuleGroup(host: DaemonClient, args: ParsedArgs): CommandResult {
    val family = args.family?.takeIf { it in RULE_FAMILIES }
        ?: return CommandResult(
            "set_rule_group requires --family ${RULE_FAMILIES.joinToString("|")}",
            exitCode = 2,
        )
    val id = args.groupId ?: return CommandResult("set_rule_group requires --group-id", exitCode = 2)
    // Read first so setting one field does not reset the other to its default.
    val existing = host.listRuleGroups(family).firstOrNull { it.id == id }
    val group = DaemonRuleGroup(
        id = id,
        name = args.name ?: existing?.name.orEmpty(),
        enabled = args.flag ?: existing?.enabled ?: true,
    )
    host.setRuleGroup(family, group)
    return CommandResult("$family group $id set (${if (group.enabled) "on" else "off"})")
}

private suspend fun removeRuleGroup(host: DaemonClient, args: ParsedArgs): CommandResult {
    val family = args.family?.takeIf { it in RULE_FAMILIES }
        ?: return CommandResult(
            "remove_rule_group requires --family ${RULE_FAMILIES.joinToString("|")}",
            exitCode = 2,
        )
    val id = args.groupId ?: return CommandResult("remove_rule_group requires --group-id", exitCode = 2)
    if (!host.removeRuleGroup(family, id, args.withRules)) {
        return CommandResult("$family group not found: $id", exitCode = 1)
    }
    val fate = if (args.withRules) "with its rules" else "rules kept"
    return CommandResult("removed $family group $id ($fate)")
}

private suspend fun listRuleGroups(host: DaemonClient, args: ParsedArgs): CommandResult {
    val family = args.family?.takeIf { it in RULE_FAMILIES }
        ?: return CommandResult(
            "list_rule_groups requires --family ${RULE_FAMILIES.joinToString("|")}",
            exitCode = 2,
        )
    val groups = host.listRuleGroups(family)
    return CommandResult(
        if (groups.isEmpty()) {
            "(no $family groups)"
        } else {
            groups.joinToString("\n") { "${it.id}\t${if (it.enabled) "on" else "off"}\t${it.name}" }
        },
    )
}

/**
 * Writes a seed into the daemon's library (ADR-0067). Deliberately not armed by this: the library is what
 * a run configures up front, `fill_seeds` is the moment it decides the sequence starts.
 */
private suspend fun setSeed(host: DaemonClient, args: ParsedArgs): CommandResult {
    val canned = when (val parsed = parseCannedResponse("set_seed", "Seed", args)) {
        is CannedResponse.Rejected -> return parsed.result
        is CannedResponse.Parsed -> parsed
    }
    host.upsertSeed(
        HostSeed(
            id = canned.id,
            enabled = args.flag ?: true,
            urlPattern = canned.urlPattern,
            method = args.method.orEmpty(),
            statusCode = canned.statusCode,
            headers = canned.headers,
            body = canned.body,
        ),
        args.groupId,
    )
    return CommandResult("seed ${canned.id} set (${canned.body.size} bytes)")
}

/**
 * The id / pattern / status / headers / body a Map Local rule and a seed are both authored from — they
 * are the same canned response, differing only in who serves it.
 */
private sealed interface CannedResponse {
    data class Parsed(
        val id: String,
        val urlPattern: String,
        val statusCode: Int,
        val headers: List<Header>,
        val body: ByteArray,
    ) : CannedResponse

    data class Rejected(val result: CommandResult) : CannedResponse
}

private fun parseCannedResponse(verb: String, label: String, args: ParsedArgs): CannedResponse {
    fun reject(message: String, exitCode: Int = 2) = CannedResponse.Rejected(CommandResult(message, exitCode))
    val id = args.id ?: return reject("$verb requires --id")
    val pattern = args.urlPattern ?: return reject("$verb requires --url-pattern")
    if (id.isBlank()) return reject("$label id must not be blank")
    if (pattern.isBlank()) return reject("$label URL pattern must not be blank")
    val status = args.statusCode ?: 200
    if (status !in 100..599) return reject("$label status must be between 100 and 599")
    val body = when {
        args.bodyFile != null && args.bodyText != null -> return reject("use only one of --body-file or --body-text")
        args.bodyFile != null -> runCatching { Files.readAllBytes(Path.of(args.bodyFile)) }.getOrElse {
            return reject("could not read body file ${args.bodyFile}: ${it.message}", exitCode = 1)
        }
        else -> args.bodyText.orEmpty().toByteArray(Charsets.UTF_8)
    }
    val headers = when (val parsed = parseHeaders(args.headers)) {
        is ParsedHeaders.Rejected -> return CannedResponse.Rejected(parsed.result)
        is ParsedHeaders.Parsed -> parsed.headers.toMutableList()
    }
    if (headers.none { it.name.equals("Content-Type", ignoreCase = true) } && args.bodyFile != null) {
        contentTypeFor(args.bodyFile)?.let { headers += Header(name = "Content-Type", value_ = it) }
    }
    return CannedResponse.Parsed(
        id = id,
        urlPattern = pattern,
        statusCode = status,
        headers = headers,
        body = body,
    )
}

private sealed interface ParsedHeaders {
    data class Parsed(val headers: List<Header>) : ParsedHeaders

    data class Rejected(val result: CommandResult) : ParsedHeaders
}

/** `Name: value` pairs as typed on the command line — authoring a rule and editing a hold both take them. */
private fun parseHeaders(raw: List<String>): ParsedHeaders {
    val headers = mutableListOf<Header>()
    raw.forEach { line ->
        val separator = line.indexOf(':')
        val name = line.substring(0, separator.coerceAtLeast(0)).trim()
        if (separator <= 0 || name.isEmpty()) {
            return ParsedHeaders.Rejected(
                CommandResult("invalid header '$line'; expected 'Name: value'", exitCode = 2),
            )
        }
        headers += Header(name = name, value_ = line.substring(separator + 1).trim())
    }
    return ParsedHeaders.Parsed(headers)
}

private fun contentTypeFor(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "xml" -> "application/xml"
    "txt" -> "text/plain"
    "js" -> "application/javascript"
    "css" -> "text/css"
    "csv" -> "text/csv"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "svg" -> "image/svg+xml"
    "webp" -> "image/webp"
    else -> null
}

internal enum class Command(val verb: String) {
    ListExchanges("list_exchanges"),
    SearchTraffic("search_traffic"),
    GetExchange("get_exchange"),
    SetMapLocal("set_map_local"),
    RemoveMapLocal("remove_map_local"),
    ListMapLocal("list_map_local"),
    GetMapLocal("get_map_local"),
    SetMapLocalEnabled("set_map_local_enabled"),
    SetRuleGroup("set_rule_group"),
    RemoveRuleGroup("remove_rule_group"),
    ListRuleGroups("list_rule_groups"),
    SetBreakpoint("set_breakpoint"),
    RemoveBreakpoint("remove_breakpoint"),
    ListBreakpoints("list_breakpoints"),
    SetBreakpointsEnabled("set_breakpoints_enabled"),
    SetSeed("set_seed"),
    RemoveSeed("remove_seed"),
    ListSeeds("list_seeds"),
    GetSeed("get_seed"),
    SetSeedsEnabled("set_seeds_enabled"),
    FillSeeds("fill_seeds"),
    ClearSeedQueue("clear_seed_queue"),
    SetCaptureFilter("set_capture_filter"),
    SetCaptureFilterEnabled("set_capture_filter_enabled"),
    ClearCaptureFilter("clear_capture_filter"),
    ListCaptureFilter("list_capture_filter"),
    ListDevices("list_devices"),
    ListHolds("list_holds"),
    ResumeHold("resume_hold"),
    AbortHold("abort_hold"),
    ClearCapture("clear_capture"),
    SetCapturing("set_capturing"),
    SetMaxRetained("set_max_retained"),
    WaitExchange("wait_exchange"),
    WaitHold("wait_hold"),
    WaitDevice("wait_device"),
    Rebind("rebind"),
    SetUsbPort("set_usb_port"),
    SetProxy("set_proxy"),
    ProxyStatus("proxy_status"),
    SetProxyLan("set_proxy_lan"),
    SetSystemProxy("set_system_proxy"),
    SetProxyDecrypt("set_proxy_decrypt"),
    ProxyCa("proxy_ca"),
    RotateProxyCa("rotate_proxy_ca"),
    RemoveProxyCa("remove_proxy_ca"),
    SetMcpAccess("set_mcp_access"),
    SetMcpRedaction("set_mcp_redaction"),
    SetRequirePairing("set_require_pairing"),
    ListPaired("list_paired"),
    ForgetDevice("forget_device"),
    ForgetAllDevices("forget_all_devices"),
    ResetIdentity("reset_identity"),
    ListBookmarks("list_bookmarks"),
    SetBookmark("set_bookmark"),
    Status("status"),
    Stop("stop"),
}

internal data class ParsedArgs(
    val command: String,
    val port: Int = WailoEngine.DEFAULT_PORT,
    val portSpecified: Boolean = false,
    val id: String? = null,
    val urlContains: String? = null,
    val urlPattern: String? = null,
    val method: String? = null,
    val statusCode: Int? = null,
    val appId: String? = null,
    val limit: Int = 50,
    val bodyChars: Int = 4_096,
    val bodyCharsSpecified: Boolean = false,
    val timeoutSeconds: Long = 30,
    val flag: Boolean? = null,
    val headers: List<String> = emptyList(),
    val allowPatterns: List<String> = emptyList(),
    val blockPatterns: List<String> = emptyList(),
    // Null means "derive it from whether the list has patterns", which is what every existing script gets.
    val allowlistEnabled: Boolean? = null,
    val blocklistEnabled: Boolean? = null,
    val hosts: List<String> = emptyList(),
    val bodyFile: String? = null,
    val bodyText: String? = null,
    val out: String? = null,
    val keep: Boolean = false,
    val family: String? = null,
    val groupId: String? = null,
    val name: String? = null,
    val withRules: Boolean = false,
    val deviceName: String? = null,
    val phase: String? = null,
    val onRequest: Boolean? = null,
    val onResponse: Boolean? = null,
    // The URL an edited hold continues to. Separate from --url, which every search command already reads
    // as "contains this".
    val editedUrl: String? = null,
    val count: Int? = null,
)

internal fun parseArgs(args: Array<String>): ParsedArgs? {
    if (args.isEmpty()) return null
    val command = args[0]
    var port = WailoEngine.DEFAULT_PORT
    var portSpecified = false
    var id: String? = null
    var urlContains: String? = null
    var urlPattern: String? = null
    var method: String? = null
    var statusCode: Int? = null
    var appId: String? = null
    var limit = 50
    var bodyChars = 4_096
    var bodyCharsSpecified = false
    var timeoutSeconds = 30L
    var flag: Boolean? = null
    val headers = mutableListOf<String>()
    val allowPatterns = mutableListOf<String>()
    val blockPatterns = mutableListOf<String>()
    var allowlistEnabled: Boolean? = null
    var blocklistEnabled: Boolean? = null
    val hosts = mutableListOf<String>()
    var bodyFile: String? = null
    var bodyText: String? = null
    var out: String? = null
    var keep = false
    var family: String? = null
    var groupId: String? = null
    var name: String? = null
    var withRules = false
    var deviceName: String? = null
    var phase: String? = null
    var onRequest: Boolean? = null
    var onResponse: Boolean? = null
    var editedUrl: String? = null
    var count: Int? = null
    var i = 1
    while (i < args.size) {
        when (val a = args[i]) {
            "--port" -> {
                port = args.getOrNull(++i)?.toIntOrNull() ?: return null
                portSpecified = true
            }
            "--id" -> id = args.getOrNull(++i) ?: return null
            "--url-contains", "--url" -> urlContains = args.getOrNull(++i) ?: return null
            "--url-pattern" -> urlPattern = args.getOrNull(++i) ?: return null
            "--method" -> method = args.getOrNull(++i) ?: return null
            "--status" -> statusCode = args.getOrNull(++i)?.toIntOrNull() ?: return null
            "--app" -> appId = args.getOrNull(++i) ?: return null
            "--limit" -> limit = args.getOrNull(++i)?.toIntOrNull() ?: return null
            "--body-chars" -> {
                bodyChars = args.getOrNull(++i)?.toIntOrNull() ?: return null
                bodyCharsSpecified = true
            }
            "--timeout" -> timeoutSeconds = args.getOrNull(++i)?.toLongOrNull() ?: return null
            "--header" -> headers += args.getOrNull(++i) ?: return null
            "--allow" -> allowPatterns += args.getOrNull(++i) ?: return null
            "--block" -> blockPatterns += args.getOrNull(++i) ?: return null
            "--allow-on" -> allowlistEnabled = true
            "--allow-off" -> allowlistEnabled = false
            "--block-on" -> blocklistEnabled = true
            "--block-off" -> blocklistEnabled = false
            "--host" -> hosts += args.getOrNull(++i) ?: return null
            "--body-file" -> bodyFile = args.getOrNull(++i) ?: return null
            "--body-text" -> bodyText = args.getOrNull(++i) ?: return null
            "--out" -> out = args.getOrNull(++i) ?: return null
            "--on" -> flag = true
            "--off" -> flag = false
            "--keep" -> keep = true
            "--family" -> family = args.getOrNull(++i) ?: return null
            "--group-id" -> groupId = args.getOrNull(++i) ?: return null
            "--name" -> name = args.getOrNull(++i) ?: return null
            "--with-rules" -> withRules = true
            "--device" -> deviceName = args.getOrNull(++i) ?: return null
            "--phase" -> phase = args.getOrNull(++i) ?: return null
            "--on-request" -> onRequest = true
            "--on-response" -> onResponse = true
            "--set-url" -> editedUrl = args.getOrNull(++i) ?: return null
            "--count" -> count = args.getOrNull(++i)?.toIntOrNull() ?: return null
            else -> return null
        }
        i += 1
    }
    if (port !in WailoEngine.PORT_RANGE) return null
    if (limit !in 1..1_000 || bodyChars !in 0..1_000_000 || timeoutSeconds !in 0..86_400) return null
    return ParsedArgs(
        command = command,
        port = port,
        portSpecified = portSpecified,
        id = id,
        urlContains = urlContains,
        urlPattern = urlPattern,
        method = method,
        statusCode = statusCode,
        appId = appId,
        limit = limit,
        bodyChars = bodyChars,
        bodyCharsSpecified = bodyCharsSpecified,
        timeoutSeconds = timeoutSeconds,
        flag = flag,
        headers = headers,
        allowPatterns = allowPatterns,
        blockPatterns = blockPatterns,
        allowlistEnabled = allowlistEnabled,
        blocklistEnabled = blocklistEnabled,
        hosts = hosts,
        bodyFile = bodyFile,
        bodyText = bodyText,
        out = out,
        keep = keep,
        family = family,
        groupId = groupId,
        name = name,
        withRules = withRules,
        deviceName = deviceName,
        phase = phase,
        onRequest = onRequest,
        onResponse = onResponse,
        editedUrl = editedUrl,
        count = count,
    )
}

private fun printUsage() {
    println(
        """
        wailo-cli — frontend over the persistent shared Wailo daemon

        wailo-cli serve [--port N] [--keep]
        wailo-cli status
        wailo-cli stop
        wailo-cli list_exchanges [--limit N]
        wailo-cli search_traffic [--url S] [--url-pattern GLOB] [--method M] [--status C] [--app ID]
        wailo-cli get_exchange --id ID
        wailo-cli set_map_local --id ID --url-pattern GLOB [--name TEXT] [--method M] [--status C]
                    [--header "Name: value"]... [--body-file PATH|--body-text TEXT] [--off]
                    [--group-id ID]
        wailo-cli remove_map_local --id ID
        wailo-cli list_map_local
        wailo-cli get_map_local --id ID [--body-chars N]
        wailo-cli set_map_local_enabled --on|--off
        wailo-cli set_rule_group --family map_local|breakpoints|seeds --group-id ID
                    [--name TEXT] [--on|--off]
        wailo-cli remove_rule_group --family F --group-id ID [--with-rules]
        wailo-cli list_rule_groups --family F
        wailo-cli set_breakpoint --id ID --url-pattern GLOB [--method M]
                    [--on-request] [--on-response] [--off] [--group-id ID]
        wailo-cli remove_breakpoint --id ID
        wailo-cli list_breakpoints
        wailo-cli set_breakpoints_enabled --on|--off
        wailo-cli set_seed --id ID --url-pattern GLOB [--method M] [--status C]
                    [--header "Name: value"]... [--body-file PATH|--body-text TEXT] [--off]
                    [--group-id ID]
        wailo-cli remove_seed --id ID
        wailo-cli list_seeds
        wailo-cli get_seed --id ID [--body-chars N]
        wailo-cli set_seeds_enabled --on|--off
        wailo-cli fill_seeds
        wailo-cli clear_seed_queue
        wailo-cli set_capture_filter [--allow HOST_PATTERN]... [--block HOST_PATTERN]...
                    [--allow-on|--allow-off] [--block-on|--block-off]
        wailo-cli set_capture_filter_enabled --on|--off
        wailo-cli clear_capture_filter
        wailo-cli list_capture_filter
        wailo-cli list_devices
        wailo-cli list_holds [--body-chars N]
        wailo-cli resume_hold --id CORRELATION_ID
                    [--method M] [--set-url URL] [--status C] [--header "Name: value"]...
                    [--body-file PATH|--body-text TEXT]
        wailo-cli abort_hold --id CORRELATION_ID
        wailo-cli clear_capture
        wailo-cli set_capturing --on|--off
        wailo-cli set_max_retained --count N
        wailo-cli wait_exchange [--url S] [--url-pattern GLOB] [--method M] [--status C] [--timeout SEC]
        wailo-cli wait_hold [--url S] [--url-pattern GLOB] [--method M] [--phase request|response]
                    [--timeout SEC] [--body-chars N]
        wailo-cli wait_device [--app ID] [--device NAME] [--timeout SEC]
        wailo-cli rebind --port N
        wailo-cli set_usb_port --port N
        wailo-cli set_proxy --on|--off [--port N]
        wailo-cli proxy_status
        wailo-cli set_proxy_lan --on|--off
        wailo-cli set_system_proxy --on|--off
        wailo-cli set_proxy_decrypt --host PATTERN... | --off
        wailo-cli proxy_ca [--out PATH]
        wailo-cli rotate_proxy_ca
        wailo-cli remove_proxy_ca
        wailo-cli set_mcp_access --on|--off
        wailo-cli set_mcp_redaction --on|--off
        wailo-cli set_require_pairing --on|--off
        wailo-cli list_paired
        wailo-cli forget_device --id DEVICE_ID
        wailo-cli forget_all_devices
        wailo-cli reset_identity
        wailo-cli list_bookmarks
        wailo-cli set_bookmark --host HOST [--off]

        Every invocation auto-starts and attaches to the same daemon. It stays up while anything refers
        to it — an open Studio, an MCP session, a connected app — and exits on its own once nothing has
        for a while. `serve --keep` holds it open until Ctrl-C; `wailo-cli stop` ends it immediately.

        Rules are organised into groups, which Studio shows as collapsible sections and which can be
        switched off as a unit — no rule in a disabled group matches, and each keeps its own state for
        when the group comes back. Create a group with set_rule_group before filing rules into it with
        --group-id; removing one keeps its rules unless you pass --with-rules.

        Seeds are canned responses that answer held exchanges in order (ADR-0041). set_seed builds the
        library; fill_seeds arms every enabled one and sweeps the holds already waiting, and each hold it
        answers spends a seed. list_seeds shows which are still armed.

        A breakpoint pauses a matching exchange so something can decide what continues. Naming neither
        phase holds the response; --on-request holds it before it is sent instead. wait_hold blocks until
        one appears and prints it, and resume_hold lets it go — optionally rewriting it, with the flags
        that apply to the phase it stopped in: --method and --set-url before the request goes out, --status
        after it came back, headers and body either way. Anything else is refused rather than ignored, so a
        script cannot believe it changed something it did not. abort_hold fails the exchange instead.

        The Capture Filter has three switches, not one: set_capture_filter_enabled gates the whole thing,
        and each list applies on its own. Patterns replace the list they are given for, and a list with any
        is armed — so passing neither patterns nor a switch for a list empties it. Pass only --allow-off or
        --block-off to disarm a list while it keeps what is in it.

        set_require_pairing decides whether an unknown device may connect at all. Approving a specific one
        happens in Studio, where the code is on screen next to the device asking; here you can require
        pairing, list what is already trusted, forget one or all of them, and reset this machine's identity
        so every device has to pair again.

        set_proxy starts the bundled HTTP proxy so traffic from anything that can reach this machine — a
        browser, a CLI, a simulator, a phone — is captured without the SDK. It is off until you start it,
        and while it runs it keeps the daemon alive. It binds every interface by default, which is also
        what makes it an open relay for that network while it runs: set_proxy_lan --off keeps it to this
        machine, and stopping the proxy ends the exposure either way.

        set_system_proxy points this Mac's own network settings at Wailo and forwards through whatever
        proxy was already configured, so a machine behind one keeps working. The settings are snapshotted
        first and put back when the proxy stops, including on the way out.

        HTTPS starts locked: a CONNECT is tunnelled without being read (ADR-0071). Decrypting needs two
        separate acts. proxy_ca mints the local root and prints it — or writes it with --out — for you to
        trust in the OS; set_proxy_decrypt then names the hosts to unlock, and replaces the list each
        time, so --off relocks everything. remove_proxy_ca forgets the root here, but you still have to
        untrust it yourself. A phone cannot read a file here, so browsing to the proxy's own address
        serves it that same root plus the steps for its platform — it never creates one.

        set_mcp_access gates whether AI tools reach this capture at all; set_mcp_redaction decides
        whether what they read has its credentials stripped. Both are also in Studio's Settings panel.
        """.trimIndent(),
    )
}
