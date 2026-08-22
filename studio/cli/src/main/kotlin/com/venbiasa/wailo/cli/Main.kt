package com.venbiasa.wailo.cli

import com.venbiasa.wailo.daemon.DaemonClient
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.HostSeed
import com.venbiasa.wailo.host.urlPatternMatches
import com.venbiasa.wailo.host.summarizeExchanges
import com.venbiasa.wailo.protocol.Header
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

private val BLOCKING_COMMANDS = setOf("wait_exchange", "wait-exchange", "wait_hold", "wait-hold")

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
            CommandResult(
                if (rules.isEmpty()) {
                    "(no Map Local rules)"
                } else {
                    rules.joinToString("\n") { rule ->
                        val methods = rule.methods.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "*"
                        "${rule.id}\t${if (rule.enabled) "on" else "off"}\t$methods\t${rule.statusCode}\t${rule.bodySize}B\t${rule.urlPattern}"
                    }
                },
            )
        }
        "set_map_local_enabled", "set-map-local-enabled" -> {
            val enabled = args.flag
                ?: return CommandResult("set_map_local_enabled requires --on or --off", exitCode = 2)
            host.setMapLocalEnabled(enabled)
            CommandResult("map_local_enabled=$enabled")
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
            host.updateCaptureFilter(
                allowlistEnabled = args.allowPatterns.isNotEmpty(),
                allowPatterns = args.allowPatterns,
                blocklistEnabled = args.blockPatterns.isNotEmpty(),
                blockPatterns = args.blockPatterns,
            )
            CommandResult(
                "capture_filter allow=${args.allowPatterns.size} block=${args.blockPatterns.size}",
            )
        }
        "clear_capture_filter", "clear-capture-filter" -> {
            host.updateCaptureFilter(false, emptyList(), false, emptyList())
            CommandResult("capture_filter cleared")
        }
        "list_capture_filter", "list-capture-filter" -> {
            val filter = host.captureFilter.value
            CommandResult(
                buildString {
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
                    holds.joinToString("\n") {
                        "${it.correlationId}\t${it.phase.name}\t${it.request?.method}\t${it.request?.url}"
                    }
                },
            )
        }
        "resume_hold", "resume-hold" -> {
            val id = args.id ?: return CommandResult("resume_hold requires --id (correlation id)", exitCode = 2)
            if (host.resumeHold(id)) {
                CommandResult("resumed $id")
            } else {
                CommandResult("hold not found: $id", exitCode = 1)
            }
        }
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
            val hold = host.waitForHold(timeout = args.timeoutSeconds.seconds)
                ?: return CommandResult("timeout waiting for hold", exitCode = 1)
            CommandResult("${hold.correlationId}\t${hold.request?.method}\t${hold.request?.url}")
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
                    "proxy=on address=${status.reachableAddress}"
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
                "mcp_access=${host.mcpAccess.value} mcp_redaction=${host.mcpRedactSecrets.value}",
        )
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
            enabled = args.flag ?: true,
            urlPattern = canned.urlPattern,
            methods = args.method?.let(::listOf).orEmpty(),
            statusCode = canned.statusCode,
            headers = canned.headers,
            body = canned.body,
        ),
    )
    return CommandResult("Map Local rule ${canned.id} set (${canned.body.size} bytes)")
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
    val headers = mutableListOf<Header>()
    args.headers.forEach { raw ->
        val separator = raw.indexOf(':')
        val name = raw.substring(0, separator.coerceAtLeast(0)).trim()
        if (separator <= 0 || name.isEmpty()) {
            return reject("invalid header '$raw'; expected 'Name: value'")
        }
        headers += Header(name = name, value_ = raw.substring(separator + 1).trim())
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
    SetMapLocalEnabled("set_map_local_enabled"),
    SetSeed("set_seed"),
    RemoveSeed("remove_seed"),
    ListSeeds("list_seeds"),
    SetSeedsEnabled("set_seeds_enabled"),
    FillSeeds("fill_seeds"),
    ClearSeedQueue("clear_seed_queue"),
    SetCaptureFilter("set_capture_filter"),
    ClearCaptureFilter("clear_capture_filter"),
    ListCaptureFilter("list_capture_filter"),
    ListDevices("list_devices"),
    ListHolds("list_holds"),
    ResumeHold("resume_hold"),
    AbortHold("abort_hold"),
    ClearCapture("clear_capture"),
    SetCapturing("set_capturing"),
    WaitExchange("wait_exchange"),
    WaitHold("wait_hold"),
    Rebind("rebind"),
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
    val timeoutSeconds: Long = 30,
    val flag: Boolean? = null,
    val headers: List<String> = emptyList(),
    val allowPatterns: List<String> = emptyList(),
    val blockPatterns: List<String> = emptyList(),
    val hosts: List<String> = emptyList(),
    val bodyFile: String? = null,
    val bodyText: String? = null,
    val out: String? = null,
    val keep: Boolean = false,
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
    var timeoutSeconds = 30L
    var flag: Boolean? = null
    val headers = mutableListOf<String>()
    val allowPatterns = mutableListOf<String>()
    val blockPatterns = mutableListOf<String>()
    val hosts = mutableListOf<String>()
    var bodyFile: String? = null
    var bodyText: String? = null
    var out: String? = null
    var keep = false
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
            "--body-chars" -> bodyChars = args.getOrNull(++i)?.toIntOrNull() ?: return null
            "--timeout" -> timeoutSeconds = args.getOrNull(++i)?.toLongOrNull() ?: return null
            "--header" -> headers += args.getOrNull(++i) ?: return null
            "--allow" -> allowPatterns += args.getOrNull(++i) ?: return null
            "--block" -> blockPatterns += args.getOrNull(++i) ?: return null
            "--host" -> hosts += args.getOrNull(++i) ?: return null
            "--body-file" -> bodyFile = args.getOrNull(++i) ?: return null
            "--body-text" -> bodyText = args.getOrNull(++i) ?: return null
            "--out" -> out = args.getOrNull(++i) ?: return null
            "--on" -> flag = true
            "--off" -> flag = false
            "--keep" -> keep = true
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
        timeoutSeconds = timeoutSeconds,
        flag = flag,
        headers = headers,
        allowPatterns = allowPatterns,
        blockPatterns = blockPatterns,
        hosts = hosts,
        bodyFile = bodyFile,
        bodyText = bodyText,
        out = out,
        keep = keep,
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
        wailo-cli set_map_local --id ID --url-pattern GLOB [--method M] [--status C]
                    [--header "Name: value"]... [--body-file PATH|--body-text TEXT] [--off]
        wailo-cli remove_map_local --id ID
        wailo-cli list_map_local
        wailo-cli set_map_local_enabled --on|--off
        wailo-cli set_seed --id ID --url-pattern GLOB [--method M] [--status C]
                    [--header "Name: value"]... [--body-file PATH|--body-text TEXT] [--off]
        wailo-cli remove_seed --id ID
        wailo-cli list_seeds
        wailo-cli set_seeds_enabled --on|--off
        wailo-cli fill_seeds
        wailo-cli clear_seed_queue
        wailo-cli set_capture_filter [--allow HOST_PATTERN]... [--block HOST_PATTERN]...
        wailo-cli clear_capture_filter
        wailo-cli list_capture_filter
        wailo-cli list_devices
        wailo-cli list_holds
        wailo-cli resume_hold --id CORRELATION_ID
        wailo-cli abort_hold --id CORRELATION_ID
        wailo-cli clear_capture
        wailo-cli set_capturing --on|--off
        wailo-cli wait_exchange [--url S] [--url-pattern GLOB] [--method M] [--status C] [--timeout SEC]
        wailo-cli wait_hold [--timeout SEC]
        wailo-cli rebind --port N
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

        Every invocation auto-starts and attaches to the same daemon. It stays up while anything refers
        to it — an open Studio, an MCP session, a connected app — and exits on its own once nothing has
        for a while. `serve --keep` holds it open until Ctrl-C; `wailo-cli stop` ends it immediately.

        Seeds are canned responses that answer held exchanges in order (ADR-0041). set_seed builds the
        library; fill_seeds arms every enabled one and sweeps the holds already waiting, and each hold it
        answers spends a seed. list_seeds shows which are still armed.

        set_proxy starts the bundled HTTP proxy so traffic from anything on this machine — a browser, a
        CLI, a simulator — is captured without the SDK. It listens on loopback, off by default, and
        while it runs it keeps the daemon alive. set_proxy_lan binds it to every interface so a phone or
        another machine can use it, which also makes it an open relay for anything on that network — so
        it is opt-in, and worth turning off when you are done.

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
