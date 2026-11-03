# T06 build brief — `/v1/ws` relay: phone <-> Hermes live socket

route: deep (security boundary + concurrency)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` §2 rules, Q4, Q7, Q7d, Q10, Q34;
`docs/protocol.md` §1 (bootstrap), §2 + §2.1 (events, session.info shapes), §10 (relay redaction), §11 (phone API);
board rows D4 in `docs/v1-tickets/tickets.md`. Then the real code: `server/internal/hermes/` (Connector, Conn, CloseCode,
sentinel errors), `server/internal/httpapi/` (routes, `requireDevice`), `server/internal/devices/`, `server/internal/app/serve.go`.

## Goal
A paired phone opens one WebSocket to aristotle-server and gets a live Hermes session socket, exactly as Hermes Desktop has:
frames pass through unchanged in both directions except the Q34 redaction. Revoking the device or stopping the server
closes it. Hermes being down or speaking an unknown protocol gives the phone a clear close code instead of a hang.

## Non-goals
Any app code; REST proxying for sessions/cron (later tickets); the terminal (T19); multi-PC.

## Requirements
1. **Route** `GET /v1/ws` on LAN and Tailscale listeners, behind `requireDevice` (Bearer token in the `Authorization`
   header of the upgrade request). Accept with `github.com/coder/websocket` (already a dependency). Text frames only.
2. **Per phone connection**: one `hermes.Connector.Dial` (fresh ticket each time, no shared Hermes socket between phones).
   Dial failure -> accept the phone socket, then close it with an application code (define them, document in protocol.md §12):
   `4503 hermes_not_running` (ErrHermesNotRunning), `4505 hermes_protocol_unsupported` (ErrUnsupportedProtocol, reason
   includes the value), `1013 try_again` (ErrDraining / Hermes 1013), `4502 hermes_unavailable` (anything else). If
   Hermes closes an established relay, forward its close code/reason to the phone (4403 -> 4502 with reason; never leak ticket text).
3. **Pumps**: phone->Hermes and Hermes->phone in two goroutines; when either side ends, close the other and both goroutines
   exit (no leaks; test with goroutine counts like the T04 review did). Do not use per-read context timeouts (closing the
   context closes the conn — see `Conn.Read` doc).
4. **Redaction** (Hermes->phone only), exactly §10: by field name at the listed paths, including `result.events[]`
   elements. Fast path: if the raw frame contains neither `"system_prompt"` nor `"credential_hint"` bytes, forward it
   untouched (byte-identical). Otherwise decode, redact, re-encode preserving all other fields. Unit tests with the real
   captured frames in `docs/spikes/frames/` (shape A unchanged byte-for-byte; shape B redacted; create/resume results;
   events[] case synthetic).
5. **Limits** (phone->Hermes): frame ≤ 1 MiB (read limit), ≤ 600 frames/min per connection (close 4429 `rate_limited`),
   ≤ 4 concurrent relays per device (5th gets 4430 `too_many_connections`). Hermes->phone frames are not size-limited by
   the relay beyond the library default — set the phone-side write path to allow large frames (history can be big).
6. **Keepalive**: ping the phone every 25 s; close 1011 if no pong within 15 s. Do not ping Hermes (loopback).
7. **Revocation**: when a device is revoked, every live relay of that device closes with `4401 revoked` within 1 s.
   Add the smallest hook to `devices` needed (e.g. a subscribe/notify func); test it.
8. **Shutdown**: server shutdown closes all relays with 1001 `going_away` and waits (bounded, 5 s) for pumps to exit.
9. **Audit/logging**: one log line on open (device id, listener kind) and one on close (device id, duration, close code,
   frame counts in/out, bytes in/out, and counts per JSON-RPC `method` name seen phone->Hermes — names only). Never log
   frame bodies, tickets, tokens. **D4**: the request log line for `/v1/ws` is written at upgrade time, not at close.
10. **Docs**: `docs/protocol.md` new §12 "Relay socket (`/v1/ws`)": upgrade request, auth, close codes table, limits,
    keepalive, redaction pointer to §10, "no capabilities needed from the phone" or the exact rule (the connector already sends
    `client.capabilities`; say whether the phone should also send it and what happens if it does — check Hermes source).
11. **Fake Hermes for tests**: the fake lives in `internal/hermes/fakehermes_test.go` and is not importable. Move it to an
    exported test helper package `internal/hermes/hermestest` (move + export only, no behaviour change) so the relay tests
    can use it; keep the hermes package tests passing.

12. **D16/D17** (T05c review, `docs/v1-tickets/runs/T05c/review-report.md`): positive test that a listener socket carries
    SO_BINDTODEVICE (read back with `GetsockoptString` on `lo`); `checkBindable` refuses an empty interface name; a test that
    every listener gets the shared rejection-log limiter; a POST-with-body test through `hermes.Do` (drop `Content-Length`
    from the allowlist if it is a no-op); fix the import grouping in `app/cli.go`, the long doc line in `hermes/rest.go`, and
    add "interface pin applies on Linux only" to `docs/protocol.md` §11.

## Tests (`-race`)
Happy path end to end through a real listener handler: phone frame reaches fake Hermes unchanged, Hermes frame reaches phone
(redacted where needed); unauthenticated upgrade 401; each Dial error -> its close code; Hermes closes 1013 -> phone 1013;
rate limit; per-device connection cap; revoke closes within 1 s; shutdown closes with 1001; no goroutine leak after 20
open/close cycles; audit line has no frame content.

## Hard rules
- Files you may edit/create: `server/**` (no new modules beyond what go.mod has), `docs/protocol.md` (§12, plus the one §11 line from item 12),
  `docs/v1-tickets/runs/T06/build-report.md`.
- Tests never touch the real `~/.hermes`. No commits.
- One manual smoke test against the REAL Hermes is allowed at the end: start `serve` (temp XDG dirs, port 18763), pair a test
  device with curl, open `/v1/ws` with a small Go or `websocat`-free test client, call `session.list` only (read-only) and
  print the number of sessions. No prompts, no other methods. Stop the server; `devices` state is in the temp dir.

## Stop and report if
The Hermes source shows the phone must not send its own `client.capabilities` and the connector's cannot be shared; or the
redaction paths in §10 do not match a real frame.

## Report
`docs/v1-tickets/runs/T06/build-report.md`, ≤50 lines: files; close-code table; tests by name; commands + exit codes;
smoke-test output (session count only); NOT-VERIFIED list.
