---
adr: 0057
title: MCP is a client-owned stdio frontend with its own headless capture process (extends ADR-0055/0056)
date: "2026-08-13"
status: accepted
relations: extends ADR-0055/0056
date_source: uncommitted-at-migration
---
# ADR-0057 — MCP is a client-owned stdio frontend with its own headless capture process (extends ADR-0055/0056)

- Status: Accepted; implemented in `studio/mcp`. Verify with `cd studio && ./gradlew :host:test :mcp:test`, then `:mcp:installDist` and a real stdio initialize / tools-list / tool-call / EOF-shutdown exchange.
- Context: Cursor and Claude both launch local MCP servers as child processes over stdin/stdout. Wailo could either make that child own `HeadlessHost`, or make it attach to ADR-0056's separately managed CLI server. Sharing one persistent process would let CLI, Cursor, and Claude see identical state, but it also makes installation two-step and either couples one frontend to another frontend's private protocol or first requires extracting a new authenticated service boundary. The requested first integration favours one command in the client's config and full traffic/rule/hold control.
- Decision:
  - **`studio/mcp` is a self-contained JVM application over `host`.** Cursor or Claude starts `wailo-mcp`; it binds the capture port, retains exchanges and rule bodies in memory, and shuts the engine down when the client closes stdin. It does not depend on `cli` or its control channel.
  - **The transport is stdio and stdout is protocol-only.** Startup/usage failures go to stderr. Client EOF, normal process exit, and external termination all close the MCP server and `HeadlessHost`, so a client restart does not strand the capture port.
  - **Tools expose the complete selected headless surface.** Read tools cover status, devices, traffic list/search/detail/wait, holds, and current Map Local / Capture Filter / breakpoint state. Mutating tools cover retention/capture state, Map Local fixtures, Capture Filter snapshots, breakpoint rules, and resume/edit/abort decisions. Responses contain both short text and structured content; bounded body reads identify UTF-8 versus Base64 and report truncation.
  - **Breakpoint definitions join Map Local definitions in `host`.** `HostBreakpointRule` and its registry retain frontend-owned rules while an independent master pushes either the compiled rules or an empty snapshot. This preserves definitions when MCP disables the feature and keeps protocol construction out of the MCP adapter.
  - **Use the official Java MCP SDK.** Its framework-free core provides stdio on this JVM build. The current Kotlin SDK is compiled against Kotlin 2.4 while Studio remains on Kotlin 2.3.21; advancing the whole Studio compiler for one adapter is not justified.
  - **Project integration is explicit and reproducible.** `:mcp:installDist` creates `wailo-mcp`; `.cursor/mcp.json` launches it with `${workspaceFolder}`, Claude Code's `.mcp.json` uses `CLAUDE_PROJECT_DIR`, and Claude Desktop uses the same executable by absolute path.
- Alternatives considered:
  - **Attach MCP to `wailo-cli serve`:** deferred, not rejected. It is the right shape if simultaneous CLI + multiple AI clients becomes important, but requires a shared service/RPC module rather than `mcp -> cli`, plus multi-client lifecycle and authentication decisions.
  - **Put MCP protocol handling in `host`:** rejected — `host` is protocol-neutral orchestration; an MCP SDK and JSON-RPC transport belong in a leaf frontend.
  - **Use Streamable HTTP:** deferred — local Cursor/Claude integration already manages stdio lifecycle and needs no listening management or authentication surface. HTTP becomes useful only with a shared persistent or remote service.
  - **Expose only read tools:** rejected for this frontend — automation needs to install mocks/filters/breakpoints and decide live holds, not merely observe traffic.
- Consequences:
  - One MCP client owns one private capture state. Cursor, Claude, Studio, and `wailo-cli serve` cannot simultaneously bind the same port; pass a distinct `--port` to isolate processes. Sharing state later means superseding the process model, not making `mcp` depend on `cli`.
  - The headless process intentionally does not depend on desktop-only adb/usbmux plumbing. Android cable users install `adb reverse` themselves; the iOS Simulator uses loopback and physical iOS uses LAN. Pulling either manager below `desktopApp` is a separate architecture decision.
  - Pairing and durable state remain absent: the host uses its in-memory pairing store and process exit clears captures, mocks, filters, and breakpoint definitions.
  - `WailoMcpProtocolTest` proves a real SDK stdio handshake, tool discovery, and call; service tests prove mutations reach one wrapped host. The distribution smoke additionally proves the launched process exits after EOF and emits no non-protocol stdout.
