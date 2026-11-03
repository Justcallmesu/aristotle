# T04b build report — Hermes connector review fixes

Only `server/internal/hermes/**` edited (hermes.go, rest.go, conn.go, control.go, fakehermes_test.go, hermes_test.go). No go.mod change, no commits, real ~/.hermes untouched.

## Findings
1. MAJOR per-profile REST ticket: FIXED. `Do(ctx, profile, req)`; profile = name / profile_id / home, ""/"default" = launch; mints for that `profile_id` (Hermes binds REST to the ticket profile: `hermes_cli/dashboard_auth/native_http.py:18-31,47-58,66-71,297-300`). Unknown profile -> `ErrUnknownProfile`, nothing minted. Tests: `TestDoMintsTicketForTargetProfile`, `TestDoUnknownProfileMintsNothing`.
2. No redirects: FIXED (client clone with `ErrUseLastResponse`). `TestDoDoesNotFollowRedirects`.
3. Strip headers: FIXED (Authorization, Cookie, Proxy-Authorization, Origin, any X-Hermes-*). `TestDoStripsCallerCredentialHeaders`.
4. Ack timeout + pre-ack cap: FIXED. `Options.HandshakeTimeout` (default 10 s); cap 64 frames / 1 MiB -> `ErrPendingOverflow`. `TestDialAckTimeout`, `TestDialPreAckOverflowFails`.
5. Close codes: FIXED. `CloseCode(err) (int, bool)`, `*CloseError`, sentinels `ErrDraining` (1013) / `ErrForbidden` (4403). Pre-accept refusal still = `ErrUpgradeRefused` (HTTP 403), documented. `TestCloseCodesSurface`.
6. Discovery: FIXED. Direct `gateway.sock` first, then pointer only if its target exists (`gateway/control_socket.py:122-133`); peer uid checked via SO_PEERCRED -> `ErrForeignSocket`. `TestSocketDiscoveryPrefersDirectOverStalePointer`, `TestStalePointerWithoutDirectIsNotRunning`, `TestForeignUIDSocketRefused` (uid faked via `ownUID`; no real foreign-uid socket test).
7. Read/Write cancel-closes docs: FIXED (doc comments on Conn, Read, Write). No test (doc only).
8. NITs: FIXED. Control call honours cancel + min(ctx deadline, 5 s) + reads capped at 1 MiB+1 (`TestControlHonoursContextCancel`); body closed on early failure (`TestDoUnknownProfileMintsNothing`); 101 failure message reworded (no test); fake sends N pre-ack frames (`TestDialKeepsSeveralPreAckFrames`).
Not done: fake still lacks envelope-key check, 30 s TTL, exact token parsing (review said none hide a bug).

## API changes
- `Do(ctx, profile string, req)` (was `Do(ctx, req)`); internal `mintTicket` takes profileID.
- New: `Options.HandshakeTimeout`, `CloseCode`, `CloseError`, `ErrUnknownProfile`, `ErrForeignSocket`, `ErrDraining`, `ErrForbidden`, `ErrPendingOverflow`.

## Commands
- `cd server && gofmt -l .` -> empty, exit 0
- `go vet ./...` -> exit 0
- `go test -race -count=1 ./internal/hermes/...` -> ok, exit 0 (also `-count=5` ok)
- `go test -race -count=1 ./...` -> FAIL only in `internal/control` (`TestOtherUIDRefused`, T05b's in-progress package, not mine); all other packages ok.
