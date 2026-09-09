---
adr: 0094
title: A Map Local rule authored from a traffic row takes that row's status code
date: "2026-09-10"
status: accepted
relations: supersedes ADR-0045's third decision bullet and the alternative it rejected for symmetry; leaves the rest of ADR-0045 and the row seam of ADR-0021 standing
---
# ADR-0094 — A Map Local rule authored from a row takes that row's status

- Status: Accepted; implemented in `shared` (`TrafficList`'s row menu and `WailoViewer`'s draft).
- Context: A row's "Map Local…" already copies almost the whole observed response into the draft — the
  URL, the method, `seededHeaders`, and the response body's raw bytes. The status code was the one field
  it did not copy, so right-clicking a 404 opened an editor claiming 200 while holding that 404's headers
  and its error body: a response that had never existed. ADR-0045 defended the split on the grounds that a
  mapping authors a *new* response where a seed replays an old one, so 200 is the better start, and
  explicitly rejected copying the code "for symmetry". Symmetry is still not the argument. The argument is
  that the draft is not a blank rule — it *is* this row, in every field but one.
- Decision:
  - **The draft takes the row's observed status code**, through the callback shape Seed already used
    (`url, method, code, headers, body`). The two row actions now differ only in which panel they open and
    which draft they build.
  - **A row with no response yet falls back to 200**, the same fallback Seed takes: `code ?: 0` at the row,
    `takeIf { it > 0 } ?: 200` at the draft, so an exchange still in flight cannot author a rule that
    answers with 0.
  - **A rule added from the panel's own Add button is untouched.** `MapLocalRuleDef.statusCode` still
    defaults to 200, which is the right start for a rule authored from nothing rather than from traffic.
- Alternatives considered:
  - **Leave ADR-0045's split standing.** Rejected — that is what this ADR reverses. Its reasoning holds for
    a rule typed from scratch and stops holding the moment every neighbouring field on the form came from a
    captured exchange; a 200 sitting above a 404's payload is not a neutral default, it is a wrong one that
    reads as deliberate.
- Consequences:
  - Authoring a mapping from a failing row now reproduces the failure with no edits, and turning that row
    into a success costs one field — the reverse of the previous trade, and the direction that matches why
    a failing row gets right-clicked.
  - `onMapLocalFromUrl` grows the `Int` parameter, so both row callbacks are one shape.
  - ADR-0045's other decisions stand: Seed still opens the editor rather than committing, both actions
    still share `seededHeaders` and the raw bytes, and a row-carried body is still retired once saved.
  - Manual smoke: right-click a row that returned 404 → "Map Local…" → the editor opens at 404, with that
    row's headers and body. Do it on a 200 row → 200. Do it on a row still in flight → 200. Add a rule from
    the panel's Add button → 200.
