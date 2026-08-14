---
adr: 0047
title: Connect is one list of desktops, and Forget is how one stops being reached
date: "2026-08-07"
status: accepted
date_source: git-commit
---
# ADR-0047 — Connect is one list of desktops, and Forget is how one stops being reached

- Status: Accepted; implemented in `sdk-ios` (`WailoDebugUI`). Amends ADR-0040.
- Context: The panel had grown two lists that were really one. "Found on the network" sat in its own section well below the address fields, and the paired desktops sat under Wi-Fi pairing — so the same machine could appear twice, in two places, described two different ways, and the panel invented a question ("which of these is mine?") that the user did not have. It was also the wrong shape for what tapping a row *does*: since ADR-0040 a discovered row fills the address field rather than connecting, which makes it a suggestion for the field it is nowhere near. Meanwhile a remembered desktop could only be forgotten from Studio — the wrong machine, and unavailable exactly when it matters, since a desktop that has moved networks is one the device will keep reaching for and the user has no way to say no to.
- Decision:
  - **One list, directly under the Connect button, merged on identity.** A desktop advertising on the network and a desktop this device holds a key for are the same machine as far as choosing an address goes, so they are one row keyed on `studioId`. It sits under the button because a row's only job is to fill the fields above it.
  - **Desktops that are not on the network stay in the list, dimmed.** Hiding them would mean Bonjour reconnecting to something with no way to say no — the very thing Forget is for. Dimmed rather than hidden: still identifiable, visibly not something Connect can reach right now.
  - **Forget lives on the device, next to the desktop it forgets.** It is the off switch for automatic reconnection, which is a decision about *this* device and has to be reachable when the other machine is not.
  - **Forgetting a desktop lets go of its address too.** A pinned address outranks everything in `apply`, and a first contact at an address the user named is taken at its word (ADR-0040) — so dropping only the key would re-trust the same desktop on the next dial two seconds later, and Forget would read as a button that does nothing.
  - **Fill and Forget are siblings, not a button inside a tappable row.** SwiftUI does not reliably route a tap to a nested `Button`, and getting Forget when you meant Fill is the one mistake here that costs something. Fill takes the whole remaining width so it stays the easy target.
  - **Wi-Fi pairing keeps only the ceremony.** With the list gone it is the QR/code form and the sentence explaining when strict mode needs it — which is what it was always about.
- Alternatives considered:
  - **Keep the two lists and cross-link them:** rejected — it preserves the duplicate row and adds a concept to explain it.
  - **Drop offline desktops from the list and offer only "Forget all":** rejected — the desktop most worth forgetting is the one that is not here, and an all-or-nothing button is unusable for someone who works across two networks.
  - **Make a row connect directly, now that Forget makes a mistake recoverable:** rejected — recoverable is not the bar. A first contact pins whatever answers, and ADR-0040 chose deliberately that this never happens from a stray tap.
  - **Show only paired desktops and let discovery stay invisible:** rejected — the first contact has to start somewhere, and typing an IP off another machine's screen is the thing discovery exists to avoid.
- Consequences:
  - `WailoDebugModel.Desktop` is the merged view — advertising rows first (in discovery order), then remembered-but-offline sorted by name — and carries what the row shows: fingerprint, how it came to be trusted, and whether Studio has since refused it. `discovered` and `pairings` stay as inputs and are no longer rendered anywhere directly.
  - A remembered desktop that is offline has a last host but no port, so Fill leaves the port field empty and the default stands rather than reinstating a port that may have moved.
  - `forget(studioId:)` and `forgetAllPairings` now unpin the manual address when it matches, so Forget is a single act with one meaning wherever it is pressed.
  - Manual smoke: with Studio running, open the panel → one row per desktop, none duplicated. Tap it → the fields above fill and nothing connects. Connect → trusted on first contact, and the row now shows a fingerprint and how it was trusted. Quit Studio → the row stays, dimmed, with Forget still offered. Press Forget → the row disappears if it was offline, the address fields stop being pinned, and Bonjour does not bring the connection back on its own; press Connect again → it is a first contact again. Smoke in both light and dark (ADR-0016).
