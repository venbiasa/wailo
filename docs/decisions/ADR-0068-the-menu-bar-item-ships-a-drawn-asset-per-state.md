---
adr: 0068
title: The menu bar item ships a drawn asset per state instead of deriving one from the app mark
date: "2026-08-21"
status: accepted
relations: builds on ADR-0065 (the agent that draws the item); replaces the runtime derivation its `TrayIcon` shipped with
---
# ADR-0068 — The menu bar item ships a drawn asset per state instead of deriving one from the app mark

- Status: Accepted; implemented in `menubar`. `TrayIcon` is a resource loader over two 40px template PNGs in the module's own resources, and `TrayMenu` swaps them on `MenuState.recording` (`listening && capturing`). The ~130 lines of masking, cropping and compositing are gone. Verify by eye: the item is a hollow mark while paused and a filled tile while recording, in both a light and a dark menu bar.
- Context: ADR-0065's agent derived its glyph at runtime from the desktop app's 1024² mark, so the two could not drift — the build copies the mark in and the code thresholds it into a template silhouette. Giving the item a second look (filled while recording, so an active capture is visible at a glance) put two limits of that approach in plain view. The cost is real: measured on a dev machine, decoding the mark takes 79 ms and deriving both states another 80 ms, and the decoded mark is 4 MiB held for the process's life — in an agent that is deliberately AWT-only so it can be resident whenever a daemon is. It is also paid far more often than once per session, because any frontend may supply the agent and the loser of that race exits: one machine's agent log held 380 spawns for 9 items actually drawn. The ceiling is worse than the cost. The mark's stroke is about 4% of its glyph's width, so at the ~20pt a menu bar reports it lands under one device pixel, and no threshold or margin constant can widen it. Deriving from a 1024² line drawing can only ever shrink it.
- Decision:
  - **Two authored assets, one per state, in `menubar`'s own resources.** `TrayIcon.load()` reads them and returns both; nothing derives, measures, or composites at runtime.
  - **Authored at 2x — 40px for the 20pt a bar reports — as pure alpha over black.** A Retina bar maps that one-for-one and a 1x display gets a clean halving. Black-with-alpha is what the template flag needs: the system owns the colour, because the bar goes dark over a dark wallpaper while the OS is still in light mode.
  - **Recording is the mark punched out of a filled tile.** Weight is the only channel a monochrome item has, and a hairline has none to give.
  - **Windows and Linux keep the app mark itself, one look for both states.** Those trays show the icon in its own colours, so there is no tint for a hole to reveal and nothing to invert against. The build still copies the mark in for them.
  - **Drift is accepted and named, not mitigated.** A new app mark means redrawing these two by hand. That is the price of a glyph drawn for the size it is displayed at.
- Alternatives considered:
  - **Keep deriving and add the inverted state in code** (what shipped first): rejected on the measurements above, and because it cannot fix the hairline — the thinness is the mark's geometry, not the renderer's tuning.
  - **Derive at build time into the resources, via a Gradle task:** rejected. It would keep both zero drift and zero runtime cost, which is genuinely attractive, but it freezes the derived look — and being able to redraw the glyph by hand is the point of the change, not a side effect. It also needs build machinery the studio build has none of.
  - **Ship a `BaseMultiResolutionImage` pair (@1x + @2x):** deferred. AWT does honour it (`CImage.Creator.createFromImage` unwraps `MultiResolutionImage`), but a single 2x asset keeps the same code path the item already used, which is worth more than a hypothetical improvement on 1x displays that cannot be smoke-tested here. Add the 20px variant if a non-Retina bar reads soft.
  - **Draw both shapes in code with `Graphics2D` instead of shipping pixels:** rejected — it has the same authoring problem as deriving. A menu bar glyph is design work, and expressing it as stroke widths and corner constants makes every future adjustment a code change.
- Consequences:
  - The item's glyph no longer tracks the app mark, which is the invariant ADR-0065 chose derivation for. Two small PNGs now need maintaining alongside it, and nothing will fail if they are forgotten — the menu bar will just keep showing the old brand.
  - Each agent spawn drops ~160 ms of startup and a retained 4 MiB. On macOS the 1024² mark is never decoded at all.
  - Making the paused glyph thicker is now an image edit rather than a change to a threshold, which is what makes it worth doing at all.
  - Manual smoke: with the daemon up, confirm the item is a filled tile, uncheck `Record Traffic` and confirm it becomes the hollow mark within about half a second, then re-check it. Do it once over a light desktop and once over a dark one, since the item is tinted by the system and a wrongly-coloured asset only shows on one of them. Confirm the tooltip reads "recording on …" and "paused on …" to match.
