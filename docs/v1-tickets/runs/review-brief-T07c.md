# Review brief — T07c (HTTPS + pin on every listener, Q49)

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` §2 +
Q49, `docs/v1-tickets/runs/T07c/build-brief.md` + `build-report.md`. Diff: `git diff HEAD -- server android docs/protocol.md` + untracked
files there. PM gates GREEN (`runs/pm-gate-10`: 225 Go tests, android 87 tests). Edit nothing but your report; no git state changes;
never connect to the real Hermes; do not stop the demo server on 8760; do not run gradle.

Check: no remote listener can serve plain HTTP in any config/branch (including re-scan rebinds and missing cert); the cert is
unchanged for existing installs; app has no path to `http://`/`ws://` for a remote host (OkHttp config, network security config,
WebSocket, redirects); one pin per PC applied to every address; migration of stored `http://` entries is safe (cannot downgrade,
cannot keep a token without a pin); deleted code left no dead references or strings; the `relay/liveness.go` multi-layer unwrap
cannot loop or pick a wrong conn; docs §11/§12 match the code. Severity BLOCKER / MAJOR (proven) / MAJOR (unproven) / MINOR / NIT with
file:line. Report ≤30 lines at `docs/v1-tickets/runs/T07c/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
