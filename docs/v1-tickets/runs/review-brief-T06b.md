# Review brief — T06b (relay review fixes + desktop_local refusal, Q43)

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` Q43,
`docs/protocol.md` §12, `docs/v1-tickets/runs/T06/review-report.md` (the findings being fixed), `runs/T06b/build-brief.md` and
`build-report.md` (note the worker deliberately inspects ALL phone frames for desktop_local, not only those containing `capabilities`).
Diff: `git diff HEAD -- server docs/protocol.md` + untracked `server/internal/relay/{liveness,phoneframe}*.go`.
PM gates GREEN (`runs/pm-gate-7`, 207 Go tests). PM mutation: desktop_local detection disabled -> 2 tests fail.
Edit nothing but your report; no git state changes; you may run `cd server && go test -race -count=3 ./internal/relay/`.
Never connect to the real Hermes.

Check each T06 finding FIXED / NOT FIXED / PARTIAL. Focus: progress-based liveness cannot be gamed into never closing a dead phone
and cannot falsely close a slow one; 32 KiB fragmentation correct with coder/websocket (message boundaries preserved; text frames);
revoke 0.5 s cut + raw TCP close under TLS doesn't race with the pumps (double close, use after close); desktop_local refusal has no
bypass (escapes, duplicate keys, nested params, batch arrays `[...]` of JSON-RPC calls, non-object params) and does not refuse
legitimate frames; known-method allowlist complete enough that real Hermes method names are not all logged as `other`;
goroutine leaks. Severity BLOCKER / MAJOR (proven) / MAJOR (unproven) / MINOR / NIT with file:line. Report ≤40 lines at
`docs/v1-tickets/runs/T06b/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
