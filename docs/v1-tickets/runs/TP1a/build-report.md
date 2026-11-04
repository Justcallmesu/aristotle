# TP1a build report — GET /v1/pc-status

- Gate: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TP1a` exit 0 (gofmt, vet, test; 381 Go tests, 0 failed). pcstatus also passes `-race -count=3`.
- New `server/internal/pcstatus`: `readers.go` (battery, /proc/stat, meminfo, uptime, disk; each takes a root dir with sys/ + proc/), `pcstatus.go` (`Collector.Status`, on-demand CPU sampler), statfs for linux / other-OS stub.
- Route `GET /v1/pc-status` in `httpapi` (`Deps.PCStatus`, behind `requireDevice`, `no-store`); wired in `app/serve.go`, helpers `tailscaleUp` and `hermesRunning` in `app/controlcmds.go`.
- JSON is exactly §16: 8 keys, every one but `at` is null when unreadable. §16 unchanged.
- Battery: first type=Battery supply (ADP/AC and scope=Device ignored); charging = Charging or Full. CPU: first sample at start, second after ≤1 s, then every 5 s; sampler stops 60 s after the last request and drops its value (the first request after a stop returns null cpu).
- Hermes: `Connector.Identify` with a 2 s timeout. ErrHermesNotRunning → false, any other answer → true, timeout → null. This is the same error that gives relay close 4503. The check opens the control socket and mints no ticket.
- Tailscale: any `Active()` endpoint of kind tailscale. Non-Linux: the OS fields are null.
- No rate limit added: no other device route has one (`/v1/addresses`, `/v1/me` have none). The brief's "rate limit like the other device routes" therefore adds nothing. Flag it if you want one.
- Tests: every reader with missing and garbage input, sampler start/keep-alive/stop/restart, and 401 without a token (collector not called), 404 for POST, the 8-key shape and null shape. The tests use fakes; none touched port 8760, the demo server or the real Hermes.
- Not exercised: a live request against a running server. The pcstatus unit tests do not stat the real /sys or /proc, and `Hermes` is always a fake.
