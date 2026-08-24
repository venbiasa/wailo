---
adr: 0087
title: A rule matches one method, or any — and the wire's repeated field is only the fold target
date: "2026-08-24"
status: accepted
relations: narrows the authoring contract ADR-0081/0085 gave the daemon; leaves ADR-0072's two matchers untouched by folding at the wire edge
---
# ADR-0087 — A rule matches one method, or any

- Status: Accepted and implemented. Decided while auditing what each frontend can author (the same pass that decided a rule-order command and an explicit capture-filter switch).
- Context: a Map Local or breakpoint rule's HTTP method has three different arities depending on where you stand, and only the widest one can express more than a single verb:
  - Studio's editor is a single-select `DropdownMenu` whose options are `["", "GET", "POST", …]` with blank rendering as "Any"; the code says so outright — "A rule matches a single HTTP method" — and the value cannot be free-typed.
  - `shared`'s `MapLocalLayout` / `BreakpointLayout` and the `.wailorules` archive schema each hold one `method: String`. `Main.kt` comma-splits it on publish and re-joins on adoption, which is a bridge to the wider model, not an authoring feature — the picker can never produce a second entry.
  - The CLI takes one `--method`.
  - Only `HostMapLocalRule.methods: List<String>`, the daemon DTOs, and MCP's `methods` array can hold two.

  So a two-method rule is authorable through exactly one frontend and survives nowhere. Open it in Studio and the next save collapses it to whatever the dropdown resolves to; export and re-import it and the extra verbs are gone, because the archive field is scalar. Both losses are silent. The wider model bought one caller an expressiveness the rest of the system quietly revokes, which is worse than not offering it.
- Decision:
  - **A rule matches one method, or any.** Absent or blank means any — the shape Studio's picker has always had.
  - **The narrow shape is the authoring contract.** The host rule models, the daemon rule DTOs, and MCP's tool schema carry a scalar `method`. Nothing that a user or a tool writes can name two.
  - **The protobuf keeps `repeated string methods` and becomes purely a fold target.** One method folds to a one-element list, "any" to an empty one — the encoding the field's own comment already describes. Neither matcher changes, so ADR-0072's duplicated precedence logic (`HostProxyRules` and `sdk-android`, which cannot share code) is untouched by this: both keep their any-of check and simply never see a second entry. Narrowing the wire instead would mean a protobuf change reaching Wire's Swift codegen and both SDKs, to delete a case that no longer occurs.
  - **A repeated input collapses last-wins.** `--method GET --method POST` is POST, following the override convention a repeated scalar flag carries everywhere else (a wrapper script or alias supplying a default must be overridable by the caller). MCP keeps accepting a legacy `methods` array, takes its last entry, and reports that it did — an array is not an override, so the collapse is stated rather than assumed.
  - **Persisted rules with more than one method collapse on load, last-wins, once.** This is the rewrite Studio already performed at the next edit; doing it at load makes it one named migration instead of a surprise later.
- Alternatives considered:
  - **Widen the authoring surfaces to match the model** — a multi-select in the editor, a repeatable `--method`, keep MCP's array. This was the recommendation put to the reviewer and it was rejected: making the state real means changing the picker, the CLI, `shared`'s layout, *and* the archive schema, to deliver a capability nobody asked for. The single-select was a deliberate design, not an omission.
  - **Keep the list in the model and merely constrain what authoring can write.** Cheapest of all, and rejected because it leaves exactly what caused this: a state the system can represent and hold but no surface can produce or preserve. A model wider than every writer is where silent rewrites live.
  - **Reject more than one method instead of collapsing it.** Right for an API, wrong for a flag — a repeated CLI option that errors breaks the override idiom. The split kept is a documented last-wins collapse in both, made visible in MCP's response where there is a channel to say it.
- Consequences:
  - The daemon rule DTOs narrow, so the control protocol bumps again (14 → 15) — shared with the rule-order command (ADR-0089), which landed in the same pass.
  - The collapse has to happen where a persisted file is *read*, not only on the way to the host. A DTO left holding a blank scalar beside a legacy list is reported by a poll as "any method", so a rule that matched GET would start answering everything the moment a frontend redisplayed it. The legacy field stays nullable and unwritten (`explicitNulls = false` omits it) rather than being deleted, because a dropped key parses as nothing and widens the rule just as silently.
  - MCP's `list_map_local` / `get_map_local` / breakpoint output emits `method` where it emitted `methods`. That is breaking for any agent script that indexed the array, which is the one real cost here.
  - `HostMapLocalRule.serve` and the breakpoint match become an equality-or-blank check instead of an any-of scan.
  - The CLI's `--method` help text has to state last-wins; without it a repeated flag looks like it accumulates.
  - `shared`, the editor, and the archive change nothing — they were already the target shape. Studio's comma split/join in `Main.kt` goes away.
  - Manual smoke (two-tier verification): write a rule through MCP's legacy `methods` array with two verbs, confirm the response says it collapsed and to which. Open that rule in Studio — the dropdown shows that verb, not a joined string. Export, delete, re-import, and confirm the method survives. Point a request with the other verb at it and confirm it is no longer served.
