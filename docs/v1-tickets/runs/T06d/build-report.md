# T06d build report — relay hardening (D20; T06c review F1, F3, F5, F6)
## Changed
- **F1 exact blocked writes** (`relay/sock.go`, `sock_unix.go`, `sock_other.go`): `relay.WrapListener` wraps each accepted
  `*net.TCPConn` in `trackedConn`, which writes through `SyscallConn().Write` (`syscall.Write` loop; `EAGAIN` → return false,
  poller waits; EINTR retried; errors as `*net.OpError`). The drain hook fires when bytes are taken *after* an EAGAIN (not on the
  EAGAIN itself). `hijacker` (`liveness.go`) finds the wrapper as `c` (Tailscale) or `tls.Conn.NetConn()` (LAN) and registers
  `session.drained`; the 10 ms timer stays only as a fallback (non-Unix, or an unwrapped listener). No HTTP-server rewrite needed.
- `listen.Manager.Wrap` (new field, applied in `add` before `Serve`/`ServeTLS`); `app/serve.go` sets `Wrap: relay.WrapListener`.
- **F3** `lowat_linux.go`: `TCP_NOTSENT_LOWAT` (0x19) = 128 KiB on the phone socket at hijack, best effort; no-op elsewhere.
- **F5/F6** `docs/protocol.md` §12: Keepalive now describes EAGAIN detection, the non-Unix 10 ms fallback, LOWAT; new "Client
  obligations": answer every ping (else 4 leaked pings → no more pings → 1011); hand frames off, never block OkHttp's read
  thread (>15 s → 1011). 1011 table row updated.
## Tests (T06c tests kept; test harness/slowPhone/TLS servers now use `WrapListener`)
- `TestRelayKeepaliveClosesDeadPhoneUnderCPUAndGCLoad`: 2×GOMAXPROCS allocating goroutines + `runtime.GC()` every 1 ms, 1 ms
  event stream, small buffers; asserts no drain recorded and 1011 within ping+pong+100 ms (closed at ~318 ms).
- `TestTrackedConn{ReportsOnlyWritesThatWaited,WriteEndsOnDeadlineAndClose}`, `TestPhoneSocketHasNotSentLowat` (getsockopt),
  TLS test asserts the session's conn is the wrapper, `TestManagerServesThroughWrap`, `TestRemoteListenersAreWrappedForTheRelay`.
- Mutations (all reverted): force timer path → load test FAIL 2/5; drain on EAGAIN itself → deadline test FAIL; drop LOWAT →
  LOWAT test FAIL (-1); drop Manager wrap / app wiring → their tests FAIL.
## Verify
`gofmt -l .` empty; `go vet ./...` ok; `go test -race -count=1 ./...` all ok (incl. hermes); relay `-race -count=5` ok (78 s);
load/tracked/dead-phone tests `-race -count=20` ok. Relay socket files also vet clean for darwin/windows/freebsd (standalone copy;
the full module is Linux-only already: hermes/statefile). Demo server on 8760 untouched. No commits.
## Notes
- A drain still means the phone's *kernel* acked (frozen app, free receive buffer = alive until full; accepted T06c F4 limit).
