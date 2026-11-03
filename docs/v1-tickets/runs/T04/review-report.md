# T04 review — Hermes connector (`server/internal/hermes`, go.mod/sum, protocol.md §1.2)

Ran: `cd server && go test -race -count=1 ./...` → all packages ok (exit 0); `gofmt -l internal/hermes` empty; `go vet` ok.
Scratch probes (copy of the package + fake under $TMPDIR, deleted afterwards; repo untouched, real Hermes never contacted).

## Checked and OK
- Ticket never logged or put in errors: Dial logs only `api_origin` (conn.go:42); upgrade errors are status-only or `scrub`bed (conn.go:59-62); ControlError carries Hermes' text only.
- No Origin: coder/websocket sets none; Do strips it (rest.go:22). Subprotocols are the exact pair; compression off.
- Ticket shape matches Hermes Desktop: `interactive` + `scope:host` for the launch profile (`local-gateway.ts:615-620`). The launch-profile
  match `hermes_home` == `served_profiles[].home` holds: both are resolved paths (`gateway/status.py:247-249,795`, `hermes_home_key`).
- Fail-closed order: protocol, then state/capability, then loopback `api_origin`, all before any ticket is minted; mint echo checks match protocol.md §1.1.
- Re-identifies on every Dial/Do. DefaultTransport's ProxyFromEnvironment skips loopback, and checkOrigin requires a loopback IP.
- Ack matching on id `1` is robust: it is the only client request before the ack, server-request ids are strings (`srq-…`), and Python echoes the int.
- No goroutine leaks: 20 Dial/Close cycles left goroutines at 4→4. Cancelling the Dial ctx after Dial returns does not kill the socket (probe).
- D6 text and cites are correct (`run_api.py:429,441,442-446`; `web_server_boundary.py:160-171`). The only new dep is `coder/websocket v1.8.15`, which has no deps of its own.

## Findings
- **MAJOR (proven from source, latent: no caller yet)**: rest.go:15 / control.go:203: `Do` always mints `native-http` for the launch profile.
  Hermes binds a REST request to the ticket's profile. Implicit selectors resolve in the ticket's home, and any other `profile` gets
  403 `profile_scope_mismatch` (`native_http.py:18-31,47-58,66-71,297-300`). So REST cannot reach `pm` or `aristotle-stocks`
  (spec Q22/Q25), and `/api/sessions/{id}/messages` for a pm session reads the launch DB. Fix: `Do(ctx, profile, req)` (or `DoAs`) that
  resolves a served profile by name/home and mints for that `profile_id`. Add a fake test minting for `/profiles/pm`. Must land before the first REST ticket.
- **MINOR (proven, scratch)**: rest.go:30 / hermes.go:76: the client follows redirects and re-sends the spent ticket. A 307 after redeem
  returned 401 (`redeemRefused=1`). Go also forwards custom headers to a different host. Fix: `CheckRedirect` returning `http.ErrUseLastResponse` on the connector's client (clone a caller-supplied one).
- **MINOR (proven, scratch)**: conn.go:79-98: coder/websocket CLOSES the connection when a Read/Write ctx ends. A 50 ms read timeout,
  then Write → `use of closed network connection`. `Conn` docs don't say so, and T06 could use per-read timeouts. Fix: document it on `Read`/`Write`.
- **MINOR**: conn.go:57-75: the handshake has no timeout of its own and `pending` is unbounded. `Dial(context.Background())` against a
  Hermes that never acks blocks forever while buffering broadcasts. Fix: wrap it in `context.WithTimeout(…, 10s)` and cap pending (e.g. 64).
- **MINOR**: conn.go:79-87: close codes are not surfaced, so T06 must import coder/websocket to read 1013/4403 and the package goal is lost.
  Fix: a `CloseStatus(err) (int, bool)` helper, or sentinels `ErrDraining` (1013) and `ErrForbidden` (4403). Note: a pre-accept 1013/4403
  reaches the client as HTTP 403 (`ErrUpgradeRefused`), which looks the same as a bad ticket. Document that.
- **MINOR**: control.go:282-293 diverges from Hermes `resolve_client_socket_path` (`control_socket.py:124-133`), which tries the direct socket first and
  uses the pointer only if its target exists. A stale `gateway.sock.path` makes Aristotle report not-running while Hermes runs. The
  pointer target is not validated (Desktop checks `hermes-gw-<hash>/control.sock` and private perms, `local-gateway.ts:600-606`), and the
  server peer is never checked. Fix: direct socket first; check `syscall.GetsockoptUcred` uid == `os.Getuid()` on the control conn.
- **MINOR**: rest.go:25-29: Do forwards every caller header. If T06 clones a phone request, the phone's `Authorization` (device token)
  and cookies reach Hermes. Fix: strip `Authorization`, `Cookie`, `Proxy-*` (or allowlist the headers).
- **NIT**: control.go:240-248: the deadline is the ctx deadline even when it is longer than 5 s, and cancel without a deadline is ignored.
  Use `min(ctxDeadline, now+5s)` + `context.AfterFunc(ctx, conn.Close)`. `ReadBytes` reads the whole answer before the 1 MiB check.
- **NIT**: rest.go:15-21: `req.Body` is not closed on early errors (http.Client contract). conn.go:60: a 101 that fails verification reports "HTTP 101 refused".
- **NIT**: fake vs source. No envelope-key check (`control_socket.py:372`), no 30 s TTL. `strings.Contains` instead of exact token
  parsing (`web_server_boundary.py:163`). Acks any first frame. Sends one pre-ack frame, so buffering of >1 frame is untested. No
  1013/4403 close paths. Only the launch `profile_id` is accepted. None of these hides a current bug. Add a test with 2+ pre-ack frames.

VERDICT: PASS-WITH-FINDINGS
