---
adr: 0045
title: A seed is authored from the traffic it will stand in for
date: "2026-08-06"
status: accepted
date_source: git-commit
---
# ADR-0045 — A seed is authored from the traffic it will stand in for

- Status: Accepted; implemented in `shared`. Extends ADR-0041.
- Context: A captured row could already become a Map Local rule or a breakpoint rule from its right-click menu, but a seed could only be authored by hand or imported from an existing Map Local rule. That left the most natural way to build one unreachable: you watch a request come back, decide you want that exact answer replayed into the next hold, and there is no way to say so from the row you are looking at. Retyping the URL, method, status, headers, and body by hand is both tedious and the easy way to author a seed that silently never matches.
- Decision:
  - **"Seed…" joins "Map Local…" and "Breakpoints…" on a captured row**, seeded with the same URL and method and opening the Seed panel on its editor — the identical seam, not a new one.
  - **It opens the editor rather than committing the seed**, unlike the Map Local panel's own "Seed…", which copies a finished rule and so has nothing to ask. A captured exchange is raw observation, and the reason to seed from one is usually to change something about it, so the editor is where the action lands.
  - **It carries the observed status code; Map Local does not.** A seed replays an exchange, so an observed 500 or 429 is often the entire point of capturing it; a mapping authors a new response, for which 200 is the better start. A row with no response falls back to 200.
  - **Headers and body come across through the same filter Map Local uses** (`seededHeaders` plus the raw response bytes), so the two row actions can't disagree about what "the captured response" means.
  - **A row-carried body is retired once saved.** The captured bytes only ever open the editor; after Save the stored body is authoritative, so reopening that seed in the still-open panel shows what was saved rather than replaying what the row carried.
- Alternatives considered:
  - **Commit the seed straight from the row, like the Map Local import:** rejected — it would append a seed that answers with the exact bytes just observed, which is rarely what is wanted and gives no moment to change the status or trim the body.
  - **Give Map Local the observed status too, for symmetry:** rejected — symmetry is not the goal; the two rules mean different things, and a mapping that silently starts at the captured 404 would be a worse default than 200.
  - **One "Seed…" that reuses the Map Local draft:** rejected — the drafts differ (a seed has no name, and now a different status default), and sharing one would make the panel that opens depend on hidden state.
- Consequences:
  - `SeedManager` gains `initialDraft`/`initialBodySeed` and a per-id body-seed map, matching `MapLocalManager`; `ResponseRuleEditor` already took a `bodySeed`, so nothing in the editor changed.
  - `WailoViewer` holds the seed draft alongside the other two, and clears it whenever the panel is reached any other way (the rail, or a Map Local import) so a stale draft can't hijack the panel.
  - Manual smoke: right-click a captured row with a JSON response → Seed panel opens on an editor pre-filled with that URL, method, status, headers, and body; Save → it appears in the seed list and fills into the breakpoint window. Do it on a row that returned 500 → the editor shows 500. Do it on an image response → the body tab previews the image. Edit the body, Save, reopen the seed from the list → the edited body is what shows. Right-click a row with no response → the editor opens at 200.
