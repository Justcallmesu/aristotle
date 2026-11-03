# T06c review — relay liveness (diff also holds uncommitted T06b)

Fresh reviewer. `go test -race -count=3 ./internal/relay/` → ok (41.2 s), exit 0; `gofmt -l` empty; `go vet` 0. Probes ran on a
scratch copy (since deleted) or as a standalone loopback program. No real Hermes, no git changes, repo untouched.
## Previous findings (T06b review)
- **A dead phone kept open while Hermes streams — FIXED.** Scaled probe (pong 3 s, an event every 8 ms = 375 writes per window,
  the same count as 15 s at 40 ms), idle host: closed 1011 at 3.10 s, 3/3 runs. Mutations reproduced: `drainWait=0` → both
  DeadPhone tests FAIL; `drainWait=1h` → KeptByDrain FAIL. **B pings while forwarding — FIXED** (test). `event` removed
  (`phoneframe.go:10-21`). T06b #1-#4 and desktop_local: re-read, unchanged apart from that; the earlier verdicts stand.
## Findings
1. **MINOR (mechanism proven, impact bounded): under load, the 10 ms threshold can keep a dead phone open** (`liveness.go:24,157-164`).
   Standalone probe, 96 B writes into a free loopback buffer: idle 0/4018 took ≥10 ms; 8 busy loops on 4 cores: 1/188 (p999 25 ms);
   heavy in-process GC: 13/768; `-race` alone: 0/4157. End-to-end scaled probe under the CPU load: 1 of 5 runs closed at 6.1 s (one
   extra round). Each spurious stall costs one more 40 s round. Under sustained pressure nearly every window gets one, which falls
   back to the T06b bound (send buffer full or TCP gives up). Fix (recommended): detect EAGAIN exactly. The build report says TLS
   "cannot be intercepted below TLS", but it can: wrap the listener passed to `srv.ServeTLS` (`listen/manager.go:158`), so
   `tls.Conn.NetConn()` returns the wrapper. Write through `TCPConn.SyscallConn().Write(f)`; the write was blocked if `f` returned
   false at least once. Keep the timer on non-Unix. Interim option: `drainWait` about 30-50 ms. Real blocked waits on a phone whose
   pong is late are about (sndbuf/3)/rate, i.e. tens of ms or more.
2. **Slow phone falsely closed — not found.** The 10 ms threshold misses no real wait. Suspected regression: T06c now pings during
   downloads (T06b pinged only on a quiet socket), which could hit coder's 5 s control-write cap (`write.go:277` closes the conn).
   Probe: phone at 8 and 2 KiB/s, 128 KiB buffers, a ping every 1 s, 40-120 s → never cut. The kernel wakes a writer only after
   ≥1/3 of sndbuf drains, so the ping fits; a ping that waits >5 s for the lock only returns an error the relay ignores.
3. **MINOR — the "last bytes already in the kernel" limit is acceptable for v1.** The tail is the relay's unsent+unacked bytes /
   link rate. Linux sizes sndbuf to about 2×cwnd, so the tail is normally a few RTTs. Risk: a Tailscale TCP conn survives Wi-Fi →
   cellular with sndbuf grown to `tcp_wmem` max (4 MiB). At <280 KB/s that is >15 s, so the phone gets 1011 at the end of a big
   `session.resume`, and a reconnect that re-requests it can repeat. Hardening: `TCP_NOTSENT_LOWAT` of about 128 KiB on `s.raw` via
   `SyscallConn` (Linux and macOS, about 10 lines, relay-only). It caps the unsent bytes ahead of a ping and keeps writes blocking
   (drain marks) until nearly all is sent.
4. **SIOCOUTQ/TIOCOUTQ — not clearly better; do not switch.** It measures the phone *kernel's* ACKs, not the app reading. A frozen
   or hung app whose kernel still ACKs into its receive buffer counts as alive: T06b-A's error, one hop further on. It also needs
   polling, is Linux-only, and needs a backlog threshold. At most, use it as an optional tail check. Prefer 1 (EAGAIN) + 3 (LOWAT).
5. **NIT — ping cap has no per-ping lifetime** (`liveness.go:66-74,82-86`). RFC 6455 §5.5.3 lets a client pong only the latest
   ping. With such a client, 4 leaked pings make `ping()` skip forever while `deadSince` still judges each window, so an idle
   healthy phone gets 1011. OkHttp pongs every ping, so the v1 app is fine. Fix: §12 should say the client must answer every ping,
   or the relay should skip the 1011 decision for a window whose ping was skipped.
6. **NIT (§12/T07)** — OkHttp reads on one thread: an `onMessage` blocking >15 s delays the pong → 1011. Document fast hand-off.
## Verified OK
- Test hook: `wrapHermes` is an unexported field of `Options` (`relay.go:56`). Another package cannot set it (compile error). The
  only non-test use is `session.go:119-121`. The test Hermes types are only in `_test.go`.
- Hijack (`liveness.go:121-139`) flushes the 101, then `Reset(pc)`. coder writes via `brw.Writer` and reads via `pc`
  (`accept.go:167-168`), so pings, close frames and data all pass `progressConn`. `s.raw` is the TCP conn, set before `register`.
- `deadSince` (`liveness.go:95-98`) vs `forward` (`session.go:159-163`): with seq-cst atomics, a forwarding wait that overlaps a
  window always blocks the 1011. A dead phone is closed within 40 s. §12 (`protocol.md:464-498`) matches the code.
VERDICT: PASS-WITH-FINDINGS
