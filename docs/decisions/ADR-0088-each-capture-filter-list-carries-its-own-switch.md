---
adr: 0088
title: Each capture filter list carries its own switch, on every frontend
date: "2026-08-24"
status: accepted
relations: completes ADR-0082 for the headless frontends; same audit pass as ADR-0087 and ADR-0089
---
# ADR-0088 — Each capture filter list carries its own switch

- Status: Accepted and implemented. Decided while auditing what each frontend can author.
- Context: ADR-0082 made the Capture Filter's masters daemon state, and Studio has two independent
  switches — one per list — because "block these hosts" and "capture only these hosts" are separate
  intents. The headless frontends had no way to say either. Both the CLI's `set_capture_filter` and MCP's
  tool derived each switch from whether that call carried patterns, so:
  - Arming an empty list was unsayable. "Capture only what I list, and I will list it next" could not be
    expressed, though it is exactly what a script sets up before it knows the hosts.
  - Disarming a list without destroying it was unsayable too. Passing no patterns turned the list off *and*
    emptied it, so re-arming meant re-sending everything — which a caller that never read them cannot do.
  - Worst: a caller that meant to change one list silently disarmed the other, because omitting the
    blocklist's patterns read as "the blocklist is off".
- Decision:
  - **Each list's switch is an explicit input.** The CLI takes `--allow-on` / `--allow-off` and
    `--block-on` / `--block-off`; MCP takes `allowlist_enabled` / `blocklist_enabled`.
  - **Unset stays distinct from off, and keeps the old derivation.** With no switch named, a list is armed
    if the call carried patterns — so every existing script and agent prompt behaves exactly as before.
    This is the whole reason the flags are tri-state rather than boolean.
  - **A switch on its own preserves that list's patterns.** Toggling is not editing: `--allow-off` alone
    disarms the allowlist and leaves it intact, matching what Studio's checkbox does.
  - **An armed empty allowlist is allowed, and said out loud.** It captures nothing — the one state a
    caller is unlikely to have meant — so both frontends append a line saying so. Refusing it would make
    the frontends disagree with Studio, which can author it by unchecking every pattern.
- Alternatives considered:
  - **Derive the switch from patterns, as before.** Rejected: it conflates content with state, which is
    what made a one-list edit disarm the other.
  - **Make the switches plain booleans with a default.** Rejected: any default silently rewrites the other
    list for every caller that omits it. Tri-state is the only shape that leaves existing callers alone.
  - **Refuse an armed empty allowlist.** Rejected — it would be a state Studio can reach and a headless
    caller cannot, which is the class of asymmetry this audit exists to remove. Warned instead.
- Consequences:
  - No protocol change: `updateCaptureFilter` already carried both switches (ADR-0082). This is a
    frontend-input change only, which is why it shipped ahead of the protocol-bumping items in the pass.
  - Preserving a list means reading it, so both frontends now consult the filter the daemon last reported
    before writing. That is the polled copy, not a fresh read: a switch-only call can only race a pattern
    edit landing in the same instant, and paying a round trip on every call to close that window would cost
    more than it saves.
  - Omitting both a list's patterns and its switch still empties and disarms it, which is how "clear just
    the allowlist" stays expressible without a second verb.
  - Manual smoke: with patterns in both lists, run `set_capture_filter --allow-off` and confirm in Studio
    that the allowlist is unchecked, its patterns still listed, and the blocklist untouched.
