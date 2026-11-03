# Review brief — T09b (T09 review fixes)

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/design.md`,
`docs/protocol.md` §11/§12, `docs/v1-tickets/runs/T09/review-report.md` (the findings being fixed), `runs/T09b/build-brief.md` +
`build-report.md`. Diff: `git diff HEAD -- android server/internal/relay/sock_unix_test.go` + untracked files in android/.
PM gates GREEN (`runs/pm-gate-12`: 234 Go, android 130). PM mutation: merge replaced by fresh-only -> 6 tests fail.
Edit nothing but your report; no git state changes; do not run gradle. A parallel worker edits `server/cmd` and `server/internal/app`
(T21) — ignore those.

For each T09 finding: FIXED / PARTIAL / NOT FIXED. Focus: merge order/cap/keep correctness and that a refresh can never add an unsafe
address or change the pin; cancellation of `/v1/addresses` (OkHttp Call.cancel on coroutine cancel, no leaked calls); cancel vs close
and the suspected OkHttp TLS-handshake cancel gap (is it real? does it matter with a 10 s read timeout?); exception handler cannot
swallow CancellationException or loop; NetworkWatch snapshot logic (Wi-Fi->Wi-Fi, VPN up/down, no storm of retries); the accepted
"save failure keeps the link" behaviour; strings EN/ID; design.md conformance of the banner. Only BLOCKER and MAJOR findings trigger a fix
round (project rule since 2026-11-03); still list MINOR/NIT. Report ≤35 lines at `docs/v1-tickets/runs/T09b/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
