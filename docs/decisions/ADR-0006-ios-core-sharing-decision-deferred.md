---
adr: 0006
title: iOS core-sharing decision deferred
date: "2026-07-05"
status: accepted
date_source: git-commit
---
# ADR-0006 — iOS core-sharing decision deferred

- Context: iOS interceptor must be native; only `core` could be shared, via Kotlin/Native.
- Decision: Build Android first with `core` in KMP; keep `core`'s surface small and port-based; decide
  native-Swift vs Kotlin/Native-framework for iOS when iOS work starts.
- Consequences: No premature commitment; protobuf already carries the contract cross-language.
