---
adr: 0066
title: The menu bar lists each daemon-owned master instead of a gate above them
date: "2026-08-20"
status: accepted
relations: supersedes ADR-0064 (the global tools gate, never shipped); restores ADR-0030 unamended; builds on ADR-0065 (the agent that draws the menu), ADR-0061 (daemon-owned configuration)
---
# ADR-0066 — The menu bar lists each daemon-owned master instead of a gate above them

- Status: Accepted; implemented in `menubar` (an `Enable Tools` submenu of `CheckboxMenuItem`s) over the `DaemonClient` calls that already existed. Removes `HeadlessHost.toolsEnabled`, the `toolsEnabled` daemon setting and poll field, `set_tools_enabled` from the RPC surface and the CLI, and the Settings row with the `featureArmable` plumbing under it. Control protocol bumped to 5. Verify with `cd studio && ./gradlew :host:test :daemon:test :cli:test`.
- Context: The menu bar item (ADR-0065) needs a way to disarm interception with no Studio open. ADR-0064 answered that with a new global gate above every feature's own master, and shipped a Settings row plus a four-level "disabled but remembered" hierarchy to explain it. The ask behind it was narrower than that: a menu that can turn each tool off. The masters that do exactly that already exist per feature (ADR-0030), already live on the daemon (ADR-0061), and are already non-destructive — the menu was simply not offering them.
- Decision:
  - **`Enable Tools` is a submenu of the existing masters, not a switch of its own.** One checked row per feature — Map Local, Breakpoints, the capture filter's allowlist and blocklist — each writing the same daemon command its panel writes. The menu adds reach, not state: there is nothing to persist, nothing to poll, and no new way for two frontends to disagree.
  - **A row exists only if the daemon owns the state.** Seeds are spent by Studio against a hold it is showing, so with no window there is nothing to enable; putting them in an item that stands for the daemon would claim otherwise. That is a rule about the whole menu, not a note about Seeds. *(ADR-0067 moved the seed library and its spend onto the daemon, so Seeds now satisfy this rule and have a row. The rule itself is unchanged.)*
  - **The capture filter appears as its two lists, because that is what the daemon holds.** Studio's single "Capture Filter" master is a Studio-local convenience above them; synthesising it here would have to guess which lists to re-arm on the way back, which is the destructive move ADR-0030 refuses.
  - **A list with no hosts is offered disabled.** An empty list cannot be armed (`CaptureFilterState`), so an enabled-looking row would flip back on the next poll and read as a bug.
  - **No global gate anywhere.** ADR-0030's rejection is restored as written; a future "turn everything off" ask is a menu that flips the rows it can see, and it needs an ADR of its own to explain why re-arming may lose the combination the user had.
- Alternatives considered:
  - **Keep ADR-0064's gate and add the submenu beneath it:** rejected — two levels of "off" with the same effect on traffic, where the top one silently disables the ones below. The gate's whole cost (a persisted field, a poll field, a protocol bump, a Settings row, and the `featureArmable` input threaded through every panel) buys a shortcut that saves at most three clicks in a menu.
  - **Synthesise a Capture Filter master in the menu (both lists off/on):** rejected — turning it back on arms lists the user had deliberately left off, which is precisely the state loss ADR-0030 exists to prevent.
  - **Give the daemon a real capture-filter master to mirror Studio's:** rejected for now — it is new daemon state to justify a label, and the two list switches already say the same thing without it.
  - **Show Seeds anyway, disabled, so the menu looks complete:** rejected — a permanently dead row teaches nothing; the honest signal is its absence.
- Consequences:
  - Removing `toolsEnabled` from `poll` bumps the control protocol, so an updated frontend replaces an older daemon rather than misreading it (ADR-0058). `HeadlessHost` no longer mirrors the requested capture filter — with nothing gating the push, the engine's filter *is* the authored one, so poll and the local MCP backend read it there again.
  - The menu is now the only surface that shows all four masters at once. Studio still shows each one in its own panel, which is where its context is.
  - A frontend that toggles a capture-filter list has to resend the other list, since the filter travels as one message. The menu reads the filter at click time rather than from the state it drew, so a host added elsewhere between polls is not dropped.
  - Manual smoke: with Studio closed, arm Map Local and a breakpoint from a CLI-started daemon, then turn each off from the menu and confirm the device falls through to the network and nothing pauses. Reopen Studio and confirm each panel's master reads exactly what the menu left, with every rule still listed. Add an allowlist host in Studio, then toggle the allowlist from the menu and confirm the panel follows and the hosts survive.
