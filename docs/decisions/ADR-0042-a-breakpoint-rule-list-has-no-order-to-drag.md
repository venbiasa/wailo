---
adr: 0042
title: A breakpoint rule list has no order to drag
date: "2026-08-06"
status: accepted
date_source: git-commit
---
# ADR-0042 — A breakpoint rule list has no order to drag

- Status: Accepted; implemented in `shared`. Amends ADR-0028.
- Context: ADR-0028 generalized Map Local's grouped, drag-orderable list so Breakpoints could reuse it, and Seed joined on the same stack (ADR-0041). But the three features consume their lists differently. Map Local and Seed resolve by **first match** — top-to-bottom order picks the winner, and for Seed it is also the sequence a run is scripted in. Breakpoints has no winner: every enabled rule whose pattern matches pauses the exchange, so the list is a set the order is drawn from, not a priority. Dragging a breakpoint rule therefore changed nothing observable, which is worse than no control at all — it invites the user to tune something that isn't there.
- Decision: `GroupedRuleListPage` gains `reorderable` (default true) that drops the drag handles from both the rule rows and the group headers; Breakpoints passes false. Everything else about the panel is unchanged: groups still organize rules and gate them off together, rules still add/edit/delete/toggle, and the saved layout is still an ordered list — nothing about the persisted format or the codec changes, so turning this back on is one argument.
- Alternatives considered:
  - **Keep the handles and let the order be meaningless:** rejected — that's the state this ADR is correcting.
  - **Give breakpoint order a meaning (e.g. first match wins, later rules ignored):** rejected — a URL can legitimately want a request-phase rule and a response-phase rule at once, and "only one breakpoint rule may fire" would silently drop the second.
  - **Drop groups from Breakpoints too:** rejected — grouping still earns its place there; toggling a whole set of breakpoints off in one switch is the common move (ADR-0026), and that has nothing to do with order.
- Consequences:
  - `handleModifier` on the shared row/header composables is nullable now, and null is what hides the grip; a row without one keeps the same leading gap, so a grouped rule's switch still lands under its group header's.
  - Breakpoint rows sit 24.dp further left than Map Local's and Seed's. That difference is the point — the panels no longer look identical because they no longer behave identically.
  - Manual smoke: open Breakpoints → no grips on rules or group headers, and nothing drags; add a group, drag-free reorder is impossible, but collapse/rename/delete and the group's switch all still work. Map Local and Seed still reorder.
