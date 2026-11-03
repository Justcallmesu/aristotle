# T06d review — relay exact blocked writes (EAGAIN), TCP_NOTSENT_LOWAT, §12 client obligations
Fresh reviewer. `go test -race -count=3 ./internal/relay/ ./internal/listen/ ./internal/app/` → all ok (relay 48.9 s), exit 0.
Liveness subset (`TrackedConn|CPUAndGC|NotSentLowat|OverTLS|DeadPhone|SlowPhone|KeptByDrain`) `-race -count=8` with an external
spin loop on every CPU → ok (88.5 s). Probes: scratch copy under `$TMPDIR`. Repo, git, Hermes, demo server :8760 untouched.
## T06c findings
- **F1 10 ms threshold under load — FIXED.** `trackedConn.Write` (`sock_unix.go:44-78`) uses `RawConn.Write` →
  `poll.FD.RawWrite` (Go 1.26.5 source checked): it takes the fd write lock (serialises with other writers, refcounts the fd so a
  concurrent Close cannot free or reuse the descriptor), `prepareWrite` honours an expired deadline, and `waitWrite` returns on
  deadline/Close (evict). The loop resumes at `p[n:]` (partial writes), retries EINTR, waits on EAGAIN, and treats `m==0` like the
  stdlib (`io.ErrUnexpectedEOF`). `maxWrite` = stdlib `maxRW`. The drain fires only when bytes are taken *after* an EAGAIN, once per
  wait, under the fd lock (it is only an atomic store). TLS: `tls.NewListener(trackListener)` → `NetConn()` is the wrapper
  (asserted in `TestRelayOverTLSWithHTTP2Enabled`). `pc.timed=false` only when the wrapper is found, else the 10 ms fallback.
- **F3 LOWAT — DONE.** Set only on hijacked relay sockets (`liveness.go:128`), so other HTTP traffic keeps default buffers. It caps
  *unsent* bytes, not in-flight ones, so BDP and throughput are unaffected. Probe, 1 GiB over loopback: plain 0.60–0.67 s, wrapped
  0.50 s, wrapped+LOWAT 0.51–0.55 s. Linux wakes the writer at notsent < lowat/2 (more, cheap wakeups). **F5/F6 — DONE** (§12).
## Findings
1. **MINOR (latent, impact unproven) — embedding `*net.TCPConn` exposes write paths that skip the tracking** (`sock_unix.go:34`).
   Probe: `*trackedConn` implements `io.ReaderFrom` (promoted `TCPConn.ReadFrom`, i.e. sendfile/splice). The unexported
   `writeBuffers` (writev via `net.Buffers.WriteTo`) is probably promoted too (not probed). The relay's write chain does not use
   either today: coder writes through `bufio.Writer` → `progressConn` (which embeds the `net.Conn` interface, so nothing is
   promoted), and `crypto/tls` calls `conn.Write`. If a library update started using one of them, drains would silently stop
   and a slow live phone would get 1011. Fix: define `ReadFrom` on `trackedConn` that goes through `Write` (e.g. `io.Copy` with a
   writer-only wrapper), or embed the `net.Conn` interface and keep `*net.TCPConn` as a named field for `SyscallConn`/`SetLinger`.
2. **NIT — error Op differs by path** (`sock_unix.go:48` vs `:75`). Deadline and Close errors arrive as `raw-write tcp …` from
   `rawConn.Write`, syscall errors as `write tcp …`. `errors.Is(err, net.ErrClosed)` and `net.Error.Timeout()` still work
   (probe, `TestTrackedConnWriteEndsOnDeadlineAndClose`). Only log text changes.
3. **NIT — §12 wording slightly too strong** (`protocol.md:497`). It says a counted write "needs the phone's TCP to
   acknowledge data". Under LOWAT an EAGAIN can come from notsent ≥ lowat and clear when bytes are *transmitted* into an already
   open window, so a phone that just died can still produce drains for about 1 RTT. `sock_unix.go:29-32` says this correctly. The
   bound (+1 round only if death falls within ~1 RTT before a ping) is negligible.
4. **NIT** — `liveness.go:50` is 142 columns (the rest of the file wraps at about 130). `exactDrain` (`sock_unix.go:14`) is a
   production constant used only by tests.
## Verified OK
- No goroutine or fd leak: 500 wrapped accept/write/close cycles → goroutines 2→2, fds 8→8. No new goroutines in the code.
- Writes that are not hijacked (plain HTTP, HTTP/2 over TLS) pass through with `drained` nil, so they behave as before. Shutdown
  and `Close` on `trackListener` are promoted. A failed `Accept` passes `(nil, err)` through.
- `Manager.add` wraps below `ServeTLS`/`Serve` (`manager.go:123-125`, `TestManagerServesThroughWrap`). The app wires it
  (`serve.go:97`). The loopback router does not carry the relay. Load test (`lastDrain == 0`, 1011 at ping+pong) holds under
  external CPU saturation too.
VERDICT: PASS-WITH-FINDINGS
