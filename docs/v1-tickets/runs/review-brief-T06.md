# Review brief — T06 (`/v1/ws` relay + D16/D17)

Fresh reviewer; you did not write this code. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`,
`docs/spec.md` §2 + Q4/Q7/Q7d/Q34, `docs/protocol.md` §10, §11, §12, then `docs/v1-tickets/runs/T06/build-brief.md` and
`build-report.md`. Diff: `git diff HEAD -- server docs/protocol.md` + untracked files under `server/` (note the staged rename
`hermes/fakehermes_test.go` -> `hermes/hermestest/fake.go`). IGNORE `docs/design/`, `docs/spec.md`, `tickets.md`,
`runs/TD1/` (another ticket). PM gates GREEN (`runs/pm-gate-6`, 201 Go tests). PM mutations caught: redaction fast path
forced -> 5 redact tests fail; revoke hook removed -> `TestRelayRevokeClosesWithinOneSecond` fails.
Edit nothing but your report; no git state changes; you may run `cd server && go test -race -count=1 ./...`. Never connect to the real Hermes.

Focus (security boundary + concurrency): auth on upgrade (token only in the header? query-string token refused?); Origin /
cross-site WebSocket from a browser on the LAN (CSWSH) — is it possible with a Bearer header requirement?; redaction
completeness vs §10 (nested paths, `events[]`, a field name inside message TEXT must not be altered, invalid JSON frames);
pump shutdown and goroutine leaks; write path for large Hermes frames and a slow phone (unbounded buffering? backpressure on
Hermes?); rate limit / per-device cap races; revoke within 1 s under load; shutdown 1001 bound; logs never contain frame
bodies/tokens; `desktop_local: true` from the phone — should the relay strip or refuse it (Hermes then treats the phone as the
local Desktop)? Recommend. D16/D17 really done.

Severity BLOCKER / MAJOR (proven) / MAJOR (unproven) / MINOR / NIT with file:line, why, fix. Report ≤50 lines at
`docs/v1-tickets/runs/T06/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
