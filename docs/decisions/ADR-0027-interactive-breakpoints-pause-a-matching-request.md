---
adr: 0027
title: Interactive breakpoints — pause a matching request/response on-device, edit it live on the desktop, resume-or-abort (iOS-first; realizes ADR-0019's anticipated extension)
date: "2026-07-24"
status: accepted
date_source: git-commit
---
# ADR-0027 — Interactive breakpoints — pause a matching request/response on-device, edit it live on the desktop, resume-or-abort (iOS-first; realizes ADR-0019's anticipated extension)

- Status: Accepted; building. `sdk-ios` `swift test` green (24 tests): `WailoBreakpointTests` covers store
  matching (enabled + at-least-one-phase, method filter, wildcard), request-phase abort → `URLError.cancelled`,
  `.proceed(nil)` fail-open onto the real network, and Map Local precedence; `WailoClientLoopbackTests` adds the
  `BreakpointRules`→`BreakpointRulesAck` anti-entropy round-trip, a `BreakpointHit`→`BreakpointDecision`
  resolution carrying an edited request, and disconnect fail-open (`.proceed(nil)`). Engine
  `WailoEngineBreakpointTest` added (rule push/ack, hit → `pausedExchanges`, resume/abort routed to the origin
  session, drop-on-disconnect); studio side verified via `ReadLints` + the hot-reload loop per the sandbox rule
  (studio/`gradlew` is unreliable inside the agent sandbox). Android deferred.
- Context: Map Local (ADR-0019) mocks a matched response *automatically* — no human in the loop. Breakpoints are
  the human-in-the-loop counterpart the reference proxy tools call "breakpoints": pause a
  matching request *before it is sent* and/or its response *before the app sees it*, let a person inspect and
  edit it live, then resume (optionally with edits) or abort. ADR-0019 explicitly built its bidirectional
  correlation/pending-map channel anticipating this ("the same correlation/pending-map machinery breakpoints
  will need — built once here"); this ADR realizes it. Three forces shaped the design: (1) the engine must stay
  headless (invariant #2) — it cannot block on a UI decision, so paused state has to be a seam the UI drives;
  (2) the SDK ships inside third-party apps (invariant #3) and must never *hang* an app's call on a desktop that
  has gone away; (3) consistency across the two SDKs is kept only by the wire contract (invariant #4), so the
  whole model must be expressible in `protocol`.
- Decision:
  - **Rule-based, matched on-device like Map Local** (not a global "pause everything" toggle — matches the
    reference tools and reuses ADR-0019's mental model). A `BreakpointRule` = `{id, enabled, url_pattern,
    methods[], on_request, on_response}`; a rule with neither phase set never fires. The match decision stays
    local and instant (no per-request desktop round-trip), exactly as ADR-0019 argued for Map Local.
  - **Four wire messages on the existing `Envelope` oneof (ADR-0008), fields 9–12:** `BreakpointRules{rules[],
    epoch}` + `BreakpointRulesAck{epoch}` (desktop→device snapshot + ack — the *identical* anti-entropy as
    `RuleSet`/`RuleAck`); `BreakpointHit{correlation_id, rule_id, phase, HttpRequest, HttpResponse}`
    (device→desktop: a matched exchange is paused — `request` always set, `response` only for the RESPONSE
    phase); `BreakpointDecision{correlation_id, action, edited_request, edited_response}` (desktop→device:
    resume-with-optional-edits or abort). `enum BreakpointPhase{REQUEST=0, RESPONSE=1}` and
    `enum BreakpointAction{PROCEED=0, ABORT=1}` — PROCEED is 0 so a malformed/empty decision fails **safe** (the
    call continues) rather than aborting the app's request.
  - **Indefinite hold while connected; fail-open only on disconnect.** This is the one deliberate deviation from
    Map Local's 10 s `bodyTimeout` (ADR-0019): a human is editing, so there is **no** timeout. The app's
    `URLProtocol` load is held open (non-blocking) until a `BreakpointDecision` arrives or the link drops. On
    disconnect the device drains every held request/response as `.proceed(nil)` — it makes its own real network
    call with the *original* message — so a desktop that quits or crashes can never wedge the host app. Same
    "we are not a proxy; the SDK always owns the real request" stance as ADR-0019's fail-open.
  - **Abort = fail the app's call with `URLError(.cancelled)`** device-side; the mirrored exchange is flagged
    `edited` so the desktop list marks it intercepted rather than a genuine network failure.
  - **Precedence v1: a Map Local match short-circuits and is never broken.** Breakpoints apply only on the
    real-network path; a URL matching both is served by Map Local and never handed to the gate. This sidesteps
    the request-edit-then-rematch tangle (would an edited request re-run Map Local matching?) — documented and
    revisitable.
  - **Engine stays headless via a paused-state seam.** `WailoEngine` exposes
    `pausedExchanges: StateFlow<List<PausedExchange>>` and mirrors the Map Local push path
    (`updateBreakpointRules` + `pushBreakpointRules(session)` + `SessionState.ackedBreakpointEpoch` +
    `reconcile()` retry). On a `breakpoint_hit` it appends a `PausedExchange` and records which session owns each
    `correlation_id` (a `ConcurrentHashMap`); `resumeBreakpoint(id, editedRequest?, editedResponse?)` /
    `abortBreakpoint(id)` send the `BreakpointDecision` on the owning session and drop the row; on session close
    it drops that session's rows (unresolvable once the device is gone). The engine never blocks — the decision
    comes later from the UI.
  - **iOS device (`sdk-ios`):** `WailoBreakpointStore` caches the pushed rules and answers
    `match(url, method) -> (ruleId, onRequest, onResponse)?` (dropped on disconnect, like `WailoRuleStore`).
    `WailoClient` handles inbound `breakpoint_rules` (replace + ack) and `breakpoint_decision` (resolve pending)
    and *is* the `WailoBreakpointGate` — a pending map keyed by a client-minted `correlation_id`, **no timeout
    timer**, drained `.proceed(nil)` on disconnect. `WailoURLProtocol` pauses the request phase in
    `startLoading` (after the Map Local check) and the response phase in `finish` (before forwarding to the
    client), applying edits, aborting via `didFailWithError`, or falling open.
  - **Desktop UI:** a third docked tool panel `BreakpointManager` (rules CRUD, mutually exclusive with Map
    Local / Capture Allowlist per ADR-0021) plus a modal `BreakpointEditor` overlay shown when `pausedExchanges`
    is non-empty — `UnderlineTabs` (Headers/Body) over the editable `CodeEditor` (ADR-0023) + editable
    method/URL (request) or status (response) + Resume/Abort. **One paused item is shown at a time**; concurrent
    hits queue behind it. Rules persist host-side (`BreakpointStore`, primitive KV with Base64 fields like
    ADR-0019/0026), the host pushes on change via `LaunchedEffect`, and paused rows are bridged engine→`shared`
    at the module boundary (a `PausedFlow`, like `FlowEntry`) so `shared` never depends on `engine` (ADR-0013).
- Alternatives considered:
  - **A global pause toggle** (break everything): rejected — noisy and unlike the reference tools; rule-based
    reuses Map Local's matching and mental model.
  - **A short hold timeout** (like Map Local's 10 s): rejected — a human is deciding; a timeout would abort
    mid-edit. Fail-open is reserved for the disconnect (no-authority) case only.
  - **Break Map Local matches too (v1):** rejected for the edit-then-rematch ambiguity; deferred behind the
    documented precedence.
  - **Queue *and* show all concurrent hits at once:** rejected for v1 — one-at-a-time keeps the editor
    unambiguous; extra hits wait behind the current one.
- Consequences:
  - The bidirectional control channel ADR-0019 built now carries a second RPC (hit/decision) with **no new
    transport machinery** — the correlation/pending-map pattern paid off exactly as predicted.
  - A held call depends on the desktop staying connected; the instant it is not, the call proceeds with its
    original bytes — never hangs. Paused state is not persisted across a disconnect (consistent with
    ADR-0003/0025's no-backlog stance).
  - **Android does not break** (it still ignores inbound frames, ADR-0019); when it does it must mirror this
    model over the same wire contract (metadata cache, on-device match, indefinite-hold/fail-open, ack), since
    only the contract keeps the SDKs aligned (invariant #4).
  - Added an `ic_breakpoint` mono vector drawable (tint-driven, light/dark-safe per ADR-0016).
  - Manual smoke (two-tier verification): with desktop + `sample-ios` connected, add a request breakpoint and
    trigger traffic → the editor opens paused; edit URL/headers/body and Resume → the app receives the edited
    request; add a response breakpoint, edit status/body and Resume → the app sees the edited response; Abort →
    the app's call fails (`.cancelled`); with a hit paused, quit the desktop → the app's call completes against
    the real network (fail-open). Smoke the panel + editor in both light and dark.
