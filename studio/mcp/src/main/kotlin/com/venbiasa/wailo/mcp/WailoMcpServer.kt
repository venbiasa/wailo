package com.venbiasa.wailo.mcp

import com.venbiasa.wailo.daemon.DaemonClient
import com.venbiasa.wailo.daemon.RULE_FAMILY_BREAKPOINTS
import com.venbiasa.wailo.daemon.RULE_FAMILY_MAP_LOCAL
import com.venbiasa.wailo.daemon.RULE_FAMILY_SEEDS
import com.venbiasa.wailo.host.HeadlessHost
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking

internal object WailoMcpServer {
    fun create(
        daemon: DaemonClient,
        input: InputStream,
        output: OutputStream,
    ): McpSyncServer = create(DaemonMcpBackend(daemon), input, output)

    fun create(
        host: HeadlessHost,
        capturePort: Int,
        input: InputStream,
        output: OutputStream,
    ): McpSyncServer = create(LocalMcpBackend(host, capturePort), input, output)

    internal fun create(
        backend: McpBackend,
        input: InputStream,
        output: OutputStream,
    ): McpSyncServer {
        val service = WailoMcpService(backend)
        val tools = WailoMcpTools.definitions.map { definition ->
            SyncToolSpecification.builder()
                .tool(
                    Tool.builder(definition.name, definition.inputSchema)
                        .description(definition.description)
                        .annotations(
                            ToolAnnotations.builder()
                                .readOnlyHint(definition.readOnly)
                                .destructiveHint(definition.destructive)
                                .idempotentHint(definition.idempotent)
                                .openWorldHint(false)
                                .build(),
                        )
                        .build(),
                )
                .callHandler { _, request ->
                    val response = runBlocking {
                        @Suppress("UNCHECKED_CAST")
                        service.call(definition.name, request.arguments() as Map<String, Any?>)
                    }
                    CallToolResult.builder()
                        .addTextContent(mcpTextContent(response))
                        .structuredContent(response.data)
                        .isError(response.isError)
                        .build()
                }
                .build()
        }
        val transport = StdioServerTransportProvider(McpJsonDefaults.getMapper(), input, output)
        return McpServer.sync(transport)
            .serverInfo("wailo", "0.1.0")
            .instructions(
                "Wailo captures HTTP(S) traffic from instrumented Android and iOS apps. " +
                    "Call status first, configure Map Local, Capture Filter, breakpoint, or seed rules as " +
                    "needed, then inspect or wait for exchanges. To script a sequence of answers, set " +
                    "breakpoints, write seeds, then fill_seeds. " +
                    "For SDK-less traffic, read get_proxy_setup_guide before making an explicit proxy " +
                    "change; mutations require confirm=true and setup_proxy_target returns the confirmed " +
                    "trust store and any remaining manual action. " +
                    "Studio, CLI, and MCP share one persistent local daemon. " +
                    "The user controls this access and can revoke it; when status reports " +
                    "redacting_secrets, credentials in headers, URLs, and bodies read back as " +
                    "\"$REDACTED_VALUE\" and cannot be recovered through these tools.",
            )
            .capabilities(ServerCapabilities.builder().tools(false).build())
            .tools(tools)
            .build()
    }
}

internal data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>,
    val readOnly: Boolean = false,
    val destructive: Boolean = false,
    val idempotent: Boolean = true,
)

internal object WailoMcpTools {
    val definitions: List<McpToolDefinition> = listOf(
        readTool(
            "status",
            "Check whether Wailo is listening and summarize the capture port, devices, exchanges, holds, " +
                "active rule counts, and whether secrets are being redacted. Call this first.",
            objectSchema(),
        ),
        readTool(
            "list_exchanges",
            "List the newest captured HTTP exchanges as compact metadata without bodies.",
            objectSchema("limit" to integer("Maximum rows, newest last", minimum = 1, maximum = 500)),
        ),
        readTool(
            "search_traffic",
            "Search captured traffic by URL substring or wildcard URL pattern, method, status, and app id. Use either url_contains or url_pattern, not both.",
            objectSchema(
                "url_contains" to string("Case-insensitive URL substring"),
                "url_pattern" to string("Full-URL wildcard pattern where * matches any characters"),
                "method" to string("HTTP method"),
                "status_code" to integer("HTTP response status", minimum = 100, maximum = 599),
                "app_id" to string("Application identifier"),
                "limit" to integer("Maximum rows, newest last", minimum = 1, maximum = 500),
            ),
        ),
        readTool(
            "get_exchange",
            "Get one captured exchange with request and response headers and bounded body data. Text MIME bodies are UTF-8; other bodies are Base64.",
            objectSchema(
                "id" to string("Exchange id"),
                "body_bytes" to integer("Maximum bytes returned from each body", minimum = 0, maximum = 1_000_000),
                required = listOf("id"),
            ),
        ),
        readTool(
            "wait_for_exchange",
            "Wait until an already-captured or new exchange matches. Useful after triggering app behavior from an automation tool.",
            objectSchema(
                "url_contains" to string("Case-insensitive URL substring"),
                "url_pattern" to string("Full-URL wildcard pattern where * matches any characters"),
                "method" to string("HTTP method"),
                "status_code" to integer("HTTP response status", minimum = 100, maximum = 599),
                "timeout_seconds" to integer("Wait timeout", minimum = 1, maximum = 300),
            ),
        ),
        readTool("list_devices", "List SDK clients currently connected to this Wailo process.", objectSchema()),
        readTool(
            "wait_for_device",
            "Wait until an already-connected or new SDK client matches an app id or case-insensitive device-name substring.",
            objectSchema(
                "app_id" to string("Application identifier"),
                "device_name" to string("Case-insensitive device-name substring"),
                "timeout_seconds" to integer("Wait timeout", minimum = 1, maximum = 300),
            ),
        ),
        readTool(
            "list_holds",
            "List requests or responses currently paused by breakpoint rules, including bounded bodies.",
            objectSchema("body_bytes" to integer("Maximum bytes returned from each body", minimum = 0, maximum = 1_000_000)),
        ),
        readTool(
            "wait_for_hold",
            "Wait until an already-paused or new breakpoint hold matches URL, method, or phase.",
            objectSchema(
                "url_contains" to string("Case-insensitive URL substring"),
                "url_pattern" to string("Full-URL wildcard pattern where * matches any characters"),
                "method" to string("HTTP method"),
                "phase" to enumString("Breakpoint phase", "request", "response"),
                "timeout_seconds" to integer("Wait timeout", minimum = 1, maximum = 300),
                "body_bytes" to integer("Maximum bytes returned from each body", minimum = 0, maximum = 1_000_000),
            ),
        ),
        McpToolDefinition(
            "clear_capture",
            "Permanently clear all exchanges retained by this Wailo process.",
            objectSchema(),
            destructive = true,
        ),
        McpToolDefinition(
            "set_capturing",
            "Start or pause retention of incoming exchanges. Connected sessions and rules remain active.",
            objectSchema("enabled" to boolean("Whether to retain incoming exchanges"), required = listOf("enabled")),
        ),
        McpToolDefinition(
            "set_max_retained",
            "Set the in-memory exchange retention cap. Lowering it immediately discards the oldest excess exchanges.",
            objectSchema(
                "max_retained" to integer("Exchange retention cap", minimum = 100, maximum = 100_000),
                required = listOf("max_retained"),
            ),
            destructive = true,
        ),
        readTool(
            "proxy_status",
            "Read the bundled proxy's listener, LAN exposure, system-proxy takeover, certificate, and TLS " +
                "decryption state. The warnings array calls out active machine or network effects.",
            objectSchema(),
        ),
        readTool(
            "get_proxy_setup_guide",
            "Get ordered blockers, next steps, self-check URLs, and target outcomes for SDK-less setup. " +
                "This never starts the listener, opens LAN access, or creates a certificate.",
            objectSchema(),
        ),
        readTool(
            "list_proxy_targets",
            "List booted simulators, emulators, and ADB-connected Android phones, including routing, " +
                "confirmed trust store, stale-certificate, pending-cleanup, and required-action state.",
            objectSchema(),
        ),
        McpToolDefinition(
            "set_proxy",
            "Explicitly start or stop the bundled proxy. Stopping first restores targets Wailo configured " +
                "and refuses to stop if any restoration fails. A port can be supplied only when no target is routed.",
            objectSchema(
                "enabled" to boolean("Whether the listener should run"),
                "port" to integer("Listener port; omit to keep the current port", minimum = 1, maximum = 65535),
                "confirm" to confirmation("start or stop the listener and restore any dependent targets"),
                required = listOf("enabled", "confirm"),
            ),
            destructive = true,
        ),
        McpToolDefinition(
            "set_proxy_port",
            "Change the bundled proxy's port. This is refused while any configured target still depends on the old port.",
            objectSchema(
                "port" to integer("Listener port", minimum = 1, maximum = 65535),
                "confirm" to confirmation("change the listener port"),
                required = listOf("port", "confirm"),
            ),
            destructive = true,
        ),
        McpToolDefinition(
            "set_proxy_lan",
            "Explicitly expose the proxy to this Mac's network or return it to loopback. Enabling this " +
                "opens an unauthenticated relay to clients that can reach the Mac; disabling is refused " +
                "while a physical Android target depends on it.",
            objectSchema(
                "enabled" to boolean("Whether the listener is reachable over LAN"),
                "confirm" to confirmation("change LAN exposure"),
                required = listOf("enabled", "confirm"),
            ),
            destructive = true,
        ),
        McpToolDefinition(
            "set_system_proxy",
            "Explicitly route this Mac's HTTP and HTTPS traffic through Wailo or restore its snapshotted " +
                "settings. Disabling is refused while a configured iOS Simulator depends on it.",
            objectSchema(
                "enabled" to boolean("Whether Wailo owns the Mac system proxy"),
                "confirm" to confirmation("change this Mac's HTTP and HTTPS proxy settings"),
                required = listOf("enabled", "confirm"),
            ),
            destructive = true,
        ),
        McpToolDefinition(
            "set_proxy_decrypt_hosts",
            "Replace the complete list of host patterns whose TLS Wailo may terminate. An empty list " +
                "relocks every host; * unlocks every host and should be used only deliberately.",
            objectSchema(
                "hosts" to stringArray("Full host patterns to decrypt; replaces the current list"),
                "confirm" to confirmation("replace the TLS decryption allowlist"),
                required = listOf("hosts", "confirm"),
            ),
            destructive = true,
        ),
        readTool(
            "get_proxy_ca",
            "Read the existing local proxy root and public PEM without creating one. The private key never leaves the daemon.",
            objectSchema(),
        ),
        McpToolDefinition(
            "ensure_proxy_ca",
            "Create the local proxy root if absent and return its public PEM and SHA-256 fingerprint. " +
                "The private key never leaves the daemon.",
            objectSchema(
                "confirm" to confirmation("create a signing root in this Mac's Keychain if none exists"),
                required = listOf("confirm"),
            ),
            destructive = true,
        ),
        McpToolDefinition(
            "rotate_proxy_ca",
            "Replace the local proxy root. Every configured target becomes stale and must be repaired " +
                "before HTTPS decryption works again.",
            objectSchema(
                "confirm" to confirmation("replace the signing root and invalidate target trust"),
                required = listOf("confirm"),
            ),
            destructive = true,
            idempotent = false,
        ),
        McpToolDefinition(
            "remove_proxy_ca",
            "Remove the local proxy root. Refused while Wailo has configured targets, because their trust " +
                "stores must be released first.",
            objectSchema(
                "confirm" to confirmation("remove the local signing root"),
                required = listOf("confirm"),
            ),
            destructive = true,
        ),
        McpToolDefinition(
            "setup_proxy_target",
            "Configure one id from list_proxy_targets, creating the local root if absent, then install or " +
                "stage it. This may start the proxy, enable the Mac system proxy for an iOS Simulator, or " +
                "expose the listener over LAN for a physical Android device. Inspect the structured trust " +
                "and action_required fields.",
            objectSchema(
                "id" to string("Target id returned by list_proxy_targets"),
                "confirm" to confirmation("change this target's network and certificate state"),
                required = listOf("id", "confirm"),
            ),
            destructive = true,
        ),
        McpToolDefinition(
            "clear_proxy_target",
            "Restore one target's exact prior proxy and remove only certificate files Wailo recorded as " +
                "its own. A failed cleanup remains recorded and retryable.",
            objectSchema(
                "id" to string("Configured target id"),
                "confirm" to confirmation("restore this target and remove Wailo-owned certificate files"),
                required = listOf("id", "confirm"),
            ),
            destructive = true,
        ),
        McpToolDefinition(
            "set_map_local",
            "Create or replace an in-memory Map Local response fixture and push its match metadata to connected devices.",
            objectSchema(
                "id" to string("Stable rule id"),
                "name" to string("Author-facing label shown in Studio; not used for matching"),
                "url_pattern" to string("Full-URL wildcard pattern where * matches any characters"),
                "method" to string("The one HTTP method to match; blank or omitted means any"),
                "methods" to legacyMethods(),
                "enabled" to boolean("Whether this rule is active"),
                "status_code" to integer("Mock response status", minimum = 100, maximum = 599),
                "headers" to headers(),
                "body_text" to string("UTF-8 response body"),
                "body_base64" to string("Base64 response body; mutually exclusive with body_text"),
                "group_id" to groupId("Map Local"),
                required = listOf("id", "url_pattern"),
            ),
        ),
        McpToolDefinition(
            "remove_map_local",
            "Remove an in-memory Map Local fixture by id and update connected devices.",
            objectSchema("id" to string("Rule id"), required = listOf("id")),
        ),
        readTool(
            "list_map_local",
            "List all in-memory Map Local fixtures and the global enabled state, without body data. Call " +
                "get_map_local for one fixture's body.",
            objectSchema(),
        ),
        readTool(
            "get_map_local",
            "Get one Map Local fixture by id with its bounded response body, to check what it actually " +
                "serves. Text MIME bodies are UTF-8; other bodies are Base64.",
            objectSchema(
                "id" to string("Rule id"),
                "body_bytes" to integer("Maximum bytes returned from the fixture body", minimum = 0, maximum = 1_000_000),
                required = listOf("id"),
            ),
        ),
        McpToolDefinition(
            "set_map_local_enabled",
            "Globally enable or disable all registered Map Local fixtures without deleting them.",
            objectSchema("enabled" to boolean("Global Map Local state"), required = listOf("enabled")),
        ),
        McpToolDefinition(
            "set_capture_filter",
            "Replace the device-side Capture Filter. Patterns replace the list they are given for, and a " +
                "list is armed by having any; passing neither patterns nor a switch for a list empties it. " +
                "Send only allowlist_enabled or blocklist_enabled to arm or disarm a list without " +
                "resending it.",
            objectSchema(
                "allow_patterns" to stringArray("Host wildcard patterns to allow"),
                "block_patterns" to stringArray("Host wildcard patterns to block"),
                "allowlist_enabled" to boolean("Whether the allowlist applies; defaults to whether it has patterns"),
                "blocklist_enabled" to boolean("Whether the blocklist applies; defaults to whether it has patterns"),
            ),
        ),
        McpToolDefinition(
            "set_capture_filter_enabled",
            "Enable or disable the whole Capture Filter without clearing either list. While off every host is captured, and each list keeps the state it comes back with.",
            objectSchema("enabled" to boolean("Global Capture Filter state"), required = listOf("enabled")),
        ),
        readTool("list_capture_filter", "Read the authored Capture Filter and whether it is enabled.", objectSchema()),
        McpToolDefinition(
            "set_breakpoint",
            "Create or replace a breakpoint rule. Matching calls pause until resume_hold or abort_hold decides them.",
            objectSchema(
                "id" to string("Stable rule id"),
                "url_pattern" to string("Full-URL wildcard pattern where * matches any characters"),
                "method" to string("The one HTTP method to match; blank or omitted means any"),
                "methods" to legacyMethods(),
                "enabled" to boolean("Whether this rule is active"),
                "on_request" to boolean("Pause before the request is sent; defaults false"),
                "on_response" to boolean("Pause before the response reaches the app; defaults true"),
                "group_id" to groupId("breakpoint"),
                required = listOf("id", "url_pattern"),
            ),
        ),
        McpToolDefinition(
            "remove_breakpoint",
            "Remove a breakpoint rule by id and update connected devices.",
            objectSchema("id" to string("Rule id"), required = listOf("id")),
        ),
        readTool("list_breakpoints", "List breakpoint rules and the global enabled state.", objectSchema()),
        McpToolDefinition(
            "set_breakpoints_enabled",
            "Globally enable or disable all registered breakpoint rules without deleting them.",
            objectSchema("enabled" to boolean("Global breakpoint state"), required = listOf("enabled")),
        ),
        McpToolDefinition(
            "set_seed",
            "Create or replace a seed: a canned response that answers a response-phase breakpoint hold. " +
                "Seeds are matched in library order and each one answers a single hold, so two seeds for " +
                "the same URL answer two successive calls. Writing a seed does not arm it; call fill_seeds.",
            objectSchema(
                "id" to string("Stable seed id"),
                "url_pattern" to string("Full-URL wildcard pattern where * matches any characters"),
                "method" to string("HTTP method; empty or omitted means any"),
                "enabled" to boolean("Whether fill_seeds arms this seed"),
                "status_code" to integer("Canned response status", minimum = 100, maximum = 599),
                "headers" to headers(),
                "body_text" to string("UTF-8 response body"),
                "body_base64" to string("Base64 response body; mutually exclusive with body_text"),
                "group_id" to groupId("seed"),
                required = listOf("id", "url_pattern"),
            ),
        ),
        McpToolDefinition(
            "remove_seed",
            "Remove a seed from the library by id, and from the armed queue if it is in it.",
            objectSchema("id" to string("Seed id"), required = listOf("id")),
        ),
        readTool(
            "list_seeds",
            "List the seed library and the global enabled state, without body data. armed says whether a " +
                "seed is still waiting to answer a hold; a seed that has been spent stays in the library " +
                "with armed false until the next fill_seeds.",
            objectSchema(),
        ),
        readTool(
            "get_seed",
            "Get one seed by id with its bounded response body, to check what it would answer with. " +
                "Text MIME bodies are UTF-8; other bodies are Base64.",
            objectSchema(
                "id" to string("Seed id"),
                "body_bytes" to integer("Maximum bytes returned from the seed body", minimum = 0, maximum = 1_000_000),
                required = listOf("id"),
            ),
        ),
        McpToolDefinition(
            "set_seeds_enabled",
            "Globally enable or disable seed answering without deleting the library.",
            objectSchema("enabled" to boolean("Global seed state"), required = listOf("enabled")),
        ),
        McpToolDefinition(
            "set_rule_group",
            "Create a rule group, or rename or re-gate an existing one. Groups organise a panel and can " +
                "be switched off as a unit: no rule in a disabled group matches, while each rule keeps " +
                "its own state for when the group comes back on. Create the group before filing rules " +
                "into it with the group_id argument.",
            objectSchema(
                "family" to ruleFamily(),
                "id" to string("Stable group id"),
                "name" to string("Author-facing group label shown in Studio"),
                "enabled" to boolean("Whether rules in this group can match; defaults true"),
                required = listOf("family", "id"),
            ),
        ),
        McpToolDefinition(
            "remove_rule_group",
            "Delete a rule group. Its rules survive as ungrouped rules unless with_rules is true, which " +
                "deletes them with it.",
            objectSchema(
                "family" to ruleFamily(),
                "id" to string("Group id"),
                "with_rules" to boolean("Also delete the rules inside the group; defaults false"),
                required = listOf("family", "id"),
            ),
            destructive = true,
        ),
        readTool(
            "list_rule_groups",
            "List one panel's rule groups with their enabled state.",
            objectSchema("family" to ruleFamily(), required = listOf("family")),
        ),
        McpToolDefinition(
            "set_rule_order",
            "Set match priority within one container: of two rules that both match, the higher one wins. " +
                "With group_id the ids are the rules inside that group; without it they are the top-level " +
                "entries, where an id is either a group id or an ungrouped rule's id. Ids you omit keep " +
                "their order behind the ones you name, so promoting one rule needs only that rule's id. " +
                "To move a rule into another group, file it there with set_map_local/set_breakpoint/" +
                "set_seed and its group_id instead.",
            objectSchema(
                "family" to ruleFamily(),
                "group_id" to string("Order the rules inside this group; omit to order the top level"),
                "ids" to stringArray("Ids in the order they should match, highest priority first"),
                required = listOf("family", "ids"),
            ),
        ),
        McpToolDefinition(
            "fill_seeds",
            "Arm every enabled seed in order and immediately answer the holds already waiting, then keep " +
                "answering matching holds as they arrive until the queue is spent. Returns how many are " +
                "left armed. Re-filling replaces a partly spent queue, restarting the sequence.",
            objectSchema(),
            destructive = true,
            idempotent = false,
        ),
        McpToolDefinition(
            "clear_seed_queue",
            "Disarm every seed without deleting the library, so waiting holds are left for a human or an " +
                "explicit resume_hold.",
            objectSchema(),
            destructive = true,
        ),
        McpToolDefinition(
            "resume_hold",
            "Resume a paused call, optionally editing the active phase. method/url apply to request holds; " +
                "status_code applies to response holds; headers/body apply to either. Passing headers replaces " +
                "the whole list; a value left as \"$REDACTED_VALUE\" keeps the real one.",
            objectSchema(
                "correlation_id" to string("Paused exchange correlation id"),
                "method" to string("Edited request method"),
                "url" to string("Edited request URL"),
                "status_code" to integer("Edited response status", minimum = 100, maximum = 599),
                "headers" to headers(),
                "body_text" to string("Edited UTF-8 body"),
                "body_base64" to string("Edited Base64 body; mutually exclusive with body_text"),
                required = listOf("correlation_id"),
            ),
            destructive = true,
            idempotent = false,
        ),
        McpToolDefinition(
            "abort_hold",
            "Abort a paused request or response, causing the intercepted app call to fail.",
            objectSchema("correlation_id" to string("Paused exchange correlation id"), required = listOf("correlation_id")),
            destructive = true,
            idempotent = false,
        ),
    )

    private fun readTool(name: String, description: String, schema: Map<String, Any>) =
        McpToolDefinition(name, description, schema, readOnly = true)
}

private fun objectSchema(
    vararg properties: Pair<String, Map<String, Any>>,
    required: List<String> = emptyList(),
): Map<String, Any> = buildMap {
    put("type", "object")
    put("properties", properties.toMap())
    if (required.isNotEmpty()) put("required", required)
    put("additionalProperties", false)
}

private fun string(description: String): Map<String, Any> = mapOf(
    "type" to "string",
    "description" to description,
)

private fun boolean(description: String): Map<String, Any> = mapOf(
    "type" to "boolean",
    "description" to description,
)

private fun confirmation(action: String): Map<String, Any> =
    boolean("Must be true to confirm that Wailo may $action.")

private fun enumString(description: String, vararg values: String): Map<String, Any> = mapOf(
    "type" to "string",
    "description" to description,
    "enum" to values.toList(),
)

private fun integer(description: String, minimum: Int, maximum: Int): Map<String, Any> = mapOf(
    "type" to "integer",
    "description" to description,
    "minimum" to minimum,
    "maximum" to maximum,
)

private fun stringArray(description: String): Map<String, Any> = mapOf(
    "type" to "array",
    "description" to description,
    "items" to mapOf("type" to "string"),
)

/**
 * Still described rather than dropped, because these schemas set `additionalProperties: false` — a
 * validating client would refuse the array before the collapse could happen, so a prompt written against
 * the old shape has to keep working (ADR-0087).
 */
private fun legacyMethods(): Map<String, Any> = stringArray(
    "Deprecated: use method. A rule matches one method, so only the last entry here is kept.",
)

private fun ruleFamily(): Map<String, Any> = enumString(
    "Which panel's groups to act on",
    RULE_FAMILY_MAP_LOCAL,
    RULE_FAMILY_BREAKPOINTS,
    RULE_FAMILY_SEEDS,
)

private fun groupId(family: String): Map<String, Any> = string(
    "Id of an existing $family group to file this rule into; omit to leave it where it is, or pass an " +
        "empty string to move it out of its group. Create groups with set_rule_group.",
)

private fun headers(): Map<String, Any> = mapOf(
    "type" to "array",
    "description" to "Ordered HTTP headers; duplicate names are allowed",
    "items" to objectSchema(
        "name" to string("Header name"),
        "value" to string("Header value"),
        required = listOf("name", "value"),
    ),
)
