---
adr: 0092
title: Saving a Map Local rule or a seed keeps its editor open and reports the save, instead of dismissing it
date: "2026-09-01"
status: accepted
relations: keeps ADR-0023's undo history reachable across a save; applies to both features that share ADR-0028's editor; leaves ADR-0034's paused-traffic editor and the breakpoint rule form unchanged
---
# ADR-0092 — Saving a rule keeps its editor open and says so

- Status: Accepted and implemented in `ResponseRuleEditor` (a footer saved/unsaved verdict, no `onSaved`
  dismissal) with `MapLocalManager` and `SeedManager` no longer popping their editor page on save.
- Context: Save wrote the rule and then closed the page. That made the dismissal itself the "it worked"
  signal, which is tidy right up until you notice how these rules are actually used: a mapped response is
  saved in order to *try* it, and the try usually fails. Closing the page destroys the body editor's
  `CodeEditorState`, and with it the undo stack that ADR-0023 built — so walking back a five-second
  experiment meant reopening the rule and retyping the old body from memory, because the only copy of it
  had just been overwritten on disk and dropped from RAM in the same click. The affordance that makes an
  experiment cheap was being spent by the action that starts one.
- Decision:
  - **Save commits and stays.** The page persists after a save; `onBack` (the header arrow) and `onClose`
    are the ways out, which they already were. Nothing about *what* is written changes — the body-then-layout
    order of ADR-0086 is untouched.
  - **The footer says whether the form still matches the store.** Three states: nothing for a draft that
    has never been written, a green check + "Saved" when the two match, and "Unsaved changes" once they do
    not. A transient toast was the obvious replacement for the dismissal and is the wrong one: a toast
    answers "did that land" and then expires, while the question an undo raises — *is what I am looking at
    what is saved?* — is a standing one, and the answer has to still be on screen when it is asked.
  - **Save is disabled in the "Saved" state and enabled in the other two.** The verdict and the control say
    the same thing, so the state is legible whether the eye lands on the caption or on the button, and a
    click that would rewrite identical bytes is not offered. A caption is what makes this safe: a greyed-out
    Save with nothing beside it is indistinguishable from one that broke.
  - **The comparison is against the store, not against the session.** A rule already in the layout therefore
    opens in "Saved" with Save disabled — nothing has changed, so there is nothing to write. That forces the
    editor to be told [`persisted`]: a draft from the Add button or from a traffic row's "Map Local…"/"Seed…"
    has never been written, so it must open ready to commit even though the user has typed nothing. The two
    cases are indistinguishable from inside the editor, and getting it wrong strands a row-seeded rule
    behind a dead button.
  - **The body is compared by edit count, not by text.** Everything else on the form (name, pattern, method,
    status, headers, chosen image) is compared by value, but the body is a document the live JSON validation
    already refuses to read on every keystroke, and this is read on every one. So the baseline records
    `CodeEditorState.revision` and an undo back to the saved text still reads as unsaved. That is wrong in
    the only harmless direction: it costs a redundant save, never a lost one.
  - **`saving` is now released in a `finally`.** It never was, because dismissal made a stuck flag
    unobservable. On a page that outlives the save, a failed one has to hand the button back — and since a
    throw leaves the baseline unset, a failed save correctly leaves the button live rather than claiming
    bytes it never wrote.
- Alternatives considered:
  - **Keep dismissing and add an undo affordance to the rule list.** Rejected: undo would then have to mean
    "restore the previous body from disk", which needs a history the body store does not keep (ADR-0086 is
    one file per rule, not a log). The editor already has the history — the bug was throwing it away.
  - **A "Save and close" button beside "Save".** Rejected: two commit buttons to express one commit plus a
    navigation that the header arrow already performs.
  - **Leave Save permanently enabled and let the caption carry the state alone.** Rejected: a re-save is
    cheap, but an always-live commit button makes the caption the only place the state exists, and the
    button is the louder of the two. Disabling it is only defensible *because* the caption is there to say
    why, which is why the two shipped together.
  - **Put the dirty marker in the header next to the title,** as an editor tab's unsaved dot does. Rejected:
    the header already carries the rule's enabled switch, and a second small green thing next to it invites
    reading one as the other. Feedback also belongs where the click was.
  - **Extend this to the breakpoint rule form.** Rejected for now: it is four match fields with no body and
    no undo stack, so there is nothing to experiment with and nothing a dismissal destroys. The divergence
    is the point — this decision is about editors that own authored content.
- Consequences:
  - Leaving a rule now takes a deliberate Back or Close. Saving no longer navigates, so a user who saves and
    walks away leaves the panel on the editor rather than the list.
  - A rule abandoned mid-edit shows "Unsaved changes" until dismissed, and dismissing still discards
    silently — there is no are-you-sure, and this ADR does not add one.
  - The baseline for a rule that opens stored is recorded at the *end* of the body-seeding effect, not at
    first composition, because the body's revision is not settled until the bytes land. Until then the
    verdict answers from the opening condition, which is the same answer it settles on, so nothing flickers.
    Only the body is read at that point, though: the other fields are taken from the editor's parameters,
    because the seeding load is a host round-trip the user can type across, and reading the live fields
    afterwards files that edit as already stored — "Saved" over an unsaved change, behind a Save the edit
    itself just disabled. Deferred timing and live reads have to stay separate here.
  - The body's dirty check is an edit count, so a rule whose body is edited and undone keeps Save live. The
    approximation is deliberate and its error only ever offers a write, never withholds one — but that is
    load-bearing now that the button is gated on it, so a future exact check must not be *less* eager.
  - The editor's remembered state is scoped to the page: backing out and reopening the same rule
    re-establishes the baseline from the store, which is correct.
  - A wrong verdict is *silent* — the rule still saves, the caption just lies about it — so this is covered
    by `ResponseRuleSaveStateTest` rather than left to review. It caught the shipped-broken first cut, where
    the headers were compared in place: `SnapshotStateList` implements `MutableList` without `AbstractList`'s
    structural `equals`, so holding the live list up against a stored copy is an identity check that can
    never hold, and every rule opened pinned in "Unsaved changes" behind a Save that appeared to do nothing.
    Any future field added to the comparison needs the same treatment.
  - Manual smoke: open a stored Map Local rule and confirm it shows "Saved" with Save greyed out; edit the
    body and confirm both flip; Save and confirm the page stays, with the green "Saved" back and the button
    grey again; press Cmd+Z and confirm it goes live again with the old body restored; Save and confirm the
    list shows the reverted body. Then right-click a captured row → "Map Local…" and confirm the draft opens
    with Save *enabled* and no caption, since nothing has written it yet. Repeat on a seed, and once on an
    image-bodied rule (choose a file, Save, confirm "Saved", choose a different file, confirm it flips).
