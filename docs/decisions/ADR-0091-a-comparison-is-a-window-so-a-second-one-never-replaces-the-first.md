---
adr: 0091
title: A comparison is a window, so a second one never replaces the first
date: "2026-08-25"
status: accepted
relations: supersedes ADR-0079's single pinned pair and the ticked menu item that ended it
---
# ADR-0091 — A comparison is a window, so a second one never replaces the first

- Status: Accepted and implemented in `shared` + `desktopApp`. Asked for directly, from use.
- Context: ADR-0079 held exactly one `(A, B)` pair in the host and gave it one window, and made the row
  menu item a toggle — ticked while that row was the B side, unticked to end the comparison. Both fall
  down the moment a second comparison is wanted, which is the ordinary case: you read two calls against
  each other, then want a third against a fourth, or the same row against a different one, and the second
  request silently took the first diff away. Worse, it *looked* like it had done nothing, because the
  window it replaced was usually the frontmost thing on screen. The tick had the mirror-image problem: it
  answered "is this row the B side?", which the window already answers, and it implied a toggle over state
  the user could not see once that window was buried behind another.
- Decision:
  - **The comparison owns the window, not the app.** The host holds a *list* of comparisons and gives each
    one its own window. Opening a comparison never touches another, so the diff that prompted the next
    question survives being asked it.
  - **A comparison's identity is its unordered pair.** A against B and B against A are one question asked
    from two sides, so they resolve to one window: re-issuing either raises the existing one rather than
    opening a mirror-image duplicate the user then has to close. That is also what lets Swap flip the sides
    *in place* — same key, same slot, so the window (and its size, position and scroll) is not torn down and
    rebuilt to show the same two rows the other way round.
  - **The menu item only ever opens or raises; it is never ticked and never ends anything.** Ending a
    comparison is its window's own close control, which is where ADR-0079 already put it for the panel's
    sake. A row may now be in several comparisons at once, so there is no single state for a tick to carry.
  - **The list marks participation, not sides.** `shared` gets `comparedIds: Set<String>` in place of the
    ordered pair. The 3dp bar never distinguished A from B (ADR-0079 — there is no room for a letter), and
    with several comparisons open "which side is this row on" has no answer to give.
  - **Windows cascade off the remembered origin, and only the one that opened at it writes geometry back.**
    A window that opens exactly over its predecessor is indistinguishable from having replaced it, which is
    the failure this ADR exists to fix, so a second one is offset — capped, so a long run stays on screen.
    Saving from an offset window instead would walk the stored origin down the screen one comparison at a
    time. With nothing stored yet the position stays `PlatformDefault`, where the OS cascades on its own.
  - **A comparison whose rows leave the capture is dropped**, as before — but the prune hands back the same
    list when nothing changed, because the live ids are recomputed on every poll and a fresh list each time
    would restate every window.
- Alternatives considered:
  - **One window with a tab strip (or a picker) over several comparisons:** rejected. The whole reason the
    diff is a window is that two monospace columns need the width and the height (ADR-0079); tabs put two
    comparisons in the space of one and bring back "which one am I looking at", which separate windows
    answer by construction.
  - **Keep one window, and make the second request replace it explicitly** (a confirmation, or a "replace /
    open new" choice): rejected. It asks the user to decide something the answer to is always "keep both",
    and it puts a prompt in the path of a two-click action.
  - **An ordered key, so B-against-A is a second comparison:** rejected. It is the same diff mirrored, and
    Swap already exists for reading it that way; the second window would be a duplicate to close.
  - **Geometry per comparison, keyed on the pair:** rejected. A capture id is unique per capture, so the
    store would accumulate dead keys forever to remember where a window that can never open again once sat.
    Geometry here belongs to the *kind* of window.
  - **A cap on how many comparisons can be open:** rejected. Nothing about the diff needs one, and the
    window list is the user's to manage; the cascade is capped instead, which is the only part that could
    actually go off-screen.
  - **Naming each window after its rows** (`Compare — GET /v6/cart`): deferred. It is the real fix for
    telling several identical "Compare" entries apart in the OS window menu, and it needs a rule for
    trimming a URL down to something that fits a title bar — worth doing on its own, not inside this change.
- Consequences:
  - `WailoApp`, `WailoViewer` and `TrafficList` take `comparedIds: Set<String>` + `onCompare:
    (Pair<String, String>) -> Unit` where they took `compareIds`/`onCompareChange`. Nothing reports the
    *end* of a comparison upward any more: the host learns it from the window closing.
  - `TrafficRow` loses `comparing`, and its compare action loses `checked` — the only remaining ticked
    actions are the ones that really are membership toggles (Bookmark, Allowlist, Blocklist).
  - New `desktopApp/OpenComparisons.kt`: `Comparison` (with the unordered `key`) and the four rules that
    move the list — open, close, swap in place, keep only live. They are pure and covered by
    `OpenComparisonsTest`; the window emission around them is manual-smoke territory.
  - `Main.kt` renders one `Window` per comparison inside `key(comparison.key)`, and the single
    `raiseCompareWindow` counter becomes one per comparison — raising the *right* window is the point.
  - ADR-0079's manual-smoke line "close the window, or untick Compare with selection" is superseded: there
    is no untick, and closing is the only way out.
  - Manual smoke: capture several calls to one endpoint. Select one, right-click another → "Compare with
    selection": the item must show **no** tick, in either state. With that window open, select a third row
    and compare it against a fourth: a second window must open, offset from the first, and the first must
    still be there showing its own pair. Right-click the same row pair again: no third window, and the
    existing one comes to the front. Swap one window's sides: its columns exchange and the window neither
    moves nor resizes. Confirm every row taking part in any open comparison carries the accent bar, and
    that closing one window clears only its own rows' bars. Move and resize the *first* window, close
    everything, and compare again: it returns to that geometry. Clear the capture with two windows open:
    both close.
