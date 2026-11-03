# T06c build report — relay liveness: dead phone closed while Hermes streams

**Rule as implemented** (`liveness.go`, §12 Keepalive + `1011` row). A ping goes out every PingInterval (25 s) whatever the traffic
(background goroutine, ctx = ioCtx so a deadline never cuts a half-written ping; ≤4 unanswered at once). At ping+PongTimeout (15 s)
the phone is closed `1011` unless, since the ping, (a) any byte came from the phone (pong, frame), or (b) a write to the phone that
**waited ≥ 10 ms** (`drainWait`) completed, or (c) a phone frame was waiting for Hermes during the window (pongs unread then).
Writes are seen by pointing coder's `brw.Writer` at `progressConn` in `Hijack` (it pointed at the raw conn). coder/websocket and Go's
`net.Conn` cannot say whether a write blocked, and a TLS conn cannot be intercepted below TLS, so "blocked" is a measured wait.
Accepting a write into a free buffer takes µs; a blocked write returns only when the kernel wakes the writer (~0.14 s apart at 2 MiB/s).
A stall that makes a free write look slow costs a dead phone at most one more ping round. Removed: `writeStalled`, `quiet`, `writing`/`lastWrite`.
Limit (also in §12): once the last bytes are in the kernel nothing waits, so the phone must drain its buffers and pong within 15 s.
Also: `event` dropped from `knownMethods` (TestRelayHappyPath now expects `other=2`). Test seam: unexported `Options.wrapHermes` + `upstream` interface.

**Tests** (`liveness_test.go`, reduced timings, ping 100 ms / pong 200 ms unless noted)
- `TestRelayKeepaliveClosesDeadPhoneWhileHermesStreams`: ~90 B event / 40 ms, non-reading phone → 1011 at ~305 ms (bound 400 ms).
- `TestRelayKeepaliveClosesDeadPhoneUnderFastStreamSmallBuffers`: 128 KiB buffers, 1 ms events (283 written) → 1011 at ~304 ms.
- `TestRelaySlowPhoneGetsEveryLargeFrame` (T06b test, unchanged in substance) passes.
- `TestRelaySlowPhoneKeptByDrainWhilePongsAreLate` (new): Hermes floods 256 KiB frames; phone reads 2 MiB/s with 2 MiB read-ahead, so pongs ~1.2 s late
  vs pong 300 ms → stays open 2.5 s. Only drain marks save 4 of its 8 windows.
- `TestRelayPingsPhoneWhileForwardingWaitsForHermes` (nit B): stuck Hermes → ≥3 pings reach the phone, no close (T06b: 0 pings).
- `TestRelayKeepaliveClosesWithoutPong` (silent Hermes), mid-frame stall and revoke tests still pass. Mutations: `drainWait=0` (counts every write, T06b-like) → both DeadPhone tests FAIL (open at 402 ms); `drainWait=1h` → KeptByDrain FAIL.

**Commands** (in `server/`): `gofmt -l internal/relay` → empty; `go vet ./internal/relay` → 0; `go test -race -count=5 ./internal/relay/`
→ 0 (68.7 s); `go test -race ./...` → 0; 4 concurrent `relay.test -test.count=4 -test.run 'DeadPhone|Pings|KeptByDrain|SlowPhone|Keepalive'` → 4× PASS.
Files: `server/internal/relay/{liveness.go,session.go,relay.go,phoneframe.go,liveness_test.go,relay_test.go}`, `docs/protocol.md` §12. No commits, no real Hermes.
