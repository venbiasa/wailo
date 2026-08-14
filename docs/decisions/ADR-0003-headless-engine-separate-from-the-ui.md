---
adr: 0003
title: Headless engine separate from the UI
date: "2026-07-05"
status: accepted
date_source: git-commit
---
# ADR-0003 — Headless engine separate from the UI

- Context: Roadmap includes headless automation (Appium) and MCP, not just the desktop UI.
- Decision: `engine` owns transport + capture store + query/command API and is UI-agnostic. UI, CLI,
  and MCP are thin frontends.
- Consequences: Automation/MCP are additive, not forks. Slightly more indirection for the desktop app.
