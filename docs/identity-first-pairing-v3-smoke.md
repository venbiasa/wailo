# Identity-First Pairing V3 physical smoke

Keep this runbook in the repository: it covers hardware, lifecycle, and network behavior that unit
tests cannot prove. The checklist is intentionally evergreen and must remain unchecked in source.
Record each execution under **Run history** instead of turning one successful build into permanent
checkmarks.

Run on both a physical iPhone and Android device. Keep the devices and Mac on the same Wi-Fi, disable
cable transports, and generate traffic containing a harmless marker that is easy to recognise in
Studio.

## Release gate

Before accepting an ordinary build, run Explicit TOFU, Different Studio at the same route, Strict
enrollment, and Online device Forget on both platforms. Before releasing a protocol change, run the
full matrix and record any unavailable archived-binary fixture explicitly.

## Evergreen matrix

- [ ] **Explicit TOFU:** with strict pairing off and no saved relationship, fill a discovered Studio,
  press Connect, and confirm the panel moves through Dialling and Authenticating before Connected.
  Confirm Studio sees no `Hello` or traffic before authentication, then sees the marked request.
- [ ] **Same Studio, new route:** move the same Studio identity to another LAN address, reconnect through
  discovery, and confirm it authenticates as the existing relationship without confirmation.
- [ ] **Different Studio, same route:** stop the expected Studio and put another signing identity at its
  saved address and port. Confirm the device stops at Identity mismatch, sends no `Hello`, and neither
  accepts nor retries until the user explicitly chooses.
- [ ] **Strict enrollment:** enable Only paired devices over Wi-Fi. On each platform, confirm an explicit
  unknown Connect ends at Refused, then enroll once by QR and once by typed code. Expire another offer
  and confirm its signed refusal is shown.
- [ ] **Online device Forget:** while traffic is flowing, Forget on the device. Confirm Connected clears
  after the sealed revoke/ack, Studio removes the device row, and discovery does not reconnect it.
  Explicit Connect or enrollment must create a fresh relationship.
- [ ] **Offline device Forget:** pair with two Studios, stop the active Studio, Forget it locally, restart
  it, and explicitly Connect. Confirm its stale row is inert, a fresh alias connects, and the second
  Studio's relationship still authenticates with its original alias.
- [ ] **Studio Forget:** Forget the live device in Studio. Confirm the session closes, reconnect receives
  the authenticated Unknown device refusal, and recovery requires local Forget plus an explicit new
  Connect or invitation.
- [ ] **Unlinkability:** pair the same app with two Studios and inspect each Studio's daemon state.
  Confirm the 32-character aliases differ. Run the platform handshake tests that assert neither alias
  appears in anonymous `AuthClientHelloV3`.
- [ ] **Downgrade:** run archived v2 SDK and Studio binaries when signed fixtures are available, in both
  directions. Confirm each closes before `Hello`, no traffic or rules cross the link, and no pairing is
  created. Always run the raw-v2-tag rejection tests on Studio, Android, and iOS.
- [ ] **Recovery:** sleep/wake, force-quit/relaunch, and cycle Wi-Fi. Confirm both platforms reconnect
  without restarting Studio, Connected appears only after sealed `Hello`, and dead sockets disappear
  instead of leaving duplicate device rows.

## Recovery and cleanup

If a device becomes stuck, Forget the relationship on both sides, clear its saved target, and reconnect
explicitly. If two test Studios are hard to distinguish, reset only the disposable identity and record
its new ID. Never reset an identity that owns pairings you intend to keep.

Use `WAILO_HOME`, `WAILO_CAPTURE_PORT`, and `WAILO_KEYCHAIN_SERVICE` for a disposable second Studio.
After the run, restore the real listener and strict-pairing setting, remove stale device rows and
on-device relationships, stop the disposable daemon, and delete its state directory and Keychain
entries.

## Run history

### 2026-08-14 — Identity-First Pairing V3

- Build: uncommitted v3 working tree based on `54f7dfda8214e7303500812dab502cc8b60fa655`.
- Devices: iPhone 12 / iOS 18.5; OPPO CPH2251 / Android 13.
- **Passed physically on both:** Explicit TOFU, same identity on a new route, different identity at the
  same route, strict refusal, QR enrollment, typed-code enrollment, online device Forget, offline
  device Forget with fresh aliases, Studio Forget with authenticated Unknown device refusal, and
  sleep/wake, Wi-Fi-cycle, and force-quit recovery.
- **Second-Studio isolation:** both apps physically reconnected to the second Studio with their saved
  relationships after the primary relationship was forgotten offline; daemon inspection confirmed the
  second-Studio aliases were reused rather than recreated.
- **Unlinkability:** physical daemon inspection showed different 32-character aliases for both apps
  across the two Studio identities. Studio, Android, and iOS tests also passed the anonymous
  `AuthClientHelloV3` assertion.
- **Enrollment details:** QR and typed code passed on both platforms. An expired QR produced Studio's
  signed expiry refusal on iPhone. iOS typed-code discovery required two fixes found during this run:
  browsing remains active while a target is pinned or reconnect is suppressed after Forget, and
  `NWBrowser` requests Bonjour TXT records with `.bonjourWithTXTRecord`.
- **Route refresh:** iOS initially kept the first numeric result for a Bonjour service after Studio's
  IP changed. Discovery now re-resolves existing service endpoints on browse updates, reconnect
  attempts, and panel presentation. Physical retest listed the current IP and reconnected with the
  existing Studio identity without confirmation or a new pairing.
- **Recovery details:** Wi-Fi cycling initially left dead pre-cycle sockets beside the recovered
  sessions. Studio now sends WebSocket pings with a 15-second timeout; the repeated physical cycle
  recovered both apps and left exactly one session per device.
- **Live iOS status and USB:** with the panel left open, stopping Studio changed Connected / USB to
  Dialling and restarting returned it to Connected / USB without reopening. LAN pings now have an
  explicit pong deadline, USB takeover posts a transport-change notification, and LAN stays active
  until USB has successfully sent Hello before being suspended.
- **Downgrade:** raw v2-tag rejection, forged hello/proof/result rejection, strict frame sequencing,
  and pre-auth status passed across Studio, Android, and iOS. Old signed v2 device binaries were not
  available, so the archived-binary physical row remains an explicit release-fixture gap.
- Verification: `swift test` passed 111 tests; the iOS Simulator SDK build passed; `:engine:test`
  passed. Required Android and cross-platform handshake suites passed earlier in this run.
- Cleanup: strict pairing is restored, the primary listener is on `8899` with one current relationship
  and one live session per device, the CLI and daemon distributions were refreshed, and the disposable
  daemon, identity, Keychain rows, state directory, stale primary rows, and Bonjour probes were removed.

