---
adr: 0016
title: Dark mode is first-class — every new design must work in light and dark
date: "2026-07-15"
status: accepted
date_source: git-commit
---
# ADR-0016 — Dark mode is first-class — every new design must work in light and dark

- Status: Accepted and built (top-bar toggle compiles in the `studio` build; `:desktopApp:classes` green).
  Builds on ADR-0013's light/dark theme and amends AGENTS.md "Conventions".
- Context: ADR-0013 shipped a full light+dark Material 3 theme generated from `tokens.json`, but the app
  only *followed the OS* (`isSystemInDarkTheme()` default) with no in-app override, and light/dark parity
  was left as an implicit, easily-skipped manual check. Dark mode should be a deliberate, tested product
  surface — not an accident of the OS setting — and designing for it must be a habit, not a retrofit.
- Decision:
  - Dark mode is a first-class, user-controllable mode. A top-bar toggle flips light/dark; the choice is
    host-owned (`desktopApp` `Main.kt`, mirroring the Cmd +/- text-scale pattern) and persisted through the
    `KeyValueStore` seam (`ThemeStore`), seeded from the OS on first run so behavior only improves on the
    old auto-only default. `shared` stays stateless over its inputs (ADR-0013): `WailoApp`/`WailoViewer`
    take `darkTheme` + `onToggleDarkTheme`, the host owns the state.
  - Going-forward rule: **every new screen/component is designed and verified in both appearances.** Colors
    come from `MaterialTheme.colorScheme` or the `WailoColors` CompositionLocal — never hard-coded; new
    colors are added as `tokens.json` entries for *both* schemes (the generated `theme/*.kt` stay
    DO-NOT-EDIT, ADR-0013). Icons are tint-driven (mono vector drawables recolored by `Icon`) so they
    invert with the theme instead of baking in a light-mode color.
  - Light/dark parity is part of the repo's manual two-tier verification (ADR-0013): smoke-check both via
    the toggle before UI work is called done.
- Consequences:
  - `ThemeStore` is one `getBoolean`/`putBoolean` binding, so it stays a plain `object` (not Koin) per the
    DI convention. Two rounded Material Symbols drawables were added (`ic_dark_mode`/`ic_light_mode`, mono).
  - A design that hard-codes a color or ships a non-tintable icon is now a **defect, not a style nit** — it
    will break one of the two schemes by construction.
  - Does not force a tri-state (System/Light/Dark); the binary toggle latches an explicit choice after first
    run. Revisit if a "follow system" option is wanted later.
