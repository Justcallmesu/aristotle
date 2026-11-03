# Review brief — wave 4: T04b, T05b (fix rounds)

Fresh reviewer; you did not write this code. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`,
your ticket's `docs/v1-tickets/runs/<T>/build-brief.md` + `build-report.md`, and the review it fixes
(`runs/T04/review-report.md` or `runs/T05/review-report.md`).
Diff: T04b = `git diff HEAD -- server/internal/hermes`; T05b = everything else uncommitted under `server/` and
`docs/protocol.md` (T05 itself was never committed, so review the whole T05+T05b listener/pairing/auth code, focusing on
the fixed areas). PM gates GREEN (`runs/pm-gate-4`, 154 Go tests). PM mutations caught: T05b Tailscale-by-TUN ->
`TestSelectTailnetRangeNeedsTailscaleTun`; T04b per-profile ticket -> `TestDoMintsTicketForTargetProfile`.
Edit nothing but your report; no git state changes; you may run `cd server && go test -race -count=1 ./...`.

For each finding of the previous review: FIXED / NOT FIXED / PARTIAL. New findings: BLOCKER / MAJOR (proven) /
MAJOR (unproven) / MINOR / NIT with file:line, why, fix. Report ≤45 lines at `runs/<T>/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.

- **T04b**: `Do` profile resolution vs Hermes `hermes_cli/dashboard_auth/native_http.py` (read-only source at
  `/home/justcallmesu/.hermes/hermes-agent/`); header stripping complete; redirect handling; handshake timeout and buffer cap;
  CloseCode mapping; socket discovery order + foreign-uid refusal. Never connect to the real Hermes.
- **T05b**: TUN/tailscale classification cannot be bypassed (name-only? tun-only? IPv6 ULA on other ifaces?); physical-NIC
  rule and `lan_interfaces` override; subnet peer gate (IPv6 LAN? mapped addresses?); per-IP lockout + global cap
  correctness and memory growth; per-request read deadline really does not affect other routes; the shared-tls.Config race
  fix; control-socket lock file; `docs/protocol.md` §11 matches the code exactly (T07 builds from it).
