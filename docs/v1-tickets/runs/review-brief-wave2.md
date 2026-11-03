# Review brief — wave 2: T01b (docs), T02b (android config), T03b (server)

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, then for
YOUR ticket `docs/v1-tickets/runs/<T>/build-brief.md`, `build-report.md`, and the previous review it fixes
(`runs/T01|T02|T03/review-report.md`). Diff: `git diff HEAD -- <paths>` plus untracked files under your ticket's paths.
PM gates GREEN: `docs/v1-tickets/runs/pm-gate-2/gate-summary.txt`. Edit nothing but your report. No git state changes.

Severity BLOCKER / MAJOR (proven, with proof) / MAJOR (unproven) / MINOR / NIT; each with file:line, why, fix.
Report ≤40 lines at `docs/v1-tickets/runs/<T>/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
For each finding of the previous review the ticket claims to fix, say FIXED / NOT FIXED / PARTIAL.

- **T01b** (`docs/protocol.md`, `docs/spikes/v0-attach.md`): spot-check ≥6 new claims (session.info shapes and keys,
  frequency, ticket params/errors, 403 path, redaction field paths) against Hermes source
  `/home/justcallmesu/.hermes/hermes-agent/` and `docs/spikes/frames/`. Do not connect to Hermes.
- **T02b** (`android/**`): SDK 36 + versions consistent, `sdkDownload=false`, wrapper checksum equals the official Gradle
  9.8.2 bin sha256, launch theme light/night correct for an M3 Compose app, icon folder rename still resolves, no lint
  suppressions. Do not run gradle.
- **T03b** (`server/**`, `docs/v1-tickets/gates.sh`): state-dir enforcement (chmod existing, refuse symlink/other owner,
  TOCTOU between Lstat and Chmod?), strict/duplicate key detection correctness (nested objects? whitespace?), gate
  FAIL vs SKIP logic, logging of route pattern without query. You may run `cd server && go test -race -count=1 ./...`.
