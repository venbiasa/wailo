---
adr: 0085
title: The daemon holds the only copy of authored rule state; no frontend keeps a mirror
date: "2026-08-24"
status: accepted
relations: completes ADR-0061/0081/0082's ownership moves by deleting the local copies they left behind; supersedes ADR-0061's Studio-side re-seed; recovery from an empty state directory falls to ADR-0080's archives
---
# ADR-0085 — The daemon holds the only copy of authored rule state; no frontend keeps a mirror

- Status: Accepted. The capture filter and breakpoint mirrors are gone and Studio renders both straight from the daemon's flows; the Map Local and seed mirrors, which additionally hold body bytes, follow in a second change. `cd studio && ./gradlew :daemon:test :cli:test :mcp:test :desktopApp:test` is green.
- Context: ADR-0061, ADR-0081 and ADR-0082 each moved a piece of authored state onto the daemon, and each left Studio's `KeyValueStore` copy in place for one stated job: re-seeding a daemon that genuinely has nothing. That left the same truth stored twice, and every launch had to decide which copy won. All four families answered that question the same way — *does the daemon's list look empty?* — and all four were wrong in the same way, because emptiness is a value a user can author. A rule set deliberately cleared through the CLI or MCP is indistinguishable from a daemon that has never been written, so Studio republished its own copy over the clear on the next launch, dragging the stored feature master along with it. The capture filter's version of this was reported as "capture filter always disabled when restart the app".
  The first fix attempt was to have the daemon answer the question honestly: a `captureFilterAuthored` flag on the poll saying whether anything had ever written the filter. It worked, and it would have needed replicating three more times. It also made the shape of the problem obvious — the flag exists only to arbitrate between two copies, so the arbitration disappears if the second copy does. The audit that found this counted thirteen stores in `desktopApp` and zero in `cli` and `mcp`: the two frontends with no local state were the two that had never had this bug.
- Decision:
  - **One copy. The daemon's.** Studio derives its UI state from `DaemonClient`'s flows and writes every change straight through. There is no local snapshot, no signature to compare against, no adopt-or-republish effect, and no launch-time reconciliation — the whole category of decision is removed rather than made correctly.
  - **A change is one write to the owner.** Every edit in these panels is a discrete act: a reorder commits once on drop, a toggle is one click, a host is added whole. None is a keystroke, so a round-trip per change is not in the way of typing, which is what made the local copy feel necessary.
  - **`captureFilterAuthored` is removed rather than extended.** It was correct and it was answering a question that should not be asked.
  - **Recovery from a wiped state directory belongs to ADR-0080's archives.** That is the only situation the mirrors ever helped with, and export/import already covers it deliberately — with the user choosing when, and with bodies included — instead of implicitly on every launch.
- Alternatives considered:
  - **Give the other three families the authored flag too:** the smallest change that fixes the reported bug, and it was written and working for the capture filter before this ADR replaced it. Rejected because it pays four times over for the privilege of keeping a duplicate whose only job is a case archives already handle, and because each flag is one more thing that can lag the state it describes.
  - **Keep the mirrors but make the daemon always win:** removes the bug and keeps the prefs write. Rejected as the worst of both — the second copy still exists, still needs migrating and sweeping, and now demonstrably does nothing.
  - **Keep a local copy purely as an offline cache** so panels render before the first poll: rejected because `DaemonClient.connect` already awaits readiness, so composition never runs against an unpopulated client.
- Consequences:
  - `CaptureFilterStore` and `BreakpointStore` are deleted, as are `PortStore`, `UsbPortStore`, `MaxRetainedStore` and `RequirePairingStore` — four stores that were written and never read at all, whose settings the daemon has persisted in `daemon.properties` since ADR-0058.
  - What stays local is presentation that a headless session could not act on: window geometry, panel width, theme, and text scale. That is the line — if a frontend with no window could use it, it is daemon state.
  - Rules authored in the previous build are already on the daemon, which has been the writer since ADR-0061/0081, so nothing authored is lost. The orphaned preference keys are swept on launch.
  - A daemon and a Studio built either side of this change disagree about the poll shape; the protocol bump to 12 in ADR-0084 covers both.
  - Manual smoke (two-tier verification): clear the block list with `wailo-cli set_capture_filter` while Studio is closed, then open Studio — the list stays cleared instead of being repopulated. Turn the capture filter's master off, quit, reopen — still off. Delete a breakpoint rule from the CLI and watch it disappear from an open panel on the next poll.
