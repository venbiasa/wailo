---
adr: 0099
title: Scripts transform requests and responses in a daemon-owned sandbox
date: "2026-09-12"
status: accepted
relations: extends ADR-0033's rule precedence, ADR-0058's daemon ownership, ADR-0067's shared SDK/proxy interception, ADR-0080's archives, and ADR-0085's authored state
---
# ADR-0099 — Scripts transform requests and responses in a daemon-owned sandbox

- Status: Accepted.
- Context: a fixed response fixture cannot express changes such as removing one JSON field, deriving a
  value from the outgoing request, or applying the same small edit to many otherwise-real responses.
  Response-only scripting would leave the corresponding request use cases unsolved and would make the
  interception order differ from tools that expose both request and response hooks. Running scripts in
  an SDK or frontend would split behavior by capture path and make a window or AI client the owner of
  live traffic.
- Decision:
  - Wailo has one ordered, grouped **Scripts** rule family. Each rule carries a URL pattern, one method or
    any method, JavaScript source, and validator-derived `onRequest` / `onResponse` availability. Source,
    order, groups, and the feature master are daemon-owned authored state. Devices receive only match
    metadata and ask the daemon to execute a matching phase.
  - The fixed order is `Capture Filter decision → request Scripts → request breakpoint → Map
    Local/network → response Scripts → response breakpoint → response delay → caller`. Capture Filter
    keeps its pre-rewrite recording decision. Breakpoints and Map Local match the transformed request;
    response Scripts match the final outgoing request. A phase selects its matching scripts once, so a
    URL rewrite does not recruit another rule during that phase.
  - A script may define synchronous `onRequest`, `onResponse`, or both. It edits method, absolute HTTP(S)
    URL, duplicate-preserving headers, body, response status, and response delay. JSON bodies are mutable
    JSON values, valid UTF-8 text is a string, binary is a copied `Uint8Array`, and unsafe request streams
    are `unavailable`. An unavailable body may not be replaced, but its metadata may still change.
  - Each script runs as a transaction over a fresh copy of the last committed value. An exception,
    invalid field, mismatched body type, or unserializable return rolls back that script and continues.
    A worker timeout, crash, or transport failure restores the input to the entire phase. Only the final
    delivered request and response are captured; `edited` means that request, response, or delay changed.
  - GraalJS Community 25.2.4 runs in one replaceable child JVM, not in the daemon process. Guest code has
    no Java/host, filesystem, network, environment, thread, native, module, or asynchronous access and
    gets no persistent globals. The worker accepts at most 32 queued phases, caches at most 128 compiled
    sources, uses a 512 MiB heap, and limits a phase to one second and each script to one million
    statements. SDK RPC waits at most 1.5 seconds.
  - Source is capped at 64 KiB UTF-8, replaceable bodies at 8 MiB, and response delay at 60 seconds.
    Returned methods and header names must be HTTP tokens, URLs absolute HTTP(S), statuses 100–599, and
    header values free of CR, LF, and NUL. Streaming, oversized, unsupported-content-coded, upgraded,
    server-sent-event, and until-close proxy bodies stay byte-preserving when they cannot be safely
    materialized.
  - Runtime diagnostics retain only rule id, phase, timestamp, and a categorical issue code. They never
    retain exception text, stacks, headers, URLs, bodies, or returned values. Editing or removing the
    rule, a later success, or a daemon restart clears stale diagnostics. MCP returns one source-redaction
    marker when secret redaction is enabled.
- Alternatives considered:
  - **Response hooks only.** Rejected because request rewrites are the same interception primitive and
    adding them later would force a second ordering and transport migration.
  - **Execute on each SDK and in the proxy.** Rejected because three runtimes would disagree and the
    shipped SDKs would absorb a large scripting dependency.
  - **Execute in Studio.** Rejected because scripts must keep working for CLI, MCP, and proxy traffic
    with no window open.
  - **Run GraalJS inside the daemon.** Rejected because cancellation cannot safely stop arbitrary guest
    code; a replaceable process is the hard timeout boundary.
  - **Expose async APIs or host services.** Rejected because deterministic, bounded transforms need no
    external side effects and those capabilities turn a traffic rule into ambient code execution.
- Consequences:
  - SDK and proxy paths pay no execution cost when metadata proves no hook can match. A matching SDK call
    incurs one bounded daemon round trip per phase.
  - Request streams remain usable even when they cannot be transformed; body replacement explicitly
    reports whether the capture endpoint may swap bytes.
  - A hard worker failure intentionally loses successful edits from earlier scripts in that phase. This
    keeps a phase atomic and makes fail-open mean the caller receives its original input.
  - Script source is portable through rule archives and authorable in Studio, CLI, and MCP, but preview
    execution and traffic-derived autocomplete are deliberately absent.
