---
adr: 0089
title: Rule order is set one container at a time, and membership is not part of it
date: "2026-08-24"
status: accepted
relations: gives ADR-0026's priority a headless writer; narrows what ADR-0081's whole-layout publish is needed for
---
# ADR-0089 — Rule order is set one container at a time

- Status: Accepted and implemented. Decided while auditing what each frontend can author.
- Context: order is meaning here — of two rules that both match, the higher one wins (ADR-0026), and the
  flattened top-to-bottom list is exactly what the engine and the devices consume. Studio can set it by
  dragging; the CLI and MCP could not set it at all. So a headless caller could author a fixture set and
  then not say which of two overlapping rules answers, and an agent asked to "make the narrow rule win"
  had no move available. The only existing mutation that could express order was `replace_map_local` —
  the whole layout at once — which a headless caller can only use by first reading the layout back, and
  which then overwrites anything another frontend changed in between.
- Decision:
  - **One command, `set_rule_order(family, group_id?, ids)`, scoped to a single container.** With
    `group_id` it orders the rules inside that group; without it, the top level — where an entry is a group
    or a loose rule, so an id there is a group id or a rule id.
  - **`ids` may be a prefix.** What it names moves to the front in the order given; everything else keeps
    its relative order behind that. Promoting one rule costs one id, and a full list is still exact.
  - **Membership is not expressible here.** A rule changes group through the upsert that files it
    (`--group-id`), which already existed. Order and placement stay separate operations so neither can be
    performed by accident while doing the other.
  - **An id the container does not hold is refused, and nothing is applied.** Including a rule id that
    lives in another container: ordering is per container, so that is a caller error, not a move request.
  - **Studio keeps publishing the whole layout.** A drag can reorder and re-parent in one gesture, and its
    republish is already cheap now that bodies travel separately (ADR-0086). This command exists for the
    frontends that have one id and an intent, not to replace the one that has the whole tree in hand.
- Alternatives considered:
  - **A whole-family flat list of rule ids**, with the daemon preserving membership. Rejected: it cannot
    move a group relative to a loose rule, and it forces a caller who wants one change to state every rule
    — the same read-modify-write race the whole-layout publish has.
  - **Move-one, `--before` / `--after` another id.** Rejected: it needs an anchor the caller must first
    look up, and n-1 calls to state a known order, each racing the last. The prefix rule gives the same
    single-rule ergonomics without an anchor.
  - **Let the headless frontends reorder by republishing the layout, as Studio does.** Rejected as the
    reason this ADR exists: it makes a one-rule change require reading and restating everything.
  - **Skip it and leave reordering to Studio.** Rejected under invariant #2 — a feature that only works
    with a window open is one the daemon does not really own.
- Consequences:
  - The control protocol bumps 14 → 15, so a frontend built with this restarts an older daemon on launch.
  - `reorder` sits with the other pure layout operations and is covered without a running daemon; the
    integration test only proves the wiring reaches it and that the host sees the new priority.
  - The daemon-less MCP backend refuses it, as it already refuses groups: order is daemon-owned layout, and
    a flat panel it cannot rearrange should say so rather than accept an order nothing applies.
  - Nothing touches the body store — order is layout, and bytes are held under a rule's id (ADR-0086), so
    re-prioritising a set of megabyte fixtures moves a handful of ids.
  - Manual smoke: with two overlapping Map Local rules, promote the narrow one with
    `set_rule_order --family map_local --ids <narrow>`, confirm Studio's list re-orders to match, and
    confirm a matching request now gets the narrow rule's response.
