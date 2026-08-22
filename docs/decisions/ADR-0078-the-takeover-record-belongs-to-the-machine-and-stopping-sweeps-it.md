---
adr: 0078
title: The system proxy takeover is machine state, and stopping the proxy sweeps for it
date: "2026-08-22"
status: accepted
relations: fixes ADR-0075; extends ADR-0070
---
# ADR-0078 — The system proxy takeover is machine state, and stopping the proxy sweeps for it

- Status: Accepted; implemented as `wailoMachineStateDir()`, `ProxySetting.unlessOurs`, and `SystemProxyController.releaseStranded`, called from `ProxyController.stop` and from daemon start-up. Verify with `cd studio && ./gradlew :daemon:test`.
- Context: ADR-0075 held the undo for a takeover in two places, both narrower than the thing they described. The live copy is a field on the `SystemProxyController` that applied it, and the durable copy is a JSON file under `WAILO_HOME`. But `networksetup` changes *the Mac*, not a daemon's state directory, so both scopes are wrong in the same way. Observed on a real machine: every network service pointed at `127.0.0.1:9090` with no record anywhere, and the daemon actually serving that port reporting `system=off`. Stopping the proxy from there ran a restore that found nothing to replay and left the machine routing through a listener that had just been closed — no internet, and no daemon that would ever put it back. Two routes reach that state: a daemon that ends without restoring (a `SIGKILL`, or the `pkill` the build instructions themselves prescribe) hands the machine to a successor that cannot see the takeover; and a scratch daemon under a temporary `WAILO_HOME` writes its only record into a directory that is then deleted. A third made it permanent — `apply` re-read the machine, so a takeover applied on top of an unrecorded one snapshotted Wailo's own address as "what was there before", and chained the relay to itself.
- Decision:
  - **The record follows the resource it describes.** The snapshot lives at `~/.wailo/system-proxy.json` regardless of `WAILO_HOME`, because one Mac has one set of network settings. Isolating a run's capture state must not isolate the note saying how to put the machine back.
  - **Wailo's own listener is never a prior setting.** Snapshotting drops any service pointing at loopback on our port, so the state that made the breakage permanent cannot be recorded. This also removes the self-chain by construction: what the relay forwards through comes from the same sanitised snapshot.
  - **An outstanding record outranks the live machine.** `apply` adopts the file when one exists rather than re-reading, since the file was written before the first change and the machine has since been overwritten.
  - **Stopping the proxy sweeps the machine, not just the snapshot.** After the listener closes, any service still pointing at loopback on that port is switched off. The invariant is the one a user feels — with the proxy not running, nothing points at it — so it is checked against the machine's state rather than against a memory of having changed it. Start-up does the same when there is no record to recover.
  - **A live listener is left alone.** The sweep only fires when nothing answers on the port, so a second daemon cannot pull the settings out from under a takeover that is working.
- Alternatives considered:
  - **Restore on JVM shutdown and treat the rest as unreachable:** rejected — the hook does not run on `SIGKILL`, which is exactly the case, and the daemon's own build instructions tell contributors to `pkill` it.
  - **Refuse the takeover unless the daemon can guarantee it will undo it:** rejected — no process can promise that, and the guarantee that matters can be reconstructed afterwards from the machine's own state.
  - **Sweep on a timer while the proxy is off:** rejected — a background process editing network settings on a schedule is a worse failure mode than the one being fixed, and start-up plus stop already cover every transition.
  - **Reinstate the previous setting during a sweep instead of turning the proxy off:** rejected — a sweep runs precisely when there is no record of a previous setting. Off is the state every machine can reach, and it is the one the user is trying to get back to.
  - **Keep the record under `WAILO_HOME` and have scratch runs clean up after themselves:** rejected — it puts the correctness of the user's network on a test's exit path, which is the path that fails.
- Consequences:
  - A machine stranded by an older build is repaired by the next daemon start, without the user knowing a record was ever missing.
  - A scratch daemon that takes the system proxy over now writes to the real `~/.wailo`. That is the point — the record has to outlive the run — but it means `WAILO_HOME` no longer isolates this one file, and a scratch run that applies a takeover can have it recovered by the user's own daemon.
  - `SystemProxyController` now dials the proxy port to decide whether a takeover is live. It is loopback-only with a short timeout, and it is the only way to distinguish a working takeover from an abandoned one without a record.
