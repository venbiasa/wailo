---
adr: 0098
title: A breakpoint rule is named, and its list is arranged by hand even though the order decides nothing
date: "2026-09-12"
status: accepted
relations: supersedes ADR-0042 (restores the drag handles it removed); completes ADR-0028's grouping for breakpoints; a name travels ADR-0085's authored path and ADR-0080's archive
---
# ADR-0098 — A breakpoint rule is named, and its list is arranged by hand

- Status: Accepted; implemented in `shared` (`BreakpointRuleDef.name`, the `BreakpointManager` row and
  editor, `ArchivedBreakpointRule.name`), `host` (`HostBreakpointRule.name`), `daemon`
  (`BreakpointRuleDto.name`), `desktopApp` (both directions of the layout seam), and the two headless
  frontends (`set_breakpoint --name`, MCP `name` + `list_breakpoints`). Amended the same day it landed:
  the editor's group picker was dropped, leaving drag as the one way a rule joins a group (see the
  alternative below), and the `assignRuleToGroup`/`groups()` layout ops it needed were removed with it.
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
    the CLI or MCP, which have named a `group_id` since ADR-0081. Whatever else the handles are worth,
    that alone made them load-bearing.
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
  - **Drag is the only way a rule joins a group,** so the editor has no group field. Restoring the handles
    already closes the gap the picker was added for, and a second control for one placement is worse than
    none: it cannot say *where* inside the group the rule lands, and two ways to move a rule means two
    places to look when one disagrees with the list.
- Alternatives considered:
  - **A group picker in the editor beside the drag.** Shipped, then removed the same day — the reason it
    was added (a group that is collapsed or scrolled far from the rule) is real but narrow, and it is
    reachable by expanding the group or scrolling, whereas a picker permanently puts a second, weaker
    control next to the gesture that does the job properly.
  - **Keep ADR-0042's grip-free list and reach groups only from a picker.** Rejected — this is the state
    being reversed. It answers the narrow question (order changes no behaviour) correctly and the wider
    one wrongly: the missing grips are read as a missing feature every time the panel is opened beside
    the two that have them.
  - **Give breakpoint order a meaning so the drag "earns" itself** — first match wins, later rules skipped.
    Still rejected, for ADR-0042's own reason: one URL can legitimately want a request-phase rule *and* a
    response-phase rule, and "only one breakpoint may fire" would silently drop the second.
  - **Keep the URL as the row's title and add the name underneath.** Rejected: two long free-text lines
    stacked, and it keeps the row identified by the thing that does not distinguish two rules on one URL.
  - **Leave breakpoints nameless and keep tuning the truncation.** Rejected — the middle ellipsis was the
    symptom. A rule the author cannot label is a rule they have to re-read to recognize.
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
    work. Restart Studio and confirm the name, the group, and the arrangement all came back from the
    daemon, then `wailo-cli list_breakpoints` and confirm the same rule reads there. Check the panel in
    both light and dark.
