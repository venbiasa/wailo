---
adr: 0056
title: One headless server owns capture state; CLI invocations use an authenticated loopback control channel (amends ADR-0055)
date: "2026-05-12"
status: accepted
relations: amends ADR-0055
date_source: git-commit
---
# ADR-0056 — One headless server owns capture state; CLI invocations use an authenticated loopback control channel (amends ADR-0055)

- Status: Accepted; implemented in `studio/host` and `studio/cli`. Supersedes ADR-0055's stdin manual-smoke flow.
- Context: The first CLI implementation made every one-shot command construct a fresh engine. `list_exchanges`, `get_exchange`, and hold decisions therefore queried an empty process rather than the process capturing traffic; only stdin commands inside `serve` were meaningful. Review also found that a hold arriving before its Seed response provider was installed was marked triaged forever, and that Tier 1 claimed Map Local / Capture Filter without exposing either through the CLI.
- Decision:
  - **Exactly one `serve` process owns the in-memory engine.** Every other CLI invocation connects to it over a length-framed TCP control channel bound to `127.0.0.1` (default `8898`). Shell parsing happens once in the invoking process; argument boundaries are preserved over the channel, including spaces in URLs, headers, and bodies.
  - **The control channel authenticates even on loopback.** `serve` generates a 256-bit per-run token and writes it under `~/.wailo` with owner-only permissions where the filesystem supports POSIX modes. Clients must present it before a command can read bodies or mutate rules. A stale token after a crash grants nothing and is overwritten by the next server.
  - **Headless Map Local fixtures live in `host`, in memory.** `HostMapLocalRule` retains response bytes on the host, pushes only `MapLocalRule` metadata, and serves bytes lazily through the engine's existing `MapLocalBodyProvider` seam (ADR-0019). CLI commands add/remove/list rules and toggle the feature; Capture Filter commands push full allow/block snapshots.
  - **Breakpoint decisions report whether a hold existed.** `resumeBreakpoint` / `abortBreakpoint` return false for an unknown or disconnected correlation id, and frontends propagate that as a non-zero result instead of printing a false success.
  - **Seed readiness is a trigger, not a missed moment.** Installing a response provider or enabling Seeds retriggers triage for holds already waiting. A Seed is consumed only after the engine accepts the breakpoint decision.
- Alternatives considered:
  - **Persist exchanges between one-shot processes:** rejected — it turns the intentionally in-memory live tap into a partial durable sink while still failing for live holds and connected devices.
  - **Keep stdin as the automation API:** rejected — it requires callers to own and multiplex one subprocess's pipes, cannot be invoked naturally from shell/Appium steps, and re-tokenizes quoted input.
  - **Unauthenticated loopback HTTP/JSON:** rejected — any local process could read captured credentials or install a mock; JSON also adds a second DTO/schema before MCP needs one.
- Consequences:
  - The server must remain alive for one-shot commands; failure to connect is explicit and non-zero. `--control-port` allows parallel isolated runs.
  - Rules, response bodies, and captures remain process-memory only. A server restart clears them; durable record/import remains a separate ADR.
  - `UrlPatternParityTest` compares the deliberately duplicated `shared` and `host` wildcard evaluators over one corpus, so the module firewall does not leave semantic drift unguarded.
  - Verify with `cd studio && ./gradlew :host:test :cli:test :desktopApp:test`. Manual smoke: build `:cli:installDist`; run `wailo-cli serve` in one process, then run `list_devices`, `set_capture_filter`, `set_map_local`, `wait_exchange`, and `list_exchanges` from separate processes.
