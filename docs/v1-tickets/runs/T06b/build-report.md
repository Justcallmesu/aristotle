# T06b build report — relay review fixes

Files: `server/internal/relay/{session,relay,limits}.go`, new `liveness.go`, `phoneframe.go`, `liveness_test.go`,
`phoneframe_test.go`, `relay_test.go` (audit test); `docs/protocol.md` §12 only. No new modules, no real Hermes, no commits.
## Findings
1. **MAJOR slow phone cut — FIXED.** Messages >32 KiB go out as 32 KiB fragments (`pc.Writer`), so ping and close interleave.
   Liveness is now progress-based (`liveness.go`): if a write is in flight, 1011 fires only when no fragment went out for PongTimeout.
   Pings go out only when nothing moved for PingInterval, and a lost pong closes only if nothing moved for PongTimeout.
   Read progress counts bytes, via a hijacked-conn wrapper, so a slowly uploading phone (its pong stuck behind its upload)
   is not cut. While a phone frame waits for Hermes (`forwarding`), a lost pong does not count. Tests: `TestRelaySlowPhoneGetsEveryLargeFrame`
   (2 MiB/s reader, 2.5 MiB frames, PongTimeout 0.5 s, 128 KiB socket buffers) and `TestRelayKeepaliveClosesPhoneThatStopsReadingMidFrame` (1011).
2. **MINOR revoke on stalled phone — FIXED.** Revoke uses `revokeGrace` 500 ms. `kill` now also closes the phone's raw TCP conn
   (`tls.Conn.NetConn()` on LAN, so no close_notify wait). I found a real bug while testing: after `kill`, `pc.Close`'s handshake could
   still read for the library's own 5 s, because ioCtx does not reach it (4/16 hangs under load before the fix). §12 4401 row: a phone that
   has not read the 4401 within 0.5 s gets a TCP close. Test: `TestRelayRevokeCutsStalledPhoneWithinOneSecond` (asserts the write is stalled, then gone <1 s).
3. **MINOR secrets in method log — FIXED.** Only names in `knownMethods` (§1–§9 incl. `commands.catalog`, `gateway.ping`, `event`)
   are logged; anything else counts as `other`. "Catalog" was read as `commands.catalog`. Tests: token-shaped and `sk-proj-…` methods
   added to `TestRelayAuditLinesHaveNoFrameContent`; `TestInspectPhoneCountsUnknownMethodsAsOther`.
4. **NITs docs — FIXED.** §12: 64 MiB Hermes→phone limit, a larger message ends with `4502 … connection lost`; Origin is optional
   and, if sent, a mismatch gets 403; fragmentation note; keepalive rewritten.
5. Brief item 5 is replaced by item 6 (PM, Q43 = REFUSE).
6. **desktop_local REFUSE — DONE.** The relay decodes `map[string]json.RawMessage` with exact keys; `client.capabilities` plus a
   `desktop_local` key in params, with any value, gets `1008 desktop_local_refused` and is not forwarded. **One deviation, flag for PM:**
   I did not use a raw-`capabilities` prefilter, because `client.c\u0061pabilities` would bypass it (Python decodes the method too).
   The relay already decodes every frame for the audit, so the full decode adds no cost. Frames Go cannot parse but Python can
   (`NaN`, etc.) are refused if their bytes contain `capabilities` or `\u`. A decoy key such as `"Method"` cannot hide the real
   method. Tests: `TestRelayRefusesDesktopLocal` (plain, escaped, `false`; the normal capabilities frame passes) and `TestInspectPhoneRefusesDesktopLocal` (13 cases).
## Verify (cd server)
`gofmt -l .` → empty, exit 0 · `go vet ./...` → exit 0 · `go test -race -count=1 ./...` → all ok, exit 0 ·
`go test -race -count=5 ./internal/relay/` → ok (48 s), exit 0. Mutations: no fragmenting → slow test FAIL; no revokeGrace → revoke 2.01 s FAIL;
no refuse → 1008 test FAIL; log every method name → audit test FAIL. The raw-close mutation is not caught deterministically (the race needs Hermes back-pressure).
## Residual risks
The library caps control-frame writes at 5 s. A ping or pong written into a full send buffer that takes >5 s to drain 1/3 still gets cut.
Pings now go out only on a quiet socket, which makes this rare. A phone ping (OkHttp `pingInterval`) during a big download hits the same limit.
