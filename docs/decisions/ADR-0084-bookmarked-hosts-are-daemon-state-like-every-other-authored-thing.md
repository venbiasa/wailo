---
adr: 0084
title: Bookmarked hosts are daemon state, like every other authored thing
date: "2026-08-24"
status: accepted
relations: applies ADR-0059/0066's daemon-state test to the last piece of user content Studio kept alone; follows ADR-0061/0081/0082's ownership moves
---
# ADR-0084 — Bookmarked hosts are daemon state, like every other authored thing

- Status: Accepted; `bookmarks.json` sits beside the other fixtures in the state directory, `PollResponse` carries `bookmarkedHosts` at protocol version 12, and `set_bookmark` exists as a command with `list_bookmarks` in the CLI and `bookmarked_hosts` in the MCP `status` tool. `cd studio && ./gradlew :daemon:test :cli:test :mcp:test :desktopApp:test` is green, including a restart case in `DaemonIntegrationTest`.
- Context: An audit of every persistence site in `desktopApp`, `cli`, and `mcp` found thirteen stores in Studio and none in the two headless frontends. Most were either dead writes or duplicates of daemon state. Bookmarked hosts were the one piece of genuine user *content* with no daemon counterpart at all: a list of the hosts the user singled out as worth watching, living only in `~/Library/Preferences/com.venbiasa.wailo.plist` under `bookmarkedHosts`. That put it on the wrong side of ADR-0066's test. It is not presentation — unlike window geometry or the theme, a headless session can act on it — and it is exactly the kind of standing hint an agent wants when deciding which traffic to look at first, but neither the CLI nor MCP could see it, and a second Studio profile against the same daemon disagreed about it.
- Decision:
  - **The daemon holds the list and persists it** as `PersistedBookmarks` in `bookmarks.json`, restored in `restoreFixtures()` beside Map Local, breakpoints, seeds, and the capture filter. Authoring order is preserved, because that is the order the list is shown in.
  - **One host per call, not a whole-list replace.** `set_bookmark` carries a host and a boolean and is idempotent. Bookmarking is the one edit two frontends plausibly make at the same time, and a read-modify-write of the whole list would silently drop whichever write landed first — the same class of loss ADR-0082 avoided by sending the filter's lists and master together.
  - **Every frontend reads it back.** `list_bookmarks` and a `bookmarks=N` count in `status` for the CLI, `bookmarked_hosts` in the MCP `status` payload, and a `StateFlow` on `DaemonClient` that Studio renders directly with no local copy beside it.
- Alternatives considered:
  - **Leave it in Studio's prefs:** rejected by ADR-0066's test. A bookmark is a durable statement about traffic, not about a window, and the frontends that most need the hint are the ones with no window.
  - **Send the whole list on every change,** matching `replace_map_local`: simpler to write and consistent with the rule families, but rule layouts are edited in one place at a time by a user dragging rows, whereas a bookmark is a one-click act available from any traffic row in any frontend. The narrower call removes the race instead of documenting it.
  - **Treat bookmarks as session state beside traffic and holds:** rejected. A bookmark deliberately outlives the exchange that prompted it — that is the whole point of marking a host rather than an exchange.
- Consequences:
  - Protocol version 12. `PollResponse` gains a required `bookmarkedHosts`, so an old frontend and a new daemon do not talk — see the daemon-restart note in `.cursor/rules/gradle-build-directories.mdc` before rebuilding against a live one.
  - Bookmarks made in the previous build are in the plist, not in `bookmarks.json`, and are not migrated. The list starts empty once and is re-marked in a click per host; carrying a migration for a handful of strings would mean keeping the store this ADR exists to delete.
  - `LocalMcpBackend` — the in-process backend that wraps a bare `HeadlessHost` with no daemon behind it — reports an empty list, since there is nothing to read rather than nothing bookmarked.
  - Manual smoke (two-tier verification): bookmark a host in Studio, quit, and run `wailo-cli list_bookmarks` with no window open — the host is listed. Add another with `set_bookmark --host other.example.com` and reopen Studio: both are marked. `set_bookmark --host other.example.com --off` unmarks it in the open window on the next poll.
