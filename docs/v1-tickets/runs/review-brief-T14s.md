# Review brief — T14s (supersede on another listener, close 4409)

Fresh reviewer. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/protocol.md` §12, `docs/v1-tickets/runs/T14s/build-brief.md`
+ `build-report.md`. Diff: `git diff HEAD -- server/internal/relay` (ignore android/ and protocol.md §6 — another ticket).
PM gate GREEN `runs/pm-gate-19` (257 Go); mutation (never supersede) fails 2 tests. Edit nothing but your report; no git state changes.
Check: lock order (requestClose under hub.mu — can stop() or the session's own unregister path take hub.mu and deadlock?), the superseded
session's later unregister after it was already deleted from the set (no double-delete side effects, metrics/log once), races with revoke and
shutdown, cap semantics, log fields, test determinism under -race. Only BLOCKER/MAJOR trigger a fix round. Report ≤20 lines at
`docs/v1-tickets/runs/T14s/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
