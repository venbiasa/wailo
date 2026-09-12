---
adr: 0096
title: A capture never claims a coding it did not undo, and a hold declines the ones it cannot
date: "2026-09-12"
status: accepted
relations: fixes the decoded-body claim ADR-0070's relay records; narrows the holds ADR-0067 fires; applies ADR-0071's "say which thing did not happen" to bodies rather than tunnels
---
# ADR-0096 — A capture never claims a coding it did not undo

- Status: Accepted and implemented in `proxy` (`canDecodeForCapture`, `withoutContentEncoding`,
  `HttpRequest.recordedAfter`, and the response hold's gate) and in `shared`
  (`List<Header>.contentEncoding`, `analyzeBody(encoded = …)`, and the notice in `BodyPreview`).
- Context: the capture side can undo exactly the codings the JDK ships — gzip and deflate. Brotli is
  neither, and it is what most origins now answer with. Three places wrote down a decode that had not
  happened. The streamed relay was already honest (`decoded = decoder !== entity`), but the request record
  kept `Content-Encoding` over a spool it had inflated; `releaseOversized` asserted `decoded = true`
  outright; and the response hold computed `decoded = encoding != null`, which is "there was a coding",
  not "we removed one". Because `framedFor` strips `Content-Encoding` whenever a body is claimed decoded,
  that last one was not merely a mislabelled row: a breakpoint on a brotli response delivered the
  compressed bytes to the client *without* the header that explained them, so the client read gzip-shaped
  garbage as the document. Stopping an exchange corrupted it.
- Decision:
  - **A recorded body's `Content-Encoding` is present if and only if the recorded bytes are still under
    it.** One invariant, both directions: dropping the header over compressed bytes and keeping it over
    plaintext are the same lie told from opposite ends, and only the pair is checkable. Every record site
    now derives the header from what the capture actually did — `recordedAfter(sent)` for a streamed or
    drained request, `relayBody`'s own flag for a streamed response — instead of from what the origin said.
  - **A hold declines a coding the capture cannot undo.** `canDecodeForCapture` gates both the request and
    the response hold; anything else falls through to the streaming relay, reaching the far side byte-exact
    and recorded as it arrived. A hold exists to show a body and take an edit back, and it can do neither
    with bytes it cannot read — so the honest move is not to fire, rather than to fire on a payload the
    pane would render as a dump and the client would receive stripped of its coding.
  - **A coding *list* (`gzip, br`) counts as undecodable.** Unwrapping only the outer layer would leave a
    half-decoded body that no single header can describe. Declining reports the bytes as they arrived,
    which is true.
  - **An encoded body is classified as nothing at all: `analyzeBody` returns Hex alone.** Compressed bytes
    are not the payload, whatever they sniff as. A short one can pass the printable-prefix check and render
    as mojibake, so the flag overrides the sniff rather than adding a caveat to it.
  - **The viewer names the coding, and says which ones Wailo does undo.** `(still br-encoded • Wailo
    decodes only gzip and deflate)`, in the same muted parenthetical as the truncation notice. The
    reader's question is why a JSON response is a hex dump; "nobody has decompressed it" is the answer,
    and naming the two codings that do work is what stops it reading as a Wailo failure. `identity` is
    not a coding, so it reads as plaintext and shows no notice.
- Alternatives considered:
  - **Take `org.brotli:dec` into `proxy` and decode it.** Rejected for now, and this decision is what makes
    it safe to add later: `proxy` depends on `protocol` and the JDK and nothing else, and a decompressor is
    a parser running on hostile bytes in the module that also relays them. When it goes in, it is one entry
    in `CaptureCoding` and both the gate and the notice follow automatically — which is the point of
    routing every site through one predicate.
  - **Teach `framedFor` to keep `Content-Encoding` when the body was not decoded.** Rejected: it fixes the
    delivery and leaves the hold still broken. The pane would show compressed bytes as the body, and the
    moment anyone edited them the header would describe a compression nobody applied — re-compressing an
    edit to match a coding we cannot decompress is not a thing this can do.
  - **Fail a hold on an undecodable coding with a 502.** Rejected: it breaks a page to enforce a breakpoint
    that could not have done its job, which is the reasoning `releaseOversized` already settled for a body
    past the hold ceiling.
  - **Strip `Accept-Encoding` from every forwarded request so origins answer in plaintext.** Rejected:
    it changes what the app under test receives, which is the one thing an interception proxy must not do
    casually. A response compressed differently because Wailo was in the path is a heisenbug, and the
    header is also a fingerprint — a strip would be visible to the origin.
  - **Show the dump with no notice, as today.** Rejected: an unexplained hex dump on a row whose headers
    say `application/json` reads as Wailo having broken the response, which is the same confusion ADR-0071
    solved for a locked tunnel by making "not decrypted" a distinct thing to be.
- Consequences:
  - A breakpoint set on a URL that answers brotli no longer fires, and the row appears with its body
    intact but unreadable. That is a real loss of reach, traded for not corrupting the response — and it is
    now the loudest argument for the brotli dependency, because the gate is where the cost is visible.
  - A Map Local rule answering a gzipped request now records the request body decoded, where it previously
    spooled it compressed: `consumeRequest` passes the coding through like every other capture path. The
    invariant is what forced that — the drain was the one site whose flag and header agreed only by
    accident.
  - `MAX_HELD_BODY_BYTES` and the coding gate now both decide holdability, so "why didn't my breakpoint
    fire?" has two answers. Neither is reported to the user yet; the daemon sees only that no hold arrived.
  - A response that is *both* oversized and undecodable never reaches `releaseOversized`, which is why that
    function's `decoded = true` is now sound rather than merely convenient.
  - Manual smoke: point the proxy at any modern HTTPS origin (most answer `br`), open the row, and confirm
    the body pane shows the dump under a notice naming the coding — then confirm the page itself rendered
    in the browser. Set a breakpoint on the same URL and confirm it does not fire and the page still
    loads. Confirm a gzipped response still holds, still shows decoded, and still carries no
    `Content-Encoding` in the recorded headers. Check the notice in both light and dark.
