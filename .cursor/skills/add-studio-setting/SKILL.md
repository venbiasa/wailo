---
name: add-studio-setting
description: Adds a user-configurable setting to the Wailo studio Settings panel end to end — where the value lives, its KeyValueStore persistence, the panel row and its copy, and the parameter chain from Main.kt through WailoApp/WailoViewer. Use when asked to "put X into settings", "make X configurable", "add a setting", "expose X in Settings", or when a hard-coded studio constant should become a user choice.
---

# Add a studio setting

The Settings panel is one of the docked tool panels in the studio right rail
(`ToolPanel.Settings`), not a dialog. A setting is five small pieces in five fixed places.

## The five layers

| Layer | Lives in | Rule |
|---|---|---|
| Default + valid range | `engine` companion (`DEFAULT_*`, `*_RANGE`) when the engine owns the behavior; else a pure object in `shared` (e.g. `theme/TextScale.kt`) | `shared` must never import `engine` |
| Runtime value | engine `MutableStateFlow` + `setX()`, or a host `mutableStateOf` for host-only concerns | one authority, never two |
| Persistence | `studio/desktopApp/src/main/kotlin/com/venbiasa/wailo/desktop/<X>Store.kt` | plain `object`, `createKeyValueStore("desktop")` |
| Row | `studio/shared/src/commonMain/kotlin/com/venbiasa/wailo/shared/ui/SettingsManager.kt` | stateless over inputs (ADR-0013) |
| Wiring | `Main.kt` → `WailoApp.kt` → `WailoViewer.kt` → `SettingsManager.kt` | value + error + `onApplyX` |

Settings holds **scalars** (a port, a cap, a flag, an identity). Anything with a list, a
hierarchy, or its own workflow gets its own tool panel instead (Capture Filter, Map Local,
Breakpoints), with its own store.

## Checklist

```
- [ ] 1. Decide the owner: engine value or host-only value
- [ ] 2. Default + range, public, next to the behavior they bound
- [ ] 3. Runtime setter that applies immediately
- [ ] 4. <X>Store with a re-defaulting load()
- [ ] 5. Row in SettingsManager under a SectionHeader
- [ ] 6. Param chain: Main.kt → WailoApp → WailoViewer → SettingsManager
- [ ] 7. Host-side validation producing an error string
- [ ] 8. Test + verify (both themes, restart, corrupt pref)
```

### 1–3. Owner, bounds, setter

Engine-owned (anything about capture, transport, devices): the engine holds the value and the
frontend observes it. Mirror `setRequirePairing` / `setMaxRetained`:

```kotlin
class WailoEngine(
    thing: Int = DEFAULT_THING,   // plain param, not `private val` — the flow is the authority
) {
    private val _thing = MutableStateFlow(thing.coerceIn(THING_RANGE))

    /** What it means, and why the value is a judgement the user makes rather than a constant. */
    val thing: StateFlow<Int> = _thing.asStateFlow()

    /** What changing it costs, and why it takes effect now rather than at the next event. */
    fun setThing(value: Int) { … }

    companion object {
        const val DEFAULT_THING: Int = 10000
        val THING_RANGE: IntRange = 100..100000   // public: the store validates against it
    }
}
```

A setter must leave nothing stale — `setMaxRetained` trims the exchanges it already holds,
`rebind` moves the live server. If applying needs IO (a bind, a file), make it `suspend` and let the
host launch it, never the composition.

Host-only (window, appearance, panel geometry): skip the engine, hold `mutableStateOf` in `Main.kt`.

### 4. Store

```kotlin
/**
 * Persists <what>, mirroring [PortStore]. Host-owned because <why the host, not shared>.
 *
 * [load] falls back to <default> both when nothing was ever saved and when what was saved is out of
 * range, so a hand-edited or corrupt preference can't <the failure that would cause>.
 */
object ThingStore {
    private const val KEY = "thing"                      // camelCase, node com/venbiasa/wailo/desktop
    private val store = createKeyValueStore("desktop")

    fun load(): Int = store.getInt(KEY, WailoEngine.DEFAULT_THING)
        .takeIf { it in WailoEngine.THING_RANGE } ?: WailoEngine.DEFAULT_THING

    fun save(value: Int) = store.putInt(KEY, value)
}
```

`KeyValueStore` (`shared/settings/`) covers Float/Int/Boolean/String. One binding stays a plain
`object` — do not reach for Koin (AGENTS.md). Secrets do not go here: prefs are plaintext on macOS,
which is why pairing keys use the Keychain.

### 5. Row

Reuse the private row composables in `SettingsManager.kt`; add a new one only for a genuinely new
control shape.

| Value | Composable | Commit |
|---|---|---|
| Boolean | `ToggleRow` | immediately on flip |
| Number | `NumberField` (`maxDigits`, `placeholder`) | Apply button or Enter only |
| Rare + irreversible | `IdentityRow`-style two-step confirm naming the cost | on confirm |

Never apply a number per keystroke: each keystroke would fire the setting, so typing `8899` would
rebind four times and typing `100` into a retention cap would throw traffic away.

Place it in an existing `SectionHeader` if it belongs there, otherwise add one. Current order —
`Connection`, `Capture`, `Local area network security` — keeps security and destructive actions last.

### 6–7. Wiring and validation

Add three params in visual order, adjacent to the setting they belong with, at every level:
`thing: Int`, `thingError: String?`, `onApplyThing: (Int) -> Unit`. `WailoApp`'s KDoc documents every
param — add a sentence in the same voice. `WailoApp` params take defaults; `WailoViewer` and
`SettingsManager` do not.

Validation is the host's job (ADR-0013) — `shared` renders the verdict, never guesses it:

```kotlin
val maxRetained by engine.maxRetained.collectAsState()   // engine is the authority; read it back
var maxRetainedError by remember { mutableStateOf<String?>(null) }
val applyMaxRetained: (Int) -> Unit = { next ->
    if (next in WailoEngine.RETAINED_RANGE) {
        maxRetainedError = null
        engine.setMaxRetained(next)     // apply first
        MaxRetainedStore.save(next)     // persist only what was accepted
    } else {
        maxRetainedError = "Must be between … and … requests."
    }
}
```

Only the host can attempt something that can fail (a bind), so it reports the verdict back through
the error param rather than `shared` predicting which values work.

The chain above delivers the *row*. When the thing the value governs sits several panels below the panel —
a `CodeEditor` deep inside a body preview — deliver the value itself through a CompositionLocal provided at
each window root in `WailoApp.kt` (`LocalStickyScopeRows`, next to `LocalBodyLoader`), and thread only
value/error/`onApply` down to the row. Threading the value too would put a settings parameter on ten
signatures that have no opinion about it. Remember the *other* window roots: `WailoBreakpointWindowContent`
and `WailoCompareWindowContent` provide their own locals, so a value only added to `WailoApp` silently
reverts to its default in those windows.

## Copy rules

Match the existing rows or the panel reads as two different products.

- **Label**: sentence case, names the thing — "Capture server port", "Requests kept in memory".
- **`status`** (mono, one line): a live fact the number governs, shown when there is no error —
  "Devices dial 192.168.1.5:8899", "Holding 812 of 10000".
- **`help`** (muted, 1–2 sentences, ~120–190 chars): what changing it *costs*, not what the control
  is. Never restate the label. Calibrate against:
  - "Changing it restarts the server, so connected devices drop. Android needs adb reverse re-run on
    the new port; iOS finds it over Bonjour."
  - "Must match the port the iOS SDK listens on. USB has no discovery, so a mismatch looks exactly
    like an app that isn't running."
- **Error**: one sentence, states the accepted range or the reason it was refused.

## Verify

1. `ReadLints` every edited file — the fastest real signal available.
2. Read the `:desktopApp:hotRun --auto` terminal for `e:` compile errors. Adding state to `Main.kt`'s
   window lambda changes its captures, so hot reload throws `NoSuchMethodError` on the old
   composition — expected, and it needs the app restarted rather than debugging.
3. Behavior test in the owning module when the engine owns the value (`WailoEngineRetentionTest`
   drives exchanges through a `FakeConnection` via `engine.attach`). Do not test the store or the row.
4. Manual: both themes, quit and relaunch to confirm it persisted, and an out-of-range value in the
   field to confirm the error path.

## Don'ts

- No persistence, validation, or engine calls inside `shared`.
- No `engine` import from `shared` — bridge values at the `Main.kt` boundary.
- No new colors: the row composables already pull from `MaterialTheme.colorScheme` / `WailoColors`.
- No ADR for a setting that follows this pattern. Write one in `DECISIONS.md` only when introducing a
  new mechanism — another persistence backend, a new control type, a new panel (ADR-0036 established
  the panel, ADR-0040 the pairing toggle).

## Existing settings

| Setting | Owner | Store | Applies |
|---|---|---|---|
| Capture server port | `engine.port` + `rebind` | `PortStore` | on Apply, rebinds live |
| USB device port | `UsbDeviceManager.devicePort` | `UsbPortStore` | on Apply, re-dials |
| Requests kept in memory | `engine.maxRetained` | `MaxRetainedStore` | on Apply, trims held |
| Pinned parent lines | host state → `LocalStickyScopeRows` | `StickyScopeRowsStore` | on Apply, next frame |
| Allow only paired devices | `engine.requirePairing` | `RequirePairingStore` | on flip |
| This Studio's identity | `engine.pairings` | Keychain | on confirm |

Outside the panel by design: theme (`ThemeStore`, tool rail) and text scale (`TextScaleStore`,
Cmd +/−/0).
