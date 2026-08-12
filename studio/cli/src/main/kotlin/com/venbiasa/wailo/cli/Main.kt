package com.venbiasa.wailo.cli

import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.summarizeExchanges
import com.venbiasa.wailo.protocol.Header
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/**
 * Headless CLI frontend over [HeadlessHost] (ADR-0055/0056). Same command surface the future MCP server
 * will expose as tools — Tier 1 traffic/session ops plus breakpoint hold resume/abort.
 *
 * Usage:
 *   wailo-cli serve [--port N]
 *   wailo-cli <command> …          # attaches to the long-running `serve` process
 */
fun main(args: Array<String>) {
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
        else -> runRemote(args, parsed)
    }
}

private fun runServe(parsed: ParsedArgs) {
    val host = try {
        HeadlessHost.start(port = parsed.port)
    } catch (failure: IllegalStateException) {
        System.err.println(failure.message)
        exitProcess(1)
    }
    val control = try {
        ControlServer(host, parsed.controlPort).also(ControlServer::start)
    } catch (failure: Exception) {
        host.stop()
        System.err.println("Could not start Wailo control server on 127.0.0.1:${parsed.controlPort}: ${failure.message}")
        exitProcess(1)
    }
    val stopped = CountDownLatch(1)
    val shutdown = Thread {
        control.close()
        host.stop()
        stopped.countDown()
    }
    Runtime.getRuntime().addShutdownHook(shutdown)
    println("wailo-cli capture listening on ${parsed.port}; control on 127.0.0.1:${parsed.controlPort}")
    try {
        stopped.await()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        control.close()
        host.stop()
    }
}

private fun runRemote(rawArgs: Array<String>, parsed: ParsedArgs) {
    val result = try {
        ControlClient(parsed.controlPort).execute(rawArgs, parsed.timeoutSeconds)
    } catch (failure: Exception) {
        CommandResult(
            "No wailo-cli serve process is reachable on 127.0.0.1:${parsed.controlPort}: ${failure.message}",
            exitCode = 2,
        )
    }
    if (result.message.isNotEmpty()) println(result.message)
    exitProcess(result.exitCode)
}

internal data class CommandResult(val message: String, val exitCode: Int = 0)

internal suspend fun dispatch(host: HeadlessHost, args: ParsedArgs): CommandResult {
    return when (args.command) {
        "list_exchanges", "list-exchanges" ->
            CommandResult(host.summarizeExchanges(args.limit))
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
                    val body = res?.body?.utf8() ?: req?.body?.utf8()
                    if (!body.isNullOrEmpty()) {
                        appendLine("--- body ---")
                        append(body.take(args.bodyChars))
                        if (body.length > args.bodyChars) append("\n… truncated")
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
            val filter = host.engine.captureFilter.value
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
        "list_holds", "list-holds" -> CommandResult(host.summarizeHolds())
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
            val row = host.waitForExchange(
                timeout = args.timeoutSeconds.seconds,
                urlContains = args.urlContains,
                urlPattern = args.urlPattern,
                method = args.method,
                statusCode = args.statusCode,
            ) ?: return CommandResult("timeout waiting for exchange", exitCode = 1)
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
        else -> CommandResult("unknown command: ${args.command}", exitCode = 2)
    }
}

private suspend fun setMapLocal(host: HeadlessHost, args: ParsedArgs): CommandResult {
    val id = args.id ?: return CommandResult("set_map_local requires --id", exitCode = 2)
    val pattern = args.urlPattern
        ?: return CommandResult("set_map_local requires --url-pattern", exitCode = 2)
    if (id.isBlank()) return CommandResult("Map Local id must not be blank", exitCode = 2)
    if (pattern.isBlank()) return CommandResult("Map Local URL pattern must not be blank", exitCode = 2)
    val status = args.statusCode ?: 200
    if (status !in 100..599) {
        return CommandResult("Map Local status must be between 100 and 599", exitCode = 2)
    }
    val body = when {
        args.bodyFile != null && args.bodyText != null ->
            return CommandResult("use only one of --body-file or --body-text", exitCode = 2)
        args.bodyFile != null -> runCatching { Files.readAllBytes(Path.of(args.bodyFile)) }.getOrElse {
            return CommandResult("could not read body file ${args.bodyFile}: ${it.message}", exitCode = 1)
        }
        else -> args.bodyText.orEmpty().toByteArray(Charsets.UTF_8)
    }
    val headers = mutableListOf<Header>()
    args.headers.forEach { raw ->
        val separator = raw.indexOf(':')
        val name = raw.substring(0, separator.coerceAtLeast(0)).trim()
        if (separator <= 0 || name.isEmpty()) {
            return CommandResult("invalid header '$raw'; expected 'Name: value'", exitCode = 2)
        }
        headers += Header(name = name, value_ = raw.substring(separator + 1).trim())
    }
    if (headers.none { it.name.equals("Content-Type", ignoreCase = true) } && args.bodyFile != null) {
        contentTypeFor(args.bodyFile)?.let { headers += Header(name = "Content-Type", value_ = it) }
    }
    host.upsertMapLocalRule(
        HostMapLocalRule(
            id = id,
            enabled = args.flag ?: true,
            urlPattern = pattern,
            methods = args.method?.let(::listOf).orEmpty(),
            statusCode = status,
            headers = headers,
            body = body,
        ),
    )
    return CommandResult("Map Local rule $id set (${body.size} bytes)")
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
}

internal data class ParsedArgs(
    val command: String,
    val port: Int = WailoEngine.DEFAULT_PORT,
    val controlPort: Int = DEFAULT_CONTROL_PORT,
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
    val bodyFile: String? = null,
    val bodyText: String? = null,
)

internal fun parseArgs(args: Array<String>): ParsedArgs? {
    if (args.isEmpty()) return null
    val command = args[0]
    var port = WailoEngine.DEFAULT_PORT
    var controlPort = DEFAULT_CONTROL_PORT
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
    var bodyFile: String? = null
    var bodyText: String? = null
    var i = 1
    while (i < args.size) {
        when (val a = args[i]) {
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull() ?: return null
            "--control-port" -> controlPort = args.getOrNull(++i)?.toIntOrNull() ?: return null
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
            "--body-file" -> bodyFile = args.getOrNull(++i) ?: return null
            "--body-text" -> bodyText = args.getOrNull(++i) ?: return null
            "--on" -> flag = true
            "--off" -> flag = false
            else -> return null
        }
        i += 1
    }
    if (port !in WailoEngine.PORT_RANGE || controlPort !in 1..65535) return null
    if (limit !in 1..1_000 || bodyChars !in 0..1_000_000 || timeoutSeconds !in 0..86_400) return null
    return ParsedArgs(
        command = command,
        port = port,
        controlPort = controlPort,
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
        bodyFile = bodyFile,
        bodyText = bodyText,
    )
}

private fun printUsage() {
    println(
        """
        wailo-cli — headless frontend over WailoEngine (ADR-0056)

        wailo-cli serve [--port N] [--control-port N]
        wailo-cli list_exchanges [--limit N]
        wailo-cli search_traffic [--url S] [--url-pattern GLOB] [--method M] [--status C] [--app ID]
        wailo-cli get_exchange --id ID
        wailo-cli set_map_local --id ID --url-pattern GLOB [--method M] [--status C]
                    [--header "Name: value"]... [--body-file PATH|--body-text TEXT] [--off]
        wailo-cli remove_map_local --id ID
        wailo-cli list_map_local
        wailo-cli set_map_local_enabled --on|--off
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

        Start `serve` first. Every other invocation attaches to that process on --control-port
        (default $DEFAULT_CONTROL_PORT), so captures, rules, and holds survive across commands.
        """.trimIndent(),
    )
}
