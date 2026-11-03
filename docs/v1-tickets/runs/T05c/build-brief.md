# T05c build brief — hardening from the wave-4 reviews

route: std

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, the board rows D9, D10, D12, D13, D14, D15 in
`docs/v1-tickets/tickets.md`, and `docs/v1-tickets/runs/T04b/review-report.md` + `runs/T05b/review-report.md` for the details.
Then the code in `server/`.

## Fix (each with a test unless marked)
- **D9** `internal/hermes/rest.go`: header removal must catch non-canonical keys (delete by exact map key after canonical
  compare). Test with a lowercase `authorization` and `x-hermes-gateway-ticket` set directly in the map.
- **D10** same file: forward only an allowlist of request headers to Hermes (`Accept`, `Accept-Encoding`, `Content-Type`,
  `Content-Length`, `User-Agent`); everything else dropped. Test `X-Forwarded-For` is dropped.
- **D12** `internal/hermes`: fake Hermes closes an established `Conn` with 1013 and 4403 after dial; `Conn.Read` error maps
  via `CloseCode` / `ErrDraining` / `ErrForbidden`.
- **D13** `internal/listen`: bind each listener to its interface with `SO_BINDTODEVICE` (via `net.ListenConfig.Control` +
  `syscall`) so a tailnet-range packet arriving on wlan0 never reaches the Tailscale listener; same for LAN listeners. If the
  kernel refuses it without privileges (EPERM), STOP and report the exact error and kernel version (`uname -r`) instead of
  working around it. Test: the Control func sets the option (unit-test the function that builds it); manual: server still binds
  and serves on wlan0 and tailscale0.
- **D14** one shared rejection-log limiter for all listeners; the "5 wrong tries" text uses the constant; take the control
  lock BEFORE loading/creating the TLS cert; add the two missing details to `docs/protocol.md` §11 (invalid UTF-8 stripped
  from device names; a LAN interface must be up and running).
- **D15** remove the dead `sizeOK` parameter.

## Hard rules
- Files you may edit: `server/**` (no new modules), `docs/protocol.md` §11 only, `docs/v1-tickets/runs/T05c/build-report.md`.
- Manual runs: temp XDG dirs, port 18762, stopped afterwards. Never touch the real `~/.hermes`. No commits.

## Verify
`cd server && gofmt -l . && go vet ./... && go test -race -count=1 ./...`; manual serve shows the same binds as T05b
(wlan0 HTTPS, tailscale0 HTTP, loopback) and `curl` to `/healthz` on loopback works.

## Report
`docs/v1-tickets/runs/T05c/build-report.md`, ≤30 lines: D9–D15 -> FIXED (test) / STOPPED (why); commands + exit codes; manual output.
