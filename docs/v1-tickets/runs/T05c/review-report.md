# T05c review report (fresh reviewer)

What I ran: `go test -race -count=1 ./...` (all ok), `gofmt -l .` (empty), `go vet` (ok). I also probed a built binary with temp HOME/XDG dirs under scratch, port 18764, stopped afterwards (0 listeners left, real ~/.hermes untouched). Mutations ran on a scratch copy of `server/`; the repo is unchanged.

## D-items
- **D9 FIXED**: `rest.go:66-72` `dropUnlisted` deletes by the exact map key. The new test sets lowercase keys and would fail on the old `Header.Del` code.
- **D10 FIXED**: `rest.go:60` allowlist. Hermes native-http (`dashboard_auth/native_http.py:70,200`) reads only `content-type` and refuses any `Origin`. Both are handled: Content-Type is forwarded and Origin is dropped. No other header is needed for current REST use.
- **D12 FIXED**: `fakehermes_test.go:250` closes after the ack. `TestCloseCodesSurfaceOnReadAfterDial` hits 1013/4403 on the second `Read`, after the buffered `gateway.ready`.
- **D13 FIXED (wiring has no test, see F1)**: all LAN and Tailscale binds go through `Manager.add` -> `BindTCP` -> `listenConfig(e.Iface)` (`manager.go:111,216`). That includes every rebind after the 10 s re-scan, and `Endpoint.Iface` is part of the map key, so an address that moves to another interface gets a fresh, pinned bind. Probe `ss -ltnH`: `192.168.1.61%wlan0`, `100.74.61.96%tailscale0`, `[fd7a:115c:a1e0::d628:3d61]%tailscale0` (IPv6 pinned too), plus `127.0.0.1` (loopback, not pinned, which is fine). The set was unchanged after a re-scan (12 s wait). `/healthz` answered 200 on all 4. No EPERM on kernel 7.1.4.
- **D14 FIXED**:
  - One shared `RejectLog` (`serve.go:83,112`).
  - The pairing text uses `pairing.MaxAttemptsPerPeer` (`cli.go:50`).
  - Lock before cert (`serve.go:66-81`): a second server fails at `Lock` before `devices.Open`/`loadCert`. Every error path unlocks. `run` -> `Listen` reuses the held lock (`control.go:69,96`), and `Serve` releases it on exit. I found no window where two servers both pass the lock. Probe: the second `serve` exits 1 with "lock ... held". Mutation "lock after loadCert" fails `TestSecondServerRefused`.
  - §11 got both additions; "up and running" matches `sysfs.go:42`.
- **D15 FIXED**: `decodeEnvelope(answer)`.

## Findings
- **F1 MINOR (proven test gap)**: no test checks that a pin is actually applied.
  - Each of these mutations still passes every test:
    - `BindTCP` reverted to `net.Listen`
    - `listenConfig("")`, where the empty name means unbind, so it is silently unpinned
    - PM's no-op `setDevice` (only the unknown-iface test caught that one)
  - Fix: `bind_linux_test.go:46` should read the option back with `syscall.GetsockoptString(fd, SOL_SOCKET, SO_BINDTODEVICE)` on a `listenConfig("lo")` socket (expect `"lo"`). Also make the BindTCP->listenConfig wiring testable, or assert it with an injected config.
- **F2 MINOR**: `checkBindable` (`manager.go:220`) does not refuse an empty `e.Iface`. `BindTCP` would then bind unpinned with no error. `endpointFor` (`addr.go:150`) never produces an empty name today, but `BindTCP` promises to refuse bad input "whatever the caller passes".
- **F3 NIT**: the shared-limiter wiring in `serve.go:112` has no test. Dropping `Rejects: s.rejects` passes every test; the httpapi test only covers sharing when a caller passes one in.
- **F4 NIT**: `Content-Length` in the allowlist does nothing. Go's client ignores that header and sends `req.ContentLength`, so removing it from the list passes the tests and changes no behaviour. No test pushes a POST body through `Do` and checks that Hermes receives the body with the right Content-Type/length.
- **F5 NIT**:
  - `cli.go:6`: the `pairing` import sits in the std-lib group (gofmt accepts it; goimports style does not).
  - `rest.go:19`: an overlong doc line.
  - §11's SO_BINDTODEVICE line does not say it applies on Linux only (`bind_other.go` is a no-op).

Not verified: a real foreign-interface packet being dropped. That needs a second host or a netns, which needs root. The `%iface` in `ss` output shows the kernel has the device binding.

VERDICT: PASS-WITH-FINDINGS
