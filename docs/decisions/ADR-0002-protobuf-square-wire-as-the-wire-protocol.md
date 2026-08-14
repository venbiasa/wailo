---
adr: 0002
title: Protobuf (Square Wire) as the wire protocol
date: "2026-07-05"
status: accepted
date_source: git-commit
---
# ADR-0002 — Protobuf (Square Wire) as the wire protocol

- Context: Kotlin and Swift both need the same message types; traffic volume can be high.
- Decision: Define messages once in `protocol/**/*.proto`; generate with Wire (Kotlin now, Swift later).
- Consequences: Compact, evolvable, no cross-language model drift. Adds a codegen step. JSON rejected
  for drift risk and payload size.
