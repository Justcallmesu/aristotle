# T06d build brief — relay hardening (T06c review)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/T06c/review-report.md` (findings 1, 3,
5, 6), board row D20, `docs/protocol.md` §12, then `server/internal/relay/` and `server/internal/app/serve.go` / `internal/listen`.

## Fix
1. Replace the 10 ms write-duration threshold with exact blocked-write detection, as the reviewer described: give the relay access
   to the raw TCP conn under TLS (wrap the listener passed to `ServeTLS`/`Serve`, keep a map or context value from the TLS conn to
   the raw conn), write through `SyscallConn().Write`, and record "blocked" when the callback returned false at least once (EAGAIN).
   If that turns out impossible without rewriting the HTTP server, STOP and report. Keep the T06c tests; add one that runs under a
   CPU/GC load generator and still closes a dead phone at ping+pong.
2. Set `TCP_NOTSENT_LOWAT` ≈ 128 KiB on accepted phone sockets (Linux; no-op elsewhere). Test the option is set (getsockopt).
3. `docs/protocol.md` §12: clients must answer every ping; the app must hand incoming frames off without blocking > 15 s.

## Hard rules
Files: `server/internal/relay/**`, `server/internal/listen/**`, `server/internal/app/**`, `docs/protocol.md` §12,
`docs/v1-tickets/runs/T06d/build-report.md`. Not `server/internal/hermes/**` (T07b in parallel). No new modules. Never connect to
the real Hermes. No commits. Do not stop or touch the demo aristotle-server process running on port 8760.

## Verify
`cd server && gofmt -l . && go vet ./... && go test -race -count=1 ./...` (if only internal/hermes fails, T07b is mid-edit: say so);
relay `-race -count=5`.

## Report
`docs/v1-tickets/runs/T06d/build-report.md`, ≤25 lines.
