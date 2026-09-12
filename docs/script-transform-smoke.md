# Script transform smoke

## Escape hatch

Turn **Scripts** off from its panel or the menu bar. Headlessly:

```bash
wailo-cli set_scripts_enabled --off
wailo-cli remove_script --id <id>
```

A transform failure already fails open to that phase's input; disabling the master removes its round trips
too.

## Smoke set

- **T-UI-THEME** — Open Scripts in light and dark mode. Confirm the rounded code-blocks rail icon is
  upright and centered; create a rule; confirm Name, URL pattern, Method, JavaScript editor, hook result,
  master, group controls, and Save fit without overlap. Invalid source must keep Save disabled.
- **T-BOTH-PROXY** — Through the bundled proxy, run a request hook that adds a header and a response hook
  that changes JSON and status. Confirm the origin sees the header, the caller sees the transformed status
  and JSON, and the captured row contains only those final values with **Edited** set.
- **T-BOTH-SOCKET** — Repeat through one Android and one iOS SDK client. Confirm request Scripts run before
  a request breakpoint/Map Local match, response Scripts run before a response breakpoint, and delay occurs
  last.
- **T-FAIL-OPEN** — Make one rule throw and put a valid rule after it. Confirm the failed rule's edit is
  absent, the later rule still lands, and the row shows a categorical issue. Then use an oversized or
  streaming request body: metadata may change, body replacement must not, and the caller receives the
  original body.

## Results

```text
Date/build: 2026-09-12 — uncommitted worktree; root and Studio builds green
T-UI-THEME: not run — requires visual light/dark inspection
T-BOTH-PROXY: partial — the isolated origin saw the request header, and the caller received transformed HTTP 201 JSON; the captured-row Edited state was not manually inspected
T-BOTH-SOCKET Android: not run — requires a live Android SDK client
T-BOTH-SOCKET iOS: not run — requires a live iOS SDK client
T-FAIL-OPEN: partial — automated tests cover rollback, continuation, timeout recovery, and unavailable bodies; the visible runtime warning was not manually inspected
```
