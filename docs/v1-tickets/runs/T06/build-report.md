# T06 build report: `/v1/ws` relay + D16/D17

**Files.** New: `server/internal/relay/{relay.go,session.go,limits.go,redact.go,relay_test.go,redact_test.go}`,
`server/internal/httpapi/relay_route_test.go`, `server/internal/hermes/hermestest/fake.go` (moved with `git mv` from
`hermes/fakehermes_test.go`, so the rename is **staged**; nothing committed). Changed: `httpapi/{httpapi.go,logging.go}`
(route, D4), `devices/devices.go` (+`Has`, `OnRevoke`), `app/serve.go` (connector, hub, 1001 on shutdown), `app/cli.go`,
`hermes/rest.go`, `listen/manager.go`, tests in `app/ hermes/ devices/ listen/`, `docs/protocol.md` (§12 + one §11 line).
Fake: the move only exports names (fields `Home Ln Mu Minted FirstFrames …`, `New URL Restart SetRuntime`). Two additions
on top: `RestBodies/RestLengths` (needed for the item 12 POST test) and `ReadLimit` (the 1 MiB echo test; real Hermes allows 384 MiB).
**Close codes (phone).** 1000/other = Hermes' own (forwarded) · 1001 `going_away` · 1003 `text_frames_only` · 1009 frame >1 MiB
(library) · 1011 `pong_timeout` · 1013 `try_again` (ErrDraining or Hermes 1013) · 4401 `revoked` · 4429 `rate_limited` ·
4430 `too_many_connections` · 4502 `hermes_unavailable[: refused (4403) | : connection lost]` · 4503 `hermes_not_running` ·
4505 `hermes_protocol_unsupported (… : N)`. Table and rules: protocol.md §12.
**Design notes.** One Hermes Dial per phone socket. Each pump returns only when its own read fails. After a close is
requested, a pump keeps reading and throws frames away, so pongs and close frames are still handled. Two contexts:
`stopCtx` signals the close; `ioCtx` cuts both sockets 2 s after the close handshakes start. A device revoked between
auth and register is caught by a `Has` re-check after register. A frame that names a redacted field but does not parse
is dropped and counted; it is never forwarded. Capabilities (Hermes `methods_voice.py:522-541`): the phone need not send
any; if it sends some, they update only the keys it names. It must not send `desktop_local:true`. Not a stop condition.
The §10 paths match the captured frames.
**Tests (`-race`).** relay: `TestRelayHappyPath` (phone frame reaches the fake unchanged, shape A comes back
byte-identical, shape B comes back redacted, open/close audit lines, D4 line while the socket is open),
`TestRelayUnauthenticated`, `TestRelayDialErrorsBecomeCloseCodes` (4503/4505/1013/4502), `TestRelayForwardsHermesClose`
(1013, 1000, 4403→4502), `TestRelayRateLimit`, `TestWindowAllowsNPerPeriod`, `TestRelayFrameLimits` (1 MiB echo ok, +1 →
1009, binary → 1003), `TestRelayPerDeviceCap`, `TestRelayRevokeClosesWithinOneSecond`, `TestRelayDeviceGoneAtRegisterIsRevoked`,
`TestRelayShutdownClosesGoingAway`, `TestRelayKeepaliveClosesWithoutPong`, `TestRelayKeepaliveKeepsReadingPhoneOpen`,
`TestRelayNoGoroutineLeak` (5 before / 5 after 20 cycles), `TestRelayAuditLinesHaveNoFrameContent`, `TestRelayOverTLSWithHTTP2Enabled`,
`TestCloseMappings`; `TestRedact{ShapeAPassesByteForByte,ShapeBEvent,Results,EventsSinceElements,SystemPromptChars,OnlyListedPaths,
KeepsTextBytes,RefusesUnparsable,EveryCapturedFrame}` (real `docs/spikes/frames`; events[] synthetic). httpapi:
`TestRelayRouteNeedsDeviceToken`, `TestRelayRouteAbsentWithoutRelay`, `TestUpgradeLoggedWhileSocketOpen` (D4). app:
`TestShutdownClosesRelays`, `TestEveryListenerSharesRejectLog` (F3). devices: `TestOnRevokeRunsAfterRemovalOnly`. D16/D17:
`TestListenConfigPinsSocket` and `TestBindTCPPinsToEndpointInterface` read SO_BINDTODEVICE back (raw getsockopt; std
`syscall` has no getter); `TestBindTCPRefuses` now gives each case an interface and adds empty-name cases; `TestDoForwardsPostBody`.
`Content-Length` removed from the allowlist. Import grouping in `cli.go` and the long `rest.go` doc line fixed. App tests set
`HERMES_HOME` to a temp dir.
**Commands.** In `server/`: `gofmt -l .` empty (exit 0); `go vet ./...` 0; `go test -race -count=1 ./...` 0; relay
`-race -count=8` 0. Mutations run on a scratch copy: all 15 killed (BindTCP `listenConfig("")`, no
empty-iface check, `Rejects` dropped, no `closeRelays`, no D4 hook, revoke no-op, no `Has` re-check, no events[] redaction,
4403 not mapped, no rate check, no cap, no pong close, no 1001 on shutdown, ErrDraining unmapped, Content-Type not forwarded).
**Smoke test (real Hermes, read-only).** Temp XDG dirs, port 18763, `HERMES_HOME=~/.hermes`. Paired over Tailscale
(`201`); `/v1/ws` without a token gave `401`. Output: `first frame type: gateway.ready` · **`session.list sessions: 3`** ·
`frames received: 2 redacted field names seen: false`. Server log: `status=101`, open line, then the close line
(`close_code=1000 closed_by=phone methods="session.list=1"`); the token is not in the log. Server stopped and port 18763
is free. Token and pair files deleted.
**NOT VERIFIED.** LAN `wss://` on the real binary (only an httptest TLS+h2 server); a real Android/OkHttp client;
keepalive at the real 25 s/15 s (tests use ms); a real stalled phone (the 2 s cut is reasoned, not tested); real Hermes'
1013/4403 on an established socket; `dropped` > 0 on real traffic; a phone hanging during a failed dial at shutdown
(the library bounds it at ≤10 s; the hub returns after 5 s).
Not my changes, also in `git status`: `docs/spec.md`, `docs/v1-tickets/tickets.md`, `docs/design/`, `runs/TD1/`.
