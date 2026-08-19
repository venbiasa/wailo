---
adr: 0063
title: Studio owns the menu bar item and outlives its own window
date: "2026-08-20"
status: superseded
relations: superseded by ADR-0065 (the item moved to the daemon's own companion process); amended ADR-0062; built on ADR-0058 (daemon ownership), ADR-0021 (Studio shell)
---
# ADR-0063 — Studio owns the menu bar item and outlives its own window

- Status: Superseded by ADR-0065. The reasoning about what the item must *show* still holds; what did not survive is the answer to who draws it. Its own first consequence — no item for a CLI-only or MCP-only session — was the motivating case, so the item moved to a companion process of the daemon. With it went the two concessions this ADR needed: a hidden Studio no longer holds a reference, and Studio's Quit no longer stops the daemon.
- Context: The daemon is invisible by design — it outlives its launcher, answers Studio, the CLI, and MCP alike (ADR-0058), and since ADR-0062 exits on its own once nothing refers to it. That left a real gap: with Studio closed and an MCP client attached, capture is running, tools are armed, and nothing on screen says so. The user asked for what Proxyman has, a menu bar item with Show Studio, Record Traffic, the listen address, a tools switch, and Quit. The open question was never the menu; it was which process draws it, because `engine`, `host`, and `daemon` are headless by invariant and the daemon is the thing the item represents.
- Decision:
  - **Studio draws the item; the daemon never does.** The tray lives in `desktopApp` as one more client of the daemon's loopback API. Putting Compose (or AWT tray code) in `daemon` would break the headless invariant this project keeps precisely so the daemon can run with no display, and would make the daemon the owner of a UI toolkit's event loop.
  - **The item represents daemon state, not window state.** Every row reads and writes through `DaemonClient` — capture on/off, the listen address, the tools gate — so the checkmarks match what the CLI and MCP see. Nothing in the menu is Studio-local.
  - **Closing the window hides it; the app keeps running.** The window's close request sets it invisible instead of ending the application, and the item (plus the macOS Dock reopen event) brings it back. Where the platform reports no system tray, close still quits: hiding the last window with no affordance to restore it is worse than the state it replaces.
  - **A hidden Studio still refers to the daemon.** ADR-0062 counts a held socket as a reference, and Studio keeps holding one while the item is up. A menu bar item whose Record Traffic checkbox tracked a daemon that had idled out from under it would be a lie, and the user would have no way to tell.
  - **Quit stops the daemon, even with MCP attached.** It is an explicit stop: it suppresses relaunch, so an attached agent's next Wailo call fails rather than quietly starting a fresh, empty daemon. The macOS quit handler (Cmd-Q and the app menu) is intercepted so it runs this path rather than the JVM's default exit.
- Alternatives considered:
  - **A separate tray process owned by the daemon:** rejected — it is the only design that shows an item with no Studio ever launched, but it needs a second binary, its own lifecycle, and an IPC path to raise Studio, all to avoid one client drawing a menu. Worth revisiting if the item is ever wanted for a pure MCP session.
  - **Tray code inside `daemon`:** rejected — breaks the headless invariant outright, and a daemon that must own an AWT event loop can no longer be the thing that runs anywhere.
  - **Keep close quitting, add the item only while a window is open:** rejected — an item that disappears with the window solves nothing; the invisible-daemon case is exactly the one with no window.
  - **Release the reference when the window hides:** rejected — the daemon could then exit under a live menu, whose state would silently stop matching anything. Quit is the explicit end; hiding is not.
- Consequences:
  - There is no menu bar item when only the CLI or an MCP client is using the daemon. The gap ADR-0062 opened is narrowed for people who use Studio, not closed.
  - A Studio left "closed" now holds the daemon up indefinitely, which is a deliberate reversal of ADR-0062 for this one frontend: the idle exit still governs every case where Studio is genuinely gone.
  - Quit is more destructive than a window close used to be — it takes the capture session and any attached agent's daemon with it. That is the point of having both affordances, but the copy has to say so.
  - macOS wants a monochrome template glyph in the menu bar while other platforms take a colored icon, so the item's image is derived from the Wailo mark per platform rather than shipped once.
