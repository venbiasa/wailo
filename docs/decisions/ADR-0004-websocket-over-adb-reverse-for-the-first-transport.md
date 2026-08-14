---
adr: 0004
title: WebSocket over adb reverse for the first transport
date: "2026-07-05"
status: accepted
date_source: git-commit
---
# ADR-0004 — WebSocket over adb reverse for the first transport

- Context: Need a simple, reliable device->desktop channel for Android during early development.
- Decision: WebSocket with device as client, desktop as server on :8899, forwarded via `adb reverse`
  per device. mDNS/wifi discovery and iOS transport deferred.
- Consequences: Zero network config for USB/adb-connected devices; multi-device supported by running
  the reverse per serial. Devices not adb-reachable are out of scope until wifi/mDNS lands.
