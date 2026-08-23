---
adr: 0061
title: Daemon-owned configuration is durable; traffic and holds stay a session
date: "2026-08-16"
status: partly superseded
relations: amends ADR-0058 (durable configuration); extends ADR-0019/0026/0027/0029; its opaque-layout decision superseded by ADR-0081
---
# ADR-0061 — Daemon-owned configuration is durable; traffic and holds stay a session

- Status: Accepted; implemented in `daemon` (`DaemonFixturesStore`) and Studio's import path in `desktopApp`.
- Context: ADR-0058 made the daemon the sole owner of capture and left "durable capture/rule recording" as a separate decision. Process state was in memory, so a daemon stop, crash, `installDist` under a live process, or protocol-version replace started empty. Studio then treated that empty snapshot as source of truth and wrote it over the prefs that had been durable since before the daemon existed — Map Local layouts, breakpoint rules, and the capture filter. That was a regression, not a product choice: those values were configuration the user authors, the same class as port / retention / pairing, which ADR-0058 already kept on disk. The user required them to survive a restart.
- Decision:
  - **Daemon-owned configuration is durable.** Map Local, breakpoint rules, and the capture filter join port, retention, USB port, pairing, and MCP access on disk under `~/.wailo/` (owner-only, atomic replace). The daemon loads them before it accepts clients, so devices keep the same mocks and filter without waiting for Studio. `WAILO_HOME` isolates the files the same way it isolates the handshake.
  - **Traffic and holds stay a session.** Captured exchanges and paused breakpoint holds are bounded live state (`maxRetained`), not configuration. They still die with the process. Seeds, bookmarks, theme, and window geometry never left Studio prefs and are unchanged.
  - **Studio's grouped layouts ride along as opaque strings.** The daemon cannot depend on `shared`, so it does not decode groups or names. Studio's `replace_map_local` / `replace_breakpoints` send the codec blob. A full replace that omits it (CLI/MCP) drops the blob, because the compiled list is then the snapshot; upsert/remove keep the last blob.
  - **Studio never copies emptiness over a non-empty authored copy.** Prefs remain the authoring copy (groups, names, filter master). An empty daemon is a restart: Studio re-pushes. Import from the daemon only happens when Studio itself has nothing saved, so a CLI-only fixture still appears on first open, and a flattened projection cannot erase groups.
- Alternatives considered:
  - **Keep configuration in Studio prefs only and just stop the empty-import:** rejected — CLI/MCP mocks and the capture filter would still die whenever the daemon restarts with Studio closed, which contradicts ADR-0058's headless ownership.
  - **Persist traffic and holds too:** rejected — they are a session, they grow without bound except for `maxRetained`, and replaying yesterday's capture on every launch is a different product.
  - **Persist only the flattened `HostMapLocalRule` list:** rejected as the sole store — Studio importing that list on attach drops groups and names. The compiled list is what devices need; the layout string is what the panel needs. Both are written.
  - **Move the layout codec into `daemon` / `host`:** rejected — it would pull `shared`'s grouped-rule model below the UI-agnostic line (invariant #2).
- Consequences:
  - Deleting every Map Local rule or clearing the capture filter in Studio is a real empty replace and is persisted as empty. A daemon crash is not.
  - An MCP `upsert_map_local` while Studio already has a grouped layout will not appear in the panel until the user edits that layout (which then last-write-wins over the daemon). Visible MCP edits still work when Studio's layout is empty.
  - Body files under `~/Library/Application Support/Wailo/maplocal-bodies/` remain the Studio-authored bytes; the daemon snapshot holds its own copy for serving. A layout that was already wiped from prefs before this ADR cannot be reconstructed from bodies alone.
  - The capture filter's pre-rename prefs key (`captureAllowlistHosts`) is still read as a fallback, because the empty-daemon import wrote the new key as blank and left the old one holding the list.
