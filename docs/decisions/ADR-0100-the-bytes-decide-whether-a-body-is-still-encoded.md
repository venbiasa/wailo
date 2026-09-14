---
adr: 0100
title: The bytes decide whether a body is still encoded, not the header (amends ADR-0096's viewer rule; ADR-0096's proxy invariant stands)
date: "2026-09-14"
status: accepted
relations: amends ADR-0096's fourth decision bullet; scopes that ADR's invariant to the proxy path; keeps ADR-0071's name-what-did-not-happen notice
---
# ADR-0100 — The bytes decide whether a body is still encoded, not the header

- Status: Accepted and implemented in `shared` (`isStillEncoded`, `BodyAnalysis.encoding`, and
  `analyzeBody`/`bodyFileName` taking the header rather than a flag). No `proxy` change: everything
  ADR-0096 decided about the capture side is unaffected.
- Context: ADR-0096 read as one invariant over every capture — a recorded body carries
  `Content-Encoding` if and only if the recorded bytes are still under it — and the viewer was built
  straight on top of it, treating the header's presence as proof. It is proof only where Wailo writes
  both, which is the proxy. An SDK capture cannot honour it: the interceptor reports the origin's
  headers beside the bytes the platform HTTP client handed it, and URLSession has already inflated
  them. Every gzipped response on the iOS path therefore arrives as `Content-Encoding: gzip` over
  plaintext — measured on a live capture: header `gzip`, `Content-Length: 35`, 16 recorded bytes
  beginning `08 B0 EA`, which is protobuf and not a gzip member. The viewer believed the header and
  buried the readable body of nearly every captured response under a hex dump and a notice blaming a
  compression that was not there.
- Decision:
  - **A coding counts only when the bytes carry it.** `isStillEncoded` is the single predicate:
    gzip and zstd are checked against their signatures, zlib-wrapped deflate against its CMF and
    header checksum, and anything else — brotli, raw deflate — falls back to "this does not read as
    text". `analyzeBody` takes `contentEncoding` and reports back the coding it *confirmed* in
    `BodyAnalysis.encoding`; nothing downstream sees the raw header again.
  - **ADR-0096's biconditional is scoped to the proxy, where Wailo writes both sides.** It stays a
    hard rule there and every `proxy` mechanism it added stands. What it cannot be is a premise a
    reader of a capture may assume, because the SDK path's header and bytes come from different
    parties and only one of them is ours.
  - **One confirmed coding drives the classification, the notice, and the file name.** They were three
    readings of the same header and so could disagree; now a body saved from a stale-header response is
    `search.json`, the name of what is in the file.
- Alternatives considered:
  - **Trust the header, and fix the SDKs to strip it when the client decoded.** Rejected as the primary
    fix: it is right for Wailo's own interceptors and still leaves the viewer believing a claim made by
    software Wailo does not ship, on rows already captured. Worth doing on its own merits — a recorded
    header that describes bytes nobody has is wrong on the Headers tab too, where no amount of
    viewer-side inference reaches. This decision is what makes it a cleanup rather than a prerequisite.
  - **Gate on `CaptureSource`: believe the header for `PROXY` rows, sniff for SDK rows.** Rejected. It
    is accurate today and encodes "which component recorded this" into a question that is really about
    the bytes — a third capture path, or an SDK that starts forwarding raw bytes, silently picks the
    wrong branch. Checking the payload is correct for reasons that do not expire.
  - **Drop the encoded notice and let a compressed body render as an unidentified dump.** Rejected:
    that is the confusion ADR-0096 was written to end, and the notice is right exactly when the bytes
    agree with the header.
  - **Decompress on the fly in the viewer when the header says gzip and the bytes are gzip.** Rejected
    here as scope, not as a bad idea: the capture, not the pane, is where a coding should come off, and
    doing it in one place keeps what the pane shows equal to what the row holds.
- Consequences:
  - Brotli over an SDK capture is the one case still decided by a guess: it has no signature by design,
    so a decoded-but-labelled brotli body is only recognised when it reads as text. A binary payload
    misread this way loses nothing it had — it was a hex dump either way — and the notice above it is
    the only thing that is wrong.
  - A body under 2 bytes is never called encoded. No real coding produces one, and the alternative is
    reading past the end for a signature.
  - The proxy now has the stricter contract of the two: it must keep the biconditional ADR-0096 set,
    *and* what it records has to survive a viewer that no longer takes its word for it. A proxy row
    whose header and bytes disagree now silently shows the body instead of flagging the disagreement —
    the tests in `ProxyServerTest` are what keeps that from happening, since the viewer stopped being a
    second check on it.
  - Manual smoke: open any gzipped JSON response captured through the SDK and confirm it renders as
    JSON with no encoding notice, and that saving it offers `<name>.json`. Then run a brotli response
    through the proxy and confirm the dump, the notice, and a `<name>.br` download are all still there.
