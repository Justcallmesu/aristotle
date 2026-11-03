# T14s review (D34 supersede on other listener, 4409)

Checked the diff, `relay.go`/`session.go`, protocol §12, and ran `go test -race -count=5 ./internal/relay/` (ok, 70 s).

- **Lock order: OK.** `requestClose` takes only `closeMu` and calls `s.stop()` (context cancel). It never takes `hub.mu`. `revoked` already calls it under `hub.mu`, so this is the same pattern. Teardown and `unregister` run on the session's own goroutine, after `register` has released the lock. No deadlock path.
- **Later unregister: OK.** `delete` on a missing key is a no-op. `byDev[dev]` is the same map and now holds the new session, so the `len==0` cleanup cannot drop it. `wg.Done` fires exactly once per session. `relay superseded` is logged once, in `register`. `relay closed` is logged once, with `close_code=4409 closed_by=server`.
- **Revoke and shutdown: OK.** Sessions already closing (`closeState` `by != ""`) are skipped, so revoke and shutdown keep their codes. A session removed from the set by supersede can still be missed by a later `Shutdown` snapshot or `revoked`. But it is already stopped and tears down on its own, within `closeGrace`, and `wg` still waits for it.
- **Cap semantics:** the cap counts only non-superseded sockets, so a switch is never refused with 4430. Superseded sockets still hold a goroutine and a Hermes upstream until teardown finishes (up to `closeGrace`). This is a brief, bounded overshoot of live resources, not a correctness bug.
- **Log fields:** `device_id`, `old_listener`, `new_listener` match the brief. `Log.Info` runs under `hub.mu`. This is a minor concern for a slow log sink, and `register` already logs nothing else under the lock.
- **Tests:** 3 new tests plus the mutation check (build report: never supersede fails 2 tests). All reads use `wantClose`/`waitFor` with timeouts, no sleeps. Stable under `-race -count=5`.
- **Docs:** the §12 4409 row matches the code. The reason column reads `superseded, reason superseded by a newer connection`, slightly redundant but harmless.
- **App flip-flop (confirmed, not a T14s defect):** the app maps 4409 to Lost and reconnects. If both listeners are reachable, two sockets could supersede each other repeatedly. The suggested D-row, "app treats 4409 as no-reconnect", is worth adding to tickets.md.

No BLOCKER or MAJOR findings. Findings: one MINOR (log under lock), one NOTE (D-row for the app).

VERDICT: PASS-WITH-FINDINGS
