# Review brief — T01 (spike docs), T02 (android shell), T03 (server skeleton)

You are a fresh reviewer. You did not write this code. Repo: `/home/justcallmesu/project/personal/Aristotle`.
Read `PROJECT.md`, `docs/spec.md`, and the build brief + build report for YOUR ticket in `docs/v1-tickets/runs/<T>/`.
Gates already GREEN by the PM (`docs/v1-tickets/runs/pm-gate-1/gate-summary.txt`). Do not edit any file except your
report. No git state changes. No commits.

Severity: BLOCKER (wrong behaviour, security hole, breaks a spec rule, or a later ticket will build on a false fact),
MAJOR (proven quality/design defect; give the proof), MAJOR (unproven) (suspicion you could not prove), MINOR, NIT.
Each finding: file:line, what, why it matters, suggested fix. Report at most ~50 lines to
`docs/v1-tickets/runs/<T>/review-report.md`, ending with a verdict line: `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.

## T01 focus
The spike code is throwaway: do not review its style. Review the DOCUMENTS later tickets will rely on:
`docs/protocol.md` and `docs/spikes/v0-attach.md`. Spot-check at least 8 concrete claims (frame shapes, method names,
REST ticket header, profile selection, approval/clarify events, session.info system-prompt field, terminal = Desktop-local PTY)
against Hermes source `/home/justcallmesu/.hermes/hermes-agent/` and the captured frames `docs/spikes/frames/`.
Report each as CONFIRMED / WRONG / UNCLEAR with the source line. Also check frames contain no tickets, tokens or other
sessions' content. Do not connect to Hermes yourself.

## T02 focus
Build config correctness (applicationId, SDK levels per spec Q27/Q33, version catalog use), Compose/M3 setup, string
resources EN/ID (no hard-coded UI strings; Indonesian reads naturally), adaptive icon incl. monochrome layer, .gitignore
coverage (build outputs, local.properties, keystores), test is meaningful, nothing that blocks a foreground service or
networking later. Note the auto-downloaded build-tools 36.0.0 (AGP requirement) — assess, do not fix.

## T03 focus
Code shape per PROJECT.md (small named functions, std lib, package roles), config parsing (unknown keys, defaults, XDG
fallbacks), loopback-only guard, graceful shutdown, state dir perms, logging never leaks headers/bodies, tests guard real
behaviour, and `docs/v1-tickets/gates.sh`: correct failure propagation with `set -euo pipefail` (does a failing step still
write the summary and exit non-zero?), runs from any cwd, never edits sources. You may run `bash docs/v1-tickets/gates.sh /home/justcallmesu/.hermes/profiles/pm/cache/scratch/review-gate` and server `go test` commands.
