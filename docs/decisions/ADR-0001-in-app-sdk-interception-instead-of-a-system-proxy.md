---
adr: 0001
title: In-app SDK interception instead of a system proxy
date: "2026-07-05"
status: accepted
date_source: git-commit
---
# ADR-0001 — In-app SDK interception instead of a system proxy

- Context: The reference experience (a proxy debugger) requires certificate install and device-wide
  proxy config. We want lower-friction capture and to run in automation/CI.
- Decision: Capture inside the app via a native interceptor (OkHttp on Android; URLProtocol/URLSession
  on iOS), streaming to a desktop app.
- Consequences: No cert/proxy setup. Only traffic through the instrumented client is captured (e.g.
  OkHttp), not arbitrary system traffic. Requires integrating the SDK into the target app.
