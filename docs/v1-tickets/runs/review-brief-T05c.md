# Review brief — T05c (hardening D9, D10, D12, D13, D14, D15)

Fresh reviewer; you did not write this code. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`,
`docs/v1-tickets/runs/T05c/build-brief.md` + `build-report.md`, board rows D9–D15 in `docs/v1-tickets/tickets.md`.
Diff: `git diff HEAD -- server docs/protocol.md` + untracked `server/internal/listen/bind_*`. PM gates GREEN
(`runs/pm-gate-5`, 162 Go tests). PM mutations: SO_BINDTODEVICE made a no-op -> only `TestSetDeviceUnknownInterfaceFails`
fails (is a positive test missing, e.g. getsockopt after bind?); header allowlist widened -> 3 tests fail.
Edit nothing but your report; no git state changes; you may run `cd server && go test -race -count=1 ./...`.

Check each D-item FIXED / NOT FIXED / PARTIAL; new findings BLOCKER / MAJOR (proven) / MAJOR (unproven) / MINOR / NIT with
file:line. Focus: the interface pin really applies to every LAN and Tailscale listener (and survives the 10 s re-scan
rebinding); IPv6 Tailscale listener pinned too; nothing in the lock-before-cert reorder lets a second server corrupt state;
header allowlist keeps what Hermes REST needs (Content-Length/Type for POST bodies). Report ≤30 lines at
`docs/v1-tickets/runs/T05c/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
