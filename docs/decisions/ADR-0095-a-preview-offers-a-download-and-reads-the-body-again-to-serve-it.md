---
adr: 0095
title: A response body offers a download in the pane header, and reads the body again to serve it
date: "2026-09-12"
status: accepted
relations: applies ADR-0069's prefix rule to writing a body out; mirrors ADR-0020's "the host owns file IO" seam; narrows the previewer set ADR-0016's detail panel offers
---
# ADR-0095 — A body offers a download in the pane header, and reads the body again to serve it

- Status: Accepted and implemented as `BodySaver` / `LocalBodySaver` in `shared`, provided by
  `desktopApp`'s `saveBodyFile`, with `SaveBodyButton` in the response pane's tab row and `bodyFileName`
  in `format`. Ships alongside a second, smaller change in the same surface: `analyzeBody` no longer
  offers Hex for a body whose type it recognized.
- Context: a captured body previewed fine and then had nowhere to go — getting the bytes out meant the
  Hex tab and a mouse. Three things had to be decided, and none is local to the button: `shared` owns no
  file IO, so the write is somebody else's; the pane does not *have* the payload, because rows carry body
  references (ADR-0069) and a previewer fetches only `PreviewByteLimit` of one; and a detail panel this
  dense has no vertical space to give a control a band of its own.
- Decision:
  - **The saver is a host capability reached through a CompositionLocal, not a threaded callback.** The
    button lives several composables below `WailoApp`, in a pane that two windows compose, so a parameter
    chain would touch every layer in between to give none of them an opinion. `LocalBodyLoader` is already
    the precedent for exactly this shape, and the two now read as halves of one seam: the host is the side
    that can reach a file, in either direction.
  - **The local is nullable and defaults to absent.** A surface the host has not wired renders no download
    button at all, rather than an enabled one over a no-op saver. That is why the seed preview in the
    breakpoint window — which composes the same previewer but is given no saver — simply has no button, and
    why adding one there later is a wiring change and not a bug fix.
  - **The button sits in the pane's tab row, beside the RESPONSE caption, not inside a previewer.** What
    leaves is the bytes, and every previewer is showing the same ones — a control per previewer would be
    one behaviour copied five times, with the hex view offering a download for bytes the image view had
    already offered. The tab row is also the only place that costs nothing: a 36dp icon button is no
    taller than the tab labels, so the control adds no height to a panel that has none to spare.
  - **It appears only on the tabs that show bytes.** Body and Raw, gated on the same `shownBody` the fetch
    already keys on, so Headers and Auth never carry an action that means nothing there. That was the
    objection to this placement, and the gate is the answer to it rather than a reason to put the button
    somewhere worse.
  - **Only the response pane offers it.** What someone pulls out of a capture is what came back; a request
    body is usually the few bytes they typed. Serving the rarer half would cost the header on both sides,
    and the seam makes adding it a one-line change if that turns out to be wrong.
  - **The saver returns the line to show, and returns it blank on success.** A file the user picked and a
    dialog they dismissed both explain themselves; only a failed write has something to say. So the pane
    has no confirmation state to manage and no toast to expire, and a silent failure is still impossible.
    The button reports through a callback rather than its own text, so that line can span the pane instead
    of squeezing the tab row.
  - **A download re-reads the whole body rather than saving what was previewed.** The click reads the
    handle again up to `MAX_INLINE_BODY_BYTES`, and falls back to the previewed bytes only when there is no
    handle (an authored body, already whole in memory) or when the re-read comes back empty (the capture
    was cleared between showing and clicking). Saving the prefix would have been one line shorter and would
    write a truncated file for exactly the payloads a preview cannot hold — the failure is silent, produces
    a real file, and surfaces later in whatever opens it.
  - **The name is the URL's last path segment plus the extension the *classification* implies.** Not the
    path's own extension and not Content-Type: the magic bytes already outvote the header for the
    previewer, and a file name is the same kind of claim. A name that already agrees keeps it; one that
    disagrees gets both (`logo.jpg.png`), which reports that the bytes are not what the path called them.
    A body nothing identified gets `bin` rather than a guess, and a body the capture never decoded is named
    for its *coding* (`search.br`, not `search.json` — ADR-0096), because the file holds an archive and
    must not be named for the thing inside it.
  - **Hex is offered only for a body nothing identified.** A hex dump answers "what are these bytes?", and
    an identified image, JSON, or text body has already answered it. Markup and form bodies keep their
    plain-Text fallback, because a Content-Type can lie and a mis-sniff must not be a dead end. For a text
    body the Raw tab is the second copy (it prints the decoded document under the headers); for an image it
    is not — Raw renders a binary body as `⟨ binary • size ⟩`, so this genuinely removes the only hex view
    an image had. That is the intended trade and not an oversight: a byte-level reading of a PNG is a
    different task from inspecting an exchange, and it is now served by saving the file.
- Alternatives considered:
  - **Write straight to `~/Downloads` with a generated name,** as `exportCertificate` does. Rejected: that
    one goes there because its next step is the OS's and the file must merely *exist* somewhere findable.
    An asset pulled out of a capture is usually on its way into a specific directory, and a save dialog
    both asks where and doubles as the confirmation this design otherwise lacks.
  - **Thread `onSaveBody` down as a parameter,** matching `onPickMapLocalFile` and `onExportRules`.
    Rejected: those land on a panel one or two levels below their provider. This lands under the detail
    panel, and the intermediate signatures would gain a parameter they only forward.
  - **A no-op default saver instead of null.** Rejected: it makes an un-wired surface indistinguishable
    from a broken one, and the button is the loudest possible claim that saving works here.
  - **Put it in the body previewer, above the preview.** Rejected after trying it: a row of its own — even
    one shared with the previewer switch — spends height on a control, and it moves as the user switches
    views, which is wrong for something that does not depend on the view.
  - **Save the previewed bytes and disable the button when the preview is partial.** Rejected: it makes a
    large body the one case that cannot be saved, which is the case most worth saving. Re-reading serves
    it correctly for the same click.
  - **Disable the button when the capture truncated the body.** Rejected: a partial body is still worth
    extracting, and the pane says it is partial directly below the button.
  - **Keep Hex as a universal last resort.** Rejected as the offer, kept as the fallback: an image whose
    decoder refuses it still lands on the hex dump, so the payload is never a dead end even though the
    switch no longer lists it.
- Consequences:
  - `WailoApp` gains `bodySaver`, and `shared` gains a second public host-capability interface. Neither
    the compare window nor the breakpoint window provides one, so neither shows a download today.
  - `UnderlineTabs` gains a `trailing` slot. It is a general affordance now — one pane-level action beside
    the caption — which is worth watching: the gate that keeps this button honest is per-tab, and a second
    caller would need its own.
  - A single-previewer body hides the previewer switch entirely, which is now the common case: JSON, text,
    and image bodies each render with no toggle above them. An image that fails to decode selects a
    previewer its own analysis no longer lists — legal, and invisible, because the switch is hidden at one
    option anyway.
  - A body that sniffs as text but carries binary further in — a `multipart/form-data` upload is the
    common one — loses its hex escape too, because the sniff only reads the first 4 KB and the switch now
    trusts it. Raw shows the same lossy decode. That is the one case where dropping Hex removes something
    with no replacement, and it argues for a multipart previewer rather than for keeping the dump.
  - The saved file can differ from what was on screen: the pane draws a prefix, the file holds the payload.
    That is the intended direction, but it means a download is a daemon round-trip, not a buffer copy, and
    it inherits that read's failure modes.
  - The re-read is bounded by `MAX_INLINE_BODY_BYTES` (8 MB), not by the body. A payload past that still
    saves truncated — the ceiling moved out by 4× rather than away, and a body larger than it needs a
    ranged write this decision does not build. A file carries no notice, so both this and a capture-side
    truncation are disclosed on screen and not in the artifact.
  - The save coroutine is scoped to the composition, so stepping to another row mid-dialog abandons the
    write. The dialog is modal to the window, which is what makes that unreachable rather than merely
    unlikely; a non-modal save surface would have to hoist the scope.
  - Manual smoke: open a row with a response body and confirm a download button sits beside the RESPONSE
    caption on the Body and Raw tabs and is absent on Headers and Auth; confirm the request pane has none;
    confirm a JSON body saves as `<segment>.json`, an image as before, and a brotli response as
    `<segment>.br`; confirm a JSON and a plain-text body show no Hex option; confirm an HTML body still
    offers Text; confirm a binary body still opens on Hex alone. Check the row in both light and dark.
