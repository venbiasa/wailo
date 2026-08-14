---
adr: 0044
title: Fill answers the holds already waiting
date: "2026-08-06"
status: accepted
date_source: git-commit
---
# ADR-0044 — Fill answers the holds already waiting

- Status: Accepted; implemented in `desktopApp`. Amends ADR-0041's "matching happens on a hold's arrival, once".
- Context: ADR-0041 evaluated seeds only when a hold arrived, reasoning that a hold already sitting in the editor is in the user's hands and the queue must not reach in and answer it behind their back. In use that reasoning turns out to cover the wrong case. The ordinary way to reach for seeds is *reactive*: traffic pauses, you look at what's waiting, and only then do you arm the queue for it. Under arrival-only matching, Fill at that moment does nothing visible — the hold you filled *for* keeps waiting, and the only way out is to release it and re-trigger the traffic, which for a retry or a poll sequence means restarting the scenario. The queue is right there, matching, and declines to help.
- Decision:
  - **Fill arms the queue and then sweeps the waiting holds**, answering each response-phase hold that a seed matches and spending that seed, in queue order — so two waiting holds for one URL take seeds 1 and 2 exactly as two successive requests would.
  - **"On arrival, once" still governs the automatic path.** Nothing else re-runs matching: a seed being spent, a rule being edited in the Seed panel, or a hold resolving all leave the other holds alone. The distinction that makes both rules coherent is *who asked* — automatic matching stays conservative because the user didn't ask for it at that moment, while Fill is a named button the user pressed while looking at the queue it will spend.
  - **The sweep does not spare the hold open in the editor.** Consistency beats caution here: a Fill that skipped whatever happened to be selected would answer a different set of holds depending on where the user last clicked, which is impossible to predict or explain. Any in-progress edits to that hold are lost, which is the same thing that happens to them on Resume.
  - **Both paths spend through one function** (`spendSeedOn`), so triage and Fill can't drift on what a seed is allowed to answer — the request-phase exclusion and the missing-body-file fallback are stated once.
- Alternatives considered:
  - **Re-match on every queue change:** rejected again, and for the original reason — it makes answering a hold an implicit side effect of unrelated edits.
  - **A separate "Apply to waiting" button next to Fill:** rejected — two buttons for what is one intention, and the second would be the one you always want after the first.
  - **Sweep, but skip the selected hold:** rejected — see above; a control whose effect depends on the current selection isn't predictable.
  - **Ask before answering the holds:** rejected — a confirmation on every Fill to protect a case the user just asked for.
- Consequences:
  - Fill is no longer instantaneous: it reads a body file per answered hold, so it runs in a coroutine and re-reads the queue between holds rather than folding a local copy — otherwise a hold arriving mid-sweep could be triaged against a queue the sweep has already spent from, and the sweep's final write would resurrect the spent seed.
  - A Fill with nothing waiting behaves exactly as before, which is still the common case when arming ahead of the traffic.
  - Manual smoke: trigger a response-phase hold and leave it waiting, *then* Fill with a matching seed armed → the hold resolves and the seed leaves the queue, with no further traffic needed. Do it with two holds on one URL and two matching seeds → each takes its own, in order. Do it with a request-phase hold waiting → it keeps waiting and no seed is spent. Fill with nothing waiting → the queue just arms.
