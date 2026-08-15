---
adr: 0060
title: Wi-Fi authentication is identity-first, with per-Studio device aliases
date: "2026-08-14"
status: accepted
date_source: conversation
---
# ADR-0060 — Wi-Fi authentication is identity-first, with per-Studio device aliases

- Status: Accepted. Supersedes ADR-0039/0040's v2 handshake ordering and amends ADR-0047's
  device-side Forget semantics.
- Context: The v2 device opened with one installation-wide `device_id`. Studio had to select that
  identifier's saved key before it revealed and signed its own identity. If iOS forgot a Studio while
  Studio retained the device, the next explicit Connect called itself a first contact but Studio still
  selected the old key; both sides derived different MACs and silently rejected each other. Rotating
  the global identifier would break every other Studio. Retaining the deleted key would make Forget
  untrue. The ordering also exposed a stable cross-Studio correlate to anything answering at an IP,
  even though DHCP and shared networks make an address routing metadata rather than identity.
- Decision:
  - **The v3 Wi-Fi handshake is identity-first.** A device opens with only a version, fresh nonce and
    fresh P-256 agreement key. Studio answers with its public key, fresh nonce/agreement key, pairing
    policy, and a signature over that whole transcript. No device identifier leaves the app before
    that signature is verified.
  - **Device aliases are random and scoped to one Studio relationship.** Once Studio's signed
    identity is known, the device selects that Studio's saved alias and key, consumes an invite, or
    creates a fresh alias for deliberate trust on first use. Forgetting one Studio therefore cannot
    disturb another and a later explicit reconnect is a genuinely new relationship, not an overwrite
    of the old alias.
  - **An address is a route plus optional intent, never a trust key.** Automatic discovery and a row
    filled from a known desktop carry an expected `studio_id`. A manually edited address has no such
    expectation. If a saved target is now answered by another signed identity, connection stops for
    explicit confirmation; selecting the newly advertised desktop is that confirmation. This permits
    ordinary IP reuse without silently accepting an address takeover.
  - **Explicit Connect keeps TOFU.** A new identity at an address the user deliberately committed may
    enroll when Studio's strict-pairing setting is off. Strict mode still requires the live QR or
    typed-code offer. Discovery never performs a first contact.
  - **Every outcome is authenticated.** Studio signs its result over the full transcript, alias,
    mode, counter and result code. A successful result also carries a MAC under the selected
    long-term key, confirming both sides chose the same credential before either sends `Hello`.
    Invalid proofs close silently; policy and stale-alias refusals are signed and may be shown.
  - **Forget is local deletion plus best-effort authenticated revocation.** If the relationship is
    live, the device asks Studio over the sealed session to remove the old alias before deleting it.
    Offline Forget still deletes immediately, and either path stops automatic connection until a later
    explicit Connect. A missed revocation leaves only an inert Studio-side row: reconnect uses a
    different alias and can never impersonate the forgotten record.
  - **v3 is a clean break.** Existing Studio signing identity survives, but v2 paired-device records
    on Studio and both device SDKs are ignored under versioned storage namespaces. There is no
    permanent dual-protocol admission path or downgrade fallback.
  - **Loopback and USB remain open.** The Simulator, `adb reverse`, and usbmux tunnel still begin at
    `Hello`; ADR-0039's kernel-local trust boundary is unchanged.
- Wire/crypto contract:
  - Nonces and secrets are 32 bytes; P-256 public keys use the 65-byte X9.63 uncompressed form.
    `studio_id` is the lowercase-hex first 16 bytes of SHA-256(public key), and an alias is 16 random
    bytes encoded as lowercase hex. ECDSA-SHA256 signatures are fixed-width 64-byte `r || s`.
  - All text below is UTF-8. `label(text, suffix) = text || 0x00 || suffix || 0x00`; booleans are one
    byte (`0` or `1`), modes/result codes are unsigned 32-bit big-endian, and counters are unsigned
    64-bit big-endian. Concatenation has no other framing because every variable text field is inside
    `label` and all remaining fields have fixed widths.
  - `hello(role) = label(role, studio_id) || nonceD || nonceS || ephemeralD || ephemeralS ||
    pairing_required`. `auth(role) = hello(role) || label("wailo/device-alias/v3", alias) || mode ||
    counter`. `result(role) = auth(role) || result_code`.
  - Studio signs `hello("wailo/studio-hello/v3")`. The device proof is
    HMAC-SHA256(`Kauth`, `auth("wailo/device-proof/v3")`). Studio signs
    `result("wailo/result-signature/v3")`; an OK result additionally carries
    HMAC-SHA256(`Kauth`, `result("wailo/studio-proof/v3")`).
  - Let `Z` be the 32-byte P-256 ECDH result. Invite relationships derive `Kdevice` with
    HKDF-SHA256(offer secret, salt=`studio_id`, info=`label("wailo/device-key/v3", alias)`); TOFU uses
    HKDF-SHA256(`Z`, salt=`studio_id`, info=`label("wailo/tofu-device-key/v3", alias)`).
    `Kauth = HKDF-SHA256(Z || Kdevice, empty salt, "wailo/auth/v3")` and
    `Ksession = HKDF-SHA256(Z || Kdevice, nonceD || nonceS, "wailo/session/v3")`.
  - Auth modes are `known=1`, `tofu=2`, `invited_qr=3`, `invited_code=4`; result codes are `ok=1`,
    `pairing_required=2`, `pairing_offer_invalid=3`, `unknown_device=4`. Zero is unspecified and is
    never admitted. `transport.proto` remains authoritative for message fields and Envelope tags.
- Alternatives considered:
  - **Let first contact replace a known key for the same global device id:** rejected. Any LAN peer
    could force or occupy that reset path, destroying the central post-TOFU guarantee.
  - **Rotate the global device id on Forget:** rejected. It repairs one Studio by breaking every other
    relationship and leaves the cross-Studio correlation in place.
  - **Keep a disabled copy of the forgotten key:** rejected as the primary model. It makes explicit
    reconnect work but means Forget did not delete the secret; it also cannot repair Studio-initiated
    revocation.
  - **Require QR/code for every new Studio:** rejected. The deliberate Connect boundary remains the
    common-case protection; strict mode is available when the network warrants ceremony.
- Consequences:
  - `protocol` gains v3 hello, identity, device-auth and result messages plus sealed revoke/ack
    controls. V2 field numbers remain occupied but are not admitted.
  - The v3 KDF/proof labels and transcripts are a three-implementation contract: Studio JVM, Android
    JVM and native Swift must carry identical vectors.
  - A Studio may retain a stale row after an offline device Forget. It is harmless and removable from
    Devices; names or IPs are not safe enough to deduplicate it automatically.
  - The on-device status has distinct dialling, authenticating, connected, refused and
    identity-mismatch states. `Connected` is set only after authenticated v3 success and a sealed
    `Hello`.
  - Manual smoke must cover two Studios trading one IP, one Studio moving IP, online and offline
    Forget/reconnect, strict QR/code enrollment, and a v2 peer being refused without exposing traffic.
