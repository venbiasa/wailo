---
adr: 0071
title: HTTPS starts locked — `CONNECT` is an opaque tunnel until a host is explicitly unlocked
date: "2026-08-22"
status: accepted
relations: extends ADR-0070; constrains the CA/trust work, implemented by ADR-0073
---
# ADR-0071 — HTTPS starts locked: `CONNECT` is an opaque tunnel until a host is explicitly unlocked

- Status: Accepted; both halves are implemented — a `CONNECT` tunnels byte-for-byte and is recorded as a locked row unless the host is on the allowlist, in which case ADR-0073's local root signs a leaf for it and the requests inside become ordinary rows.
- Context: A proxy sees `CONNECT host:443` and has exactly two options: pass the bytes through untouched, or terminate TLS with a certificate the client will accept and re-originate the connection. The second requires generating a root, installing it in the OS trust store, and then being technically able to read every TLS connection the machine makes — banking, password managers, the user's own mail. Proxy tools have historically defaulted this on, and it is the single most consequential thing a debugging tool can ask for. Wailo's existing posture is the opposite of casual about this: the MCP gate is revocable, redaction is on by default (ADR-0059), and captured bodies are encrypted under a key that dies with the process (ADR-0069). A proxy that decrypted everything the moment it started would contradict all of it.
- Decision:
  - **Locked is the default and the starting state.** A proxy with no configuration tunnels every `CONNECT` opaquely. Turning proxying on is not consent to decrypt anything.
  - **`CONNECT` still produces a row.** A locked tunnel is recorded as an exchange with the `CONNECT` method, an `https://host:port` URL, and a response that says the tunnel was established and not decrypted. Silence would be worse than a locked row: the user would think their traffic was not reaching Wailo, when in fact it was and we chose not to read it.
  - **Installing the CA is not the same as decrypting.** Generating and trusting a root is a separate, explicit step, and even with it installed the proxy decrypts only hosts on an allowlist the user maintains. `*` is offered only as a deliberate, daemon-session-scoped action and is never persisted.
  - **Removing trust is a first-class action, not a cleanup chore.** The root stays installed until the user chooses Remove Wailo CA, and export is public-certificate-only.
  - **A rule that matches a locked host stays inert and says so.** A Map Local or Breakpoint rule against a tunnel we cannot read must report why and offer to unlock that one host. It must never widen the allowlist on its own — a rule silently granting decryption would make the allowlist meaningless.
- Alternatives considered:
  - **Decrypt everything once the CA is trusted (the conventional default):** rejected — it makes the blast radius of a debugging tool the user's entire TLS surface, and it is the opposite of the daemon's existing defaults. The cost of the allowlist is one click per host that actually needs it.
  - **Refuse `CONNECT` entirely until a CA exists:** rejected — it would break every HTTPS request from a client pointed at the proxy, which is nearly all of them, and teach the user that pointing anything at Wailo breaks their machine.
  - **Tunnel silently with no row:** rejected — see above; the user cannot tell "not captured" from "not decrypted", and the second is a state with an obvious next action.
  - **Ship a pre-generated root:** rejected outright — a root whose private key is in a public repo is a root everyone has.
  - **Bypass pinning:** rejected — a pinned app must fail visibly. Defeating pinning is a different product and a different threat model.
- Consequences:
  - Out of the box the proxy inspects plain HTTP fully and shows HTTPS only as locked rows with host, port, and timing. That is a smaller first result than a tool that decrypts by default, and it is the intended one.
  - Every unlocked host is a deliberate act with a record, which is what makes it possible to answer "what could Wailo read" later.
  - HTTP/2 and QUIC are invisible inside a locked tunnel, so the traffic list cannot distinguish them; a locked row is all there is until the host is unlocked and the connection negotiates down.
  - The allowlist becomes durable daemon configuration (ADR-0061's class), while the `*` escape hatch is session state that dies with the process.
