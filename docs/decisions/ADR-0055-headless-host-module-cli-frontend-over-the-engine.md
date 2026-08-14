---
adr: 0055
title: Headless `host` module + CLI frontend over the engine (extends ADR-0003)
date: "2026-05-12"
status: accepted
relations: extends ADR-0003
date_source: git-commit
---
# ADR-0055 — Headless `host` module + CLI frontend over the engine (extends ADR-0003)

- Status: Accepted; implemented in `studio/host` and `studio/cli`. `cd studio && ./gradlew :host:test :cli:test` is the verify gate.
- Context: ADR-0003 said UI, CLI, and MCP are thin frontends over `engine`, but Seed auto-spend and traffic wait/find lived only in `desktopApp` (`Main.kt`), so a second frontend would have reimplemented them. The ask is to expose the Tier 1 capture/query surface (and breakpoint holds) for automation and a future MCP server without forking the engine or pulling Compose into headless processes.
- Decision:
  - **New `studio/host` JVM module** depends only on `engine` (never on `shared` / Compose). It owns `HeadlessHost`, `spendSeedOn` / `HostSeed`, and `TrafficQueries` (find / wait-with-timeout / summarize).
  - **New `studio/cli` JVM module** is the first headless frontend: a thin command surface over `HeadlessHost` whose verbs match the planned MCP tool names (`list_exchanges`, `get_exchange`, `search_traffic`, `list_devices`, `list_holds`, `resume_hold`, `abort_hold`, `clear_capture`, `wait_exchange`, …). MCP can later be another adapter on the same host API.
  - **Desktop keeps talking to `engine` for Compose state**, but Seed spend goes through `host.spendSeedOn` so desktop and headless cannot drift on what a seed is allowed to answer.
  - **No durable capture sink** in this ADR — exchanges stay in-memory (see the earlier note under retention). Export/record remains a separate decision if CI needs post-process persistence.
- Alternatives considered:
  - **Put orchestration inside `engine`:** rejected — Seed spend and wait helpers are frontend concerns; `engine` stays the transport + store + push/decision seam.
  - **Have `host` depend on `shared` for `SeedRuleDef`:** rejected — `shared` pulls Compose; headless must not.
  - **MCP server as the first frontend:** deferred — CLI is enough to dogfood the host API; MCP maps 1:1 onto the same verbs later.
- Consequences:
  - Studio module graph: `engine <- host <- cli` and `engine <- host <- desktopApp` alongside `shared <- desktopApp`. `shared` still must not depend on `engine` or `host`.
  - Wildcard URL matching is duplicated between `host` and `shared` on purpose (same byte-identical scheme as the devices, ADR-0041); unifying it would force `shared → host → engine` and break the UI firewall.
  - Pairing in headless defaults to `InMemoryPairingKeyStore`; durable Keychain stays a desktop concern.
  - Manual smoke: `cd studio && ./gradlew :cli:run --args='serve --port 8899'`, point a sample app at it, then on stdin `list_devices` / `list_exchanges` / `wait_exchange --url /todos` / `list_holds` + `resume_hold --id …`.
