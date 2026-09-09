---
adr: 0093
title: MCP reads are paged, compact, and bounded by default
date: "2026-09-08"
status: accepted
relations: extends ADR-0057's MCP tool surface, ADR-0069's captured-body handles, and ADR-0086's authored-body handles
---
# ADR-0093 — MCP reads are paged, compact, and bounded by default

- Status: Accepted and implemented in `studio/mcp`, with `:mcp:test` covering paging, body limits, cursor
  stability, compact status data, and the tool-manifest budget.
- Context: The MCP exposed focused tools, but several defaults still let one ordinary call consume a large
  part of an agent's context. Traffic lists returned 50 rows by default and up to 500, rule and hold lists
  returned every entry, detail reads allowed 1 MB from each body, and `status` repeated the full proxy
  state. The server also instructed every workflow to call `status` first even though every tool enforces
  the access gate itself. Cursor discovers individual schemas lazily, but other MCP clients may still load
  the complete 51-tool manifest.
- Decision:
  - **Status is optional and stays an overview.** Direct tool calls are supported. Its proxy section keeps
    only listener, traffic, reachability, certificate, and decryption-count fields; `proxy_status` owns the
    complete proxy state.
  - **Growing lists return 20 entries by default and at most 100.** Every page reports `total`, `returned`,
    `has_more`, and the argument for the next page. Rule, hold, device, group, and proxy-target lists use an
    offset because their order is the state being inspected.
  - **Traffic pages use a stable exchange cursor.** `next_before_id` feeds the next call's `before_id`.
    Offset paging was rejected here because new exchanges arriving between calls would shift the offset,
    duplicate rows already paid for, and skip others.
  - **Rule lists carry match metadata, not large values.** Map Local and seed lists omit headers and bodies;
    their focused `get_*` tools return both. Rule groups remain available through `list_rule_groups` rather
    than being repeated on every rule page.
  - **Body previews default to 1 KiB and share a 64 KiB raw-body budget per result.** A one-body result can
    use the full budget; multi-body details and hold pages divide it across the bodies they return.
    Truncation remains explicit. MCP is for bounded inspection; showing an arbitrarily large payload
    cheaply is impossible, and Studio remains the surface for full large-body work.
  - **Text and structured content remain mirrored.** The MCP specification recommends that compatibility
    shape for clients that ignore `structuredContent`; removing one copy would save wire bytes but make
    traffic disappear for those clients. A protocol test instead caps the serialized tool manifest at
    64 KiB.
- Alternatives considered:
  - **Collapse the 51 tools into a few generic action tools:** rejected. It moves the same schemas behind
    enums, weakens tool selection and mutation annotations, and brings little benefit to clients that
    discover one schema on demand.
  - **Expose configurable tool profiles:** deferred. They reduce full-manifest cost but make capabilities
    depend on launch configuration; paging bounds the result cost without hiding operations.
  - **Return only structured content:** rejected for compatibility with clients that consume text content.
  - **Keep large explicit limits and optimize only defaults:** rejected. A mistaken argument could still
    inject megabytes into one model turn.
- Consequences:
  - Clients that need more than one page must follow `next_offset` or `next_before_id`.
  - Clients that previously expected every rule, its headers, and all group metadata from one list call
    must use paging plus the focused detail/group tools.
  - Bodies larger than the call's share of 64 KiB cannot be fully returned through that response. The
    response reports `body_bytes_per_body` and says when data was truncated rather than silently presenting
    a complete-looking prefix.
  - The full manifest remains broad, but result size now scales with an explicit page or body budget rather
    than daemon history.
