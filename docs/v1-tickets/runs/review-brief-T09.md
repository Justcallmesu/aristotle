# Review brief — T09 (connection core)

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/design.md`,
`docs/protocol.md` §11/§12, `docs/v1-tickets/runs/T09/build-brief.md` + `build-report.md`, board rows D24/D26/D27/D28.
Diff: `git diff HEAD -- server android docs/protocol.md` + untracked files there. PM gates GREEN (`runs/pm-gate-11`: 234 Go tests,
android 111 tests). PM mutation: endpoint order forced LAN-first -> 4 tests fail. Edit nothing but your report; no git state changes;
never connect to the real Hermes; do not stop the demo server on 8760; do not run gradle (you may run `cd server && go test -race ./...`).

Focus: LinkManager concurrency (coroutine scopes, cancellation, two connects racing after a network change, leaked sockets/callbacks,
state emitted after cancel), backoff/jitter correctness and reset, close-code handling vs §12 table, foreground/background triggers
(lifecycle owner, no reconnect storm), NetworkCallback registration/unregistration, address refresh (`/v1/addresses`) trust: can a
response add a non-private / non-tailnet address or change the pin? (it must not), migration safety, D24 rule (Android 10–11), D26 pool
separation, server `/v1/addresses` auth + D27 trackedConn change, design.md conformance of banner/pages, EN/ID strings. Severity
BLOCKER / MAJOR (proven) / MAJOR (unproven) / MINOR / NIT with file:line. Report ≤40 lines at `docs/v1-tickets/runs/T09/review-report.md`,
last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
