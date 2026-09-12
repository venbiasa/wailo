---
adr: 0098
title: A breakpoint rule is named, and its list is arranged by hand even though the order decides nothing
date: "2026-09-12"
status: accepted
relations: supersedes ADR-0042 (restores the drag handles it removed); completes ADR-0028's grouping for breakpoints; a name travels ADR-0085's authored path and ADR-0080's archive
---
# ADR-0098 — A breakpoint rule is named, and its list is arranged by hand

- Status: Accepted; implemented in `shared` (`BreakpointRuleDef.name`, `assignRuleToGroup`, `groups()`,
  the `BreakpointManager` row and editor, `ArchivedBreakpointRule.name`), `host`
  (`HostBreakpointRule.name`), `daemon` (`BreakpointRuleDto.name`), `desktopApp` (both directions of the
  layout seam), and the two headless frontends (`set_breakpoint --name`, MCP `name` + `list_breakpoints`).
- Context: two gaps, both of which read as "breakpoints are Map Local's sibling, except where they
  aren't".
  - **No name.** A Map Local rule has carried an author-facing label since it had a list; a breakpoint rule
    had only its URL pattern, so the row's title was a 90-character string. The tell was the previous
    change to this panel: truncating that URL *in the middle* to keep both ends readable. That tuned the
    truncation of the wrong field — two rules on one URL differing only in phase stayed indistinguishable
    at any ellipsis.
  - **No reachable group.** ADR-0028 gave breakpoints Map Local's grouping; ADR-0042 then took the drag
    handles away, because every matching rule pauses the exchange and ordering them changes nothing. But
    dragging was also the only gesture that *files* a rule into a group. Studio kept the button that
    creates a group and lost the move that fills one — so a breakpoint group could only be populated from
    the CLI or MCP, which have named a `group_id` since ADR-0081.
- Decision:
  - **A breakpoint rule carries a `name`,** defaulting to `Untitled`, required non-blank to save, and
    never part of matching — the same contract Map Local's has. It travels the whole authored path rather
    than living in the panel: host rule, daemon DTO, the persisted layout, and the export archive, so a
    name survives a restart (ADR-0085) and a hand-off (ADR-0080). The DTO field is *defaulted*, which
    keeps it additive: a daemon and a frontend built either side of this still talk, and a rule written
    before it loads with a blank name.
  - **The row leads with the name; the URL joins the caption** beside the method and the phases, truncated
    at the end. That is the Map Local row shape, and it is what makes the end ellipsis correct again: the
    line that identifies the rule is no longer the line being cut.
  - **A blank name adopted from the daemon falls back to the rule's id,** as Map Local's does. A rule
    authored headlessly may have no label, and its id is the handle its author chose there (`--id
    hold-checkout`) — better than a blank row, and better than inventing one from the pattern.
  - **The drag handles come back, reversing ADR-0042.** A breakpoint list reorders and groups exactly like
    Map Local's and Seed's. Its order still decides nothing about matching, and that stays true: what the
    drag buys is *arrangement* — related rules sitting together, and a rule joining a group by being
    dropped in it. ADR-0042 reasoned from "this control changes no behaviour" to "this control does
    nothing", and that step is the error: a list of twenty breakpoints is read far more often than it is
    matched against, and the arrangement is persisted daemon state, not a view preference, so it is worth
    the gesture. The cost it named — a user who infers a priority that isn't there — is smaller than a
    panel that is visibly the one sibling you cannot organize.
  - **A rule's group can also be set in its editor,** from a picker listing `None` and the existing
    groups, committed by Save with the rest of the rule. Drag is the better gesture when both ends are on
    screen and it is the only one that says *where* inside a group; the picker covers what it is worst at —
    a collapsed or far-scrolled group, and a rule authored from a traffic row that opens in the editor
    already. The move is a pure layout op, `assignRuleToGroup`: it appends at the destination's end,
    returns the layout untouched when the rule is already there, and refuses a group id nothing answers to.
- Alternatives considered:
  - **Keep ADR-0042's grip-free list and let the picker be the only way to group.** Rejected — this is the
    state being reversed. It answers the narrow question (order changes no behaviour) correctly and the
    wider one wrongly: the missing grips are read as a missing feature every time the panel is opened
    beside the two that have them.
  - **Give breakpoint order a meaning so the drag "earns" itself** — first match wins, later rules skipped.
    Still rejected, for ADR-0042's own reason: one URL can legitimately want a request-phase rule *and* a
    response-phase rule, and "only one breakpoint may fire" would silently drop the second.
  - **Drag only, no picker.** Rejected: filing into a collapsed group or one scrolled off the panel is
    exactly where a drag is worst, and a rule authored from a traffic row is already in the editor.
  - **Keep the URL as the row's title and add the name underneath.** Rejected: two long free-text lines
    stacked, and it keeps the row identified by the thing that does not distinguish two rules on one URL.
  - **Leave breakpoints nameless and keep tuning the truncation.** Rejected — the middle ellipsis was the
    symptom. A rule the author cannot label is a rule they have to re-read to recognize.
  - **Put the group picker in the shared response-rule editor so all three panels get one.** Deferred, not
    refused: `assignRuleToGroup` is generic over `LayoutRule`, so it is one call away if Map Local and Seed
    want the same shortcut into a distant group.
- Consequences:
  - Existing breakpoint rules read `Untitled` in the panel (or their id, when they came from the CLI or
    MCP) until renamed. Nothing about their matching changed, so nothing silently stops firing.
  - The three rule panels are shape-identical again — two-line rows, grips, groups, a Name field leading
    the editor — which is what stops "why can't I drag this one?" from being a question.
  - `GroupedRuleListPage.reorderable` now has no caller passing false. It stays, with its doc saying so:
    ADR-0042 observed that turning the grips off is one argument, and this decision is the proof that the
    switch is worth keeping — as is the nullable `handleModifier` plumbing under it.
  - The pre-ADR-0085 layout codecs in `shared` still encode no breakpoint name. They are unreachable from
    production code (the daemon holds the only copy of authored state), so they were left alone rather
    than extended; the name would be the fourth field a revived codec has to migrate.
  - A breakpoint editor is now a form that can fail two ways, so validation moved onto the fields: the
    name reports its own error, and the one sentence about a pattern and a phase stays for the mistake
    that spans both.
  - Manual smoke: name a rule and confirm the row leads with the name and cuts the URL at the end; clear
    the name and confirm Save is blocked with the field's own error. Reorder rules by drag, drag one into
    a group and back out, drag a whole group, and confirm collapse, rename, and the group switch still
    work. File a rule from the editor's picker instead and confirm it appends at the end of that group;
    save again without touching the picker and confirm it does not move. Restart Studio and confirm the
    name, the group, and the arrangement all came back from the daemon, then `wailo-cli list_breakpoints`
    and confirm the same rule reads there. Check the panel in both light and dark.
