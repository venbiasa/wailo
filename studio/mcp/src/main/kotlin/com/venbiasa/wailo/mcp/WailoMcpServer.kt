package com.venbiasa.wailo.mcp

import com.venbiasa.wailo.daemon.DaemonClient
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

    private fun create(
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
                        .addTextContent(response.text)
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
                    "Call status first, configure Map Local, Capture Filter, or breakpoint rules as needed, " +
                    "then inspect or wait for exchanges. Studio, CLI, and MCP share one persistent local daemon. " +
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
        McpToolDefinition(
            "set_map_local",
            "Create or replace an in-memory Map Local response fixture and push its match metadata to connected devices.",
            objectSchema(
                "id" to string("Stable rule id"),
                "name" to string("Author-facing label shown in Studio; not used for matching"),
                "url_pattern" to string("Full-URL wildcard pattern where * matches any characters"),
                "methods" to stringArray("HTTP methods; empty or omitted means any"),
                "enabled" to boolean("Whether this rule is active"),
                "status_code" to integer("Mock response status", minimum = 100, maximum = 599),
                "headers" to headers(),
                "body_text" to string("UTF-8 response body"),
                "body_base64" to string("Base64 response body; mutually exclusive with body_text"),
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
                "get_map_local for one fixture's body. A fixture with body_available false never serves " +
                "even while enabled is true.",
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
            "Replace the device-side Capture Filter. Empty or omitted arrays disable that side; both empty means capture everything.",
            objectSchema(
                "allow_patterns" to stringArray("Host wildcard patterns to allow"),
                "block_patterns" to stringArray("Host wildcard patterns to block"),
            ),
        ),
        readTool("list_capture_filter", "Read the Capture Filter currently pushed to devices.", objectSchema()),
        McpToolDefinition(
            "set_breakpoint",
            "Create or replace a breakpoint rule. Matching calls pause until resume_hold or abort_hold decides them.",
            objectSchema(
                "id" to string("Stable rule id"),
                "url_pattern" to string("Full-URL wildcard pattern where * matches any characters"),
                "methods" to stringArray("HTTP methods; empty or omitted means any"),
                "enabled" to boolean("Whether this rule is active"),
                "on_request" to boolean("Pause before the request is sent; defaults false"),
                "on_response" to boolean("Pause before the response reaches the app; defaults true"),
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

private fun headers(): Map<String, Any> = mapOf(
    "type" to "array",
    "description" to "Ordered HTTP headers; duplicate names are allowed",
    "items" to objectSchema(
        "name" to string("Header name"),
        "value" to string("Header value"),
        required = listOf("name", "value"),
    ),
)
