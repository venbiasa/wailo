---
adr: 0038
title: The on-device panel is themed from the shared design tokens, and the iOS SDK stays native Swift
date: "2026-07-30"
status: accepted
date_source: git-commit
---
# ADR-0038 — The on-device panel is themed from the shared design tokens, and the iOS SDK stays native Swift

- Status: Accepted; implemented. `xcodebuild -scheme WailoSDKDebug -sdk iphonesimulator` builds clean. Appearance is manual-verification only (both schemes, on device) — nothing here is machine-checkable.
- Context: The panel from ADR-0035 was a stock SwiftUI `Form` with system colors (`Color.green`, `.secondary`, the grouped-list background). It therefore followed iOS's palette rather than Wailo's in both schemes, and its three same-weight sections gave no reading order — the thing a user opens the panel to learn (is anything connected, and over what) had no more prominence than a text field. Making the USB port configurable added a fourth thing to place. The Android SDK will eventually want the same panel, which raised whether to write it once in Compose Multiplatform.
- Decision:
  - **Share the tokens, not the UI code.** `WailoTokens.swift` is generated from its semantic layer with the same role names the studio's Compose theme uses, and carries the same DO-NOT-EDIT header. Visual consistency comes from the data, not from a shared widget tree.
  - **No Compose Multiplatform in `sdk-ios`.** It would link a Kotlin/Native runtime and Skia into every host app — invariant #3 and ADR-0010 directly. Confining it to `WailoSDKDebug` does not help much: that is still a runtime shipped into someone else's process for a settings sheet. The Android panel will use Compose natively against the same tokens.
  - **Hand-built layout instead of `Form`.** UIKit's grouped list paints itself from the system palette and cannot be brought onto a custom one per row. Sections are ordered by the question being asked: Status, then Found on this network, then Manual address, then USB.
  - **Type follows the token scale on the system face.** Sizes and weights come from `tokens.json`; the family does not, because bundling Noto Sans into a library means registering a font at runtime inside someone else's app.
- Alternatives considered:
  - **A Compose Multiplatform panel shared with the future Android SDK:** rejected as above. Revisiting it means an ADR that reopens ADR-0010, not a UI decision.
  - **Restyle `Form` via `UITableView.appearance()`:** rejected — a global UIKit appearance mutation from inside an embedded SDK is exactly the kind of side effect on the host app this SDK must never have.
  - **Bundle Noto Sans in the debug product:** deferred. `CTFontManager` registration plus a resource bundle is real weight for a panel nobody reads for long, and it would be the only resource the package ships.
- Consequences:
  - `WailoDebugUI` gains the generated `WailoTokens.swift`; no system color remains in the panel, and the palette is the only place a color is chosen.
  - Tokens are now instantiated twice (Compose and Swift), so a `tokens.json` change needs regenerating in both. Drift shows up as the two surfaces disagreeing; Style Dictionary is the fix if it ever bites.
  - Manual smoke (two-tier verification): open the panel in light *and* dark → chrome, cards, fields, and buttons read as Wailo rather than iOS, the status dot is green when connected / amber when started but not connected / grey before start, the transport chip says USB while the cable is in, and the USB card shows the live listener port. Raise the system text size → the panel scales without clipping. Pin an address, then clear it, and confirm the status line's wording follows.
