---
adr: 0058
title: One persistent local daemon is shared by Studio, CLI, and MCP (supersedes ADR-0056/0057 process ownership)
date: "2026-08-13"
status: accepted
relations: supersedes ADR-0056/0057 process ownership
date_source: uncommitted-at-migration
---
# ADR-0058 — One persistent local daemon is shared by Studio, CLI, and MCP (supersedes ADR-0056/0057 process ownership)

- Status: Accepted; implemented in `studio/daemon`, with all three frontends migrated to its client.
- Context: ADR-0056 fixed throwaway CLI state by making `wailo-cli serve` own one engine. ADR-0057 then made every MCP stdio child own a different engine for one-command setup. Those choices made either Studio, CLI serve, or one MCP client the sole owner of port 8899: concurrent frontends failed to bind or saw unrelated captures, rules, devices, and holds. The required behavior is stronger: Studio and any number of CLI/MCP clients must share state regardless of startup order; capture must continue when all frontends close; Android and physical-iOS cable support must remain available headlessly.
- Decision:
  - **`studio/daemon` is the only process allowed to own capture.** It constructs `HeadlessHost`, binds the device WebSocket, retains exchanges/rules/holds, owns the macOS pairing Keychain, keeps `adb reverse` mappings alive, and attaches physical iOS devices through usbmuxd. `desktopApp`, `cli`, and `mcp` depend on the daemon client and never construct `WailoEngine` or cable managers.
  - **Every frontend starts the daemon on demand and then attaches.** A client first probes the loopback control port (8898). If absent it launches `wailo-daemon` from the current application classpath, with stdin detached and logs redirected under `~/.wailo`; simultaneous launch races are resolved by the capture/control binds. The daemon outlives its launcher and every client; it is not a login item. `wailo-cli stop` records an explicit-stop marker so already-open clients do not immediately relaunch it, while opening a new frontend is a fresh request to start it. The old `serve` verb remains as an ensure-running compatibility command and returns immediately.
  - **Control compatibility is explicit.** The daemon reports its control-protocol version in the authenticated probe. A newer frontend automatically stops and replaces an older incompatible daemon, accepting the loss of that daemon's in-memory session; an older frontend refuses to replace a newer daemon and asks to be updated. The binary framing stays stable so version probing and orderly replacement remain available.
  - **The local RPC is authenticated and length-framed.** The daemon creates a 256-bit per-run token in owner-only `~/.wailo/daemon.token`; every loopback request presents it. JSON payloads sit inside a bounded binary frame so arguments and response bodies are not shell-tokenized. These daemon DTOs describe a local process API; device/desktop traffic remains exclusively the generated protobuf contract from `protocol`.
  - **State projection is incremental.** Clients poll a compact state snapshot, transfer captured protobuf exchanges once in bounded batches, and receive Map Local/breakpoint/hold bodies only when their hashes change. Initial connection waits until all retained batches are synchronized, so one-shot CLI/MCP reads are complete. Each client exposes `StateFlow`s, so Compose remains reactive while CLI/MCP use the same commands. Studio uploads file-backed Map Local bytes to daemon memory and imports external rule/filter changes, making MCP mutations visible in the open UI.
  - **Concurrent configuration writes use last-write-wins.** Commands are atomic at the daemon boundary; if Studio and MCP replace the same rules or filter concurrently, the last completed mutation becomes shared state and all clients converge on the following poll.
  - **MCP remains stdio, but no longer owns capture.** Cursor/Claude still launch one `wailo-mcp` child and stdout remains protocol-only. The child is now an adapter over `DaemonClient`; stdin EOF closes only that adapter, not capture. CLI commands likewise call the daemon directly rather than a protocol private to another frontend.
  - **Process state remains intentionally in memory.** Captures, active mocks, filters, and holds survive frontend disconnects for as long as the daemon lives. Explicit daemon stop, crash, logout, or reboot clears process state; ports, retention, USB port, and pairing secrets retain their existing durable storage. Durable capture/rule recording remains a separate decision.
- Alternatives considered:
  - **Studio owns capture and MCP attaches only while Studio is open:** rejected — violates headless operation and makes startup order observable.
  - **MCP owns capture when Studio is absent, then hands it over:** rejected — transferring live sockets, holds, pairing identity, and transport jobs is more failure-prone than stable ownership.
  - **Unauthenticated HTTP or a filesystem snapshot:** rejected — local processes could read credentials or mutate mocks, and snapshots cannot carry live device sessions or breakpoint decisions.
  - **Stop after the last client disconnects:** rejected for the selected lifecycle — automation and MCP reconnects must not create gaps in capture or discard state.
- Consequences:
  - There is one capture port and one shared truth per OS user. `--port` / `--max-retained` on MCP update that daemon; they no longer create an isolated server.
  - Closing Studio, Cursor, or Claude does not release port 8899. Use `wailo-cli status` to inspect the daemon and `wailo-cli stop` to release it.
  - Cable support works without Studio because adb/usbmux code and its tests moved below the frontend boundary into `daemon`.
  - The module graph is `engine <- host <- daemon <- {desktopApp, cli, mcp}` plus `shared <- desktopApp`.
  - `DaemonIntegrationTest` covers authenticated multi-client state, concurrent mutations, and disconnect independence. MCP protocol tests retain the real stdio handshake; distribution smoke verifies CLI→MCP and MCP→CLI mutations against one daemon.
  - Amended by ADR-0059: the control port is no longer the fixed 8898, and the token file it describes is now part of a handshake file.
