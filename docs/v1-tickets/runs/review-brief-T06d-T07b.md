# Review brief — T06d (relay exact blocked writes) and T07b (app security + connector discovery)

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, your ticket's
`docs/v1-tickets/runs/<T>/build-brief.md` + `build-report.md`, and the review it fixes (`runs/T06c/review-report.md` or
`runs/T07/review-report.md`). Diff: `git diff HEAD` limited to your ticket's paths (+ untracked files there).
PM gates GREEN (`runs/pm-gate-9`). Edit nothing but your report; no git state changes; never connect to the real Hermes; do not
stop the demo server on port 8760. Severity BLOCKER / MAJOR (proven) / MAJOR (unproven) / MINOR / NIT with file:line.
Report ≤40 lines at `runs/<T>/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.

- **T06d** (`server/internal/relay|listen|app`, protocol §12): trackedConn correctness (SyscallConn write loop: partial writes,
  EINTR, deadlines, Close races, use after hijack); TLS path via `NetConn()` really finds the wrapper; HTTP/2 or non-hijack
  requests unaffected; TCP_NOTSENT_LOWAT side effects on throughput; no goroutine/fd leak. You may run
  `cd server && go test -race -count=3 ./internal/relay/ ./internal/listen/ ./internal/app/`.
- **T07b** (`android/**`, `server/internal/hermes/**`, protocol §1.1 note by PM): D21 VPN binding — can tailnet traffic still
  leave over Wi-Fi/cellular (DNS, WebSocket upgrade, OkHttp connection reuse across networks, VPN that does not route the
  address, a non-Tailscale VPN routing 100.64/10)? `Proxy.NO_PROXY` everywhere? D22 wipe policy (which exceptions wipe),
  threading, SessionRepository tests; D19; D18 discovery vs Hermes source (`/home/justcallmesu/.hermes/hermes-agent/`, read-only)
  and the "launch profile becomes pm" consequence. Do not run gradle.
