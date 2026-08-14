---
adr: 0043
title: One pane, shared by holds and seeds — and an arriving hold takes it only when the user isn't already in one
date: "2026-08-06"
status: accepted
date_source: git-commit
---
# ADR-0043 — One pane, shared by holds and seeds — and an arriving hold takes it only when the user isn't already in one

- Status: Accepted; implemented in `shared` + `desktopApp`. Extends ADR-0041.
- Context: ADR-0041 gave the breakpoint window a left column of two lists — holds Waiting over the armed Seed queue — but only holds were selectable; seeds were display-only rows, so the one thing you could not check before a seed was spent was *what it would actually answer with*. Meanwhile the window had become user-owned, which introduced a question its old lifetime never had to answer: a hold can now arrive while the window is open but buried, or open and in front with the user mid-edit on a different hold. "Always jump to the newest" and "never move" are both wrong — the first throws away work, the second hides a device that is blocked and waiting.
- Decision:
  - **The right pane shows a selection, not a hold.** Selecting a seed previews it there: its match, the status and headers it answers with, and its stored body through the same previewer the traffic list uses, so an image or binary seed reads as itself. The preview is read-only with no actions — a seed is authored in the Seed panel and spent by traffic, not by anything in this window.
  - **A hold arriving raises the window only when the window is not focused.** In front already, it is not raised; there is nothing to raise it above.
  - **It takes the pane unless the pane already holds another paused exchange.** Buried window ⇒ raise and select it, because the user hasn't seen it. In front and showing a seed (or nothing) ⇒ select it, since nothing is lost. In front and showing a hold ⇒ leave it alone: that hold is mid-edit, and its edits are unsaved state that switching away destroys.
  - **Opening an already-open window brings it forward.** The panel's open button used to set a flag that was already true, so once the window was buried the button did nothing. The host keeps a raise counter instead, which both the button and the inspector's arrival rule bump.
  - **Selection is resolved against what still exists, every composition.** A resolved hold and a spent seed both vanish under the user; the pane falls back to the first hold still waiting, then to the empty state. That is one rule covering both list kinds instead of two effects racing to fix up a stale id.
- Alternatives considered:
  - **Always select the newest hold:** rejected — the common case for concurrent holds is editing one carefully while others queue, and this discards that edit on every arrival.
  - **Never move the selection; badge the new hold instead:** rejected — a buried window can't show a badge, and the device is blocked in the meantime.
  - **Raise the window on every arrival:** rejected — it steals focus from whatever the user is doing for a hold they may already be looking at.
  - **A separate seed-detail window or an expanding row:** rejected — the pane is already the window's "what's selected" surface, and two ways to look at one queue is the thing ADR-0041 avoided.
  - **Let the host decide the arrival policy:** rejected — the policy depends on what the pane is showing, which is the inspector's own state; splitting the decision across the boundary means duplicating that state.
- Consequences:
  - The inspector reads `LocalWindowInfo.isWindowFocused` inside the arrival effect rather than in composition, so focus changes don't recompose the window.
  - `WailoBreakpointWindowContent` takes `onLoadSeedBody` and `onBringToFront`; the host answers the first from `SeedStore` and the second by bumping the counter its window raises on.
  - Both lists share one row scaffold, so a seed and a hold read as the same kind of pick (accent bar + fill). Seed rows are two lines now — URL over `METHOD → status` — because one line left no room for the URL in a column that drags narrow.
  - Manual smoke: with the window open, click a seed → its headers and body show on the right, no buttons; spend it and the pane falls back. Bury the window behind the main one and trigger a hold → it comes forward with that hold selected. With the window in front and a hold being edited, trigger a second hold → the editor doesn't move. Repeat while a *seed* is selected → the new hold takes the pane. Bury the window and press the Breakpoints panel's open button → it comes forward.
