# TP1a — `GET /v1/pc-status` (server, Q78)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. The contract is `docs/protocol.md` §16 (PM-written; implement it exactly;
if something in it is wrong or impossible, change §16 and say so). Read `PROJECT.md` hard rules, `server/internal/httpapi/httpapi.go`
(how `GET /v1/addresses` is wired with `requireDevice`, :91), the 4503 Hermes check in `server/internal/relay/**`, the listener manager
(`server/internal/listen/manager.go`) for "a Tailscale listener is bound".

## Do
New package `server/internal/pcstatus`: readers for `/sys/class/power_supply/*` (type Battery: capacity, status Charging/Full;
ignore ADP*/AC), `/proc/stat` (CPU from two samples; a background sampler started on demand, stopped 60 s after the last request),
`/proc/meminfo` (MemTotal - MemAvailable), `statfs` of the user's home, `/proc/uptime`, Hermes running (reuse the relay's check, no new
socket logic), Tailscale up (listener manager). Each reader takes a root path so tests use a fake `/sys` + `/proc` tree under `t.TempDir()`.
Handler + route + rate limit like the other device routes. Tests: every reader incl. missing/garbage files -> null, the sampler lifecycle,
the handler auth (401 without token), JSON shape exactly as §16.

Hard rules: no commits; never git checkout/restore/stash/reset. No adb. Never touch port 8760 or the demo server; never connect to the
real Hermes. Another worker builds the other side in parallel: edit only your side.
Files: `server/**`, `docs/protocol.md` §16 (only if a correction is needed), `docs/v1-tickets/runs/TP1a/build-report.md`.
Verify: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TP1a` exits 0. Report ≤12 lines.
