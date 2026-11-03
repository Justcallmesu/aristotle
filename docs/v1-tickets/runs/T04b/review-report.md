# T04b review — Hermes connector fix round (`git diff HEAD -- server/internal/hermes`)

Ran: `cd server && go test -race -count=1 ./...` → all 10 packages ok (exit 0); `gofmt -l internal/hermes` empty; `go vet` ok.
I ran probes on a scratch copy under $TMPDIR and deleted it afterwards. Nothing in the repo changed and the real Hermes was never contacted.

## Previous findings
- MAJOR per-profile REST ticket: **FIXED**. `Do(ctx, profile, req)` → `resolveProfile` → mint for that `profile_id`, echo-checked (control.go:149).
  Matches `native_http.py:28-43,58,297-300`: the grant's `profile_id` is the home override, and selectors must resolve to it. An unknown profile mints nothing.
- MINOR redirects: **FIXED**. hermes.go:98 clones the client with `ErrUseLastResponse`. coder/websocket chains the old CheckRedirect (dial.go:89-99), so the upgrade does not follow redirects either.
- MINOR Read/Write cancel closes the conn: **FIXED** (documented on Conn, Read and Write). Checked: the per-read AfterFunc is cleared after each read
  (read.go:244-247), so `defer cancel()` of the handshake ctx after Dial does not kill the socket.
- MINOR handshake timeout / pending cap: **FIXED**. 10 s default, `Options.HandshakeTimeout`, 64 frames / 1 MiB → `ErrPendingOverflow`.
- MINOR close codes: **FIXED**. `CloseError` + `CloseCode` + `ErrDraining`/`ErrForbidden` via `Is`. The pre-accept 403 is documented (hermes.go).
- MINOR socket discovery: **FIXED**. It matches `resolve_client_socket_path` (`control_socket.py:121-133`): direct socket first, then the pointer only if
  its target exists, with the BOM stripped as `utf-8-sig` does. SO_PEERCRED uid check runs before anything is written.
- MINOR header forwarding: **PARTIAL**. Canonical keys are stripped, but non-canonical keys survive (new finding 1).
- NIT control deadline/cancel: **FIXED** (min(ctx, 5 s), `AfterFunc(conn.Close)`, LimitReader cap). NIT body close / 101 message: **FIXED**.
- NIT fake fidelity: **PARTIAL** (as reported). Multi-frame pre-ack, 1013/4403 and a second profile were added. Envelope-key check, TTL and exact token parsing are still missing.

## New findings
- **MINOR (proven, scratch)**: rest.go:54-58 uses `out.Header.Del(name)`, and `Del` canonicalises its argument. A key that was set
  non-canonically (`req.Header["authorization"]`, `["x-hermes-gateway-ticket"]`) is not deleted. Probe result: Hermes received
  `Authorization: Bearer phone` and the ticket values `["tkt-1-1","forged"]`. Real Hermes would answer 401 because `len(tickets) != 1` (`native_http.py:200`).
  Requests parsed by net/http are always canonical, so this only happens when a caller builds the map directly. Fix: `delete(out.Header, name)`, plus a test with a lowercase map key.
- **MINOR (proven from source, latent)**: control.go:112 resolves `"default"` to the *launch* profile. In Hermes, `default` is the
  default root (`profiles.py:437-441` `get_profile_dir`). If the gateway was launched as a named profile, `Do(ctx,"default",…)` mints for that
  profile, and the root profile can then only be reached by its home path. Hermes also lowercases names (`normalize_profile_name`). Aristotle
  rejects `PM`/`Default` with `ErrUnknownProfile`, so it fails closed. Today's setup launches from the root (protocol.md:26), so nothing breaks now.
  Fix: keep `""` = launch; map `default` (case-insensitive) to the served profile whose home is not under `profiles/`; lowercase names.
- **NIT**: rest.go:54-60 uses a denylist. Hermes trusts loopback for `X-Forwarded-*` once `proxy_headers` is on (gated mode,
  `config_defaults.py:1000-1001`, `web_server.py:1061`). Aristotle is a loopback peer, so a cloned phone `X-Forwarded-For/-Proto/-Prefix` would be trusted.
  Hermes is not in gated mode today. Fix: also drop `Forwarded`, `X-Forwarded-*` and `X-Real-Ip`, or switch to an allowlist when T06 lands.
- **NIT**: control.go:230 `decodeEnvelope(answer, sizeOK bool)`: the only caller now passes `true`, so the parameter and its branch are dead.
- **NIT**: `TestCloseCodesSurface` only covers a close before the ack (during Dial). No test covers 1013/4403 reaching `Conn.Read` after
  attach, which is the case T06 will see. The code path is the same (`readWS`→`mapClose`), so this is cheap to add.

Checked and OK: the ticket never appears in logs or errors (`scrub` is also used for the 101-verification error). Body close on all error paths: the
transport closes it on `client.Do` errors and a double close is harmless. `ownUID` swap is safe (the package has no `t.Parallel`). Mutation coverage
from the PM (`TestDoMintsTicketForTargetProfile`) holds. The overflow check counts the 65th frame as overflow, i.e. 64 are kept.

VERDICT: PASS-WITH-FINDINGS
