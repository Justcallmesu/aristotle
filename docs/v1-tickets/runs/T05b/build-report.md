# T05b build report — listener and pairing security fixes

**Files.** New: `listen/sysfs.go` (injectable `Host{NetClassDir, RouteFile}`: `device`/`tun_flags`, `/proc/net/route`), `httpapi/ratelog.go`, `control/lock_{unix,other}.go`.
Edited: `listen/{addr,gate,manager}.go`, `pairing/pairing.go`, `httpapi/{pair,httpapi}.go`, `tlscert/tlscert.go`, `control/control.go`, `config/config.go`, `app/{serve,controlcmds,cli}.go`, the tests next to them, and `docs/protocol.md` (§11 appended; §1–§10 byte-identical, checked with diff).
Not touched: `internal/hermes/**`, `go.mod`/`go.sum`. Std lib + syscall only.
## Review findings
- **MAJOR Tailscale by address -> FIXED.** A tailnet-range address counts only on a TUN interface named `tailscale*`. `TestSelectTailnetRangeNeedsTailscaleTun`: `wlan0` 100.80.1.2/10, `wlan0` fd7a::, `tun0`, non-TUN `tailscale0`, `usb0` 100.100.x -> no endpoint. I saw it fail with the check removed (RED).
- **MINOR docker/bridge -> FIXED.** (1) LAN only on physical NICs (`device` exists), or only the interfaces in `lan_interfaces` (`TestSelect`: bridge, veth, wg, down NIC excluded; allowlist case). Fake sysfs tree: `TestHostAnnotate`; route parsing: `TestParseDefaultRoute`.
  (2) `pair` prints URL, interface and note in this order: default-route LAN first, other LAN, Tailscale (`TestSelectOrderDefaultRouteFirst`, `TestPrintPairing`, e2e `TestPairOutputOrderAndInterfaces`).
  (3) On-link gate: a LAN peer must be inside the interface prefix, else 403 (`TestPeerGate`: 172.28.0.2, 10.0.0.5, 192.168.2.20 -> 403; no subnet -> 403). Also RED-checked.
- **MINOR pairing DoS -> FIXED.** 5 wrong tries per peer IP lock that IP out of the code (IPv4-mapped addresses are unmapped). After 20 wrong tries in total the code dies. A locked-out IP's further tries are not compared and do not count.
  Tests: `TestPerPeerLockout`, `TestLockedPeerAttemptsDoNotCount`, `TestGlobalCapInvalidates`, `TestNineteenSpreadAttemptsKeepCode`, HTTP `TestPairLockoutIsPerPeer`.
  Rejections log at most one `pairing attempts rejected count=N` line per minute (`TestRejectLogOneLinePerInterval`; 30 rejections -> 1 line). Invalidation logs once. The client always gets the same 401 text.
- **MINOR slow body -> FIXED.** `SetReadDeadline(now+10s)` through `http.ResponseController` in the pair handler; no server-wide ReadTimeout; 4 KiB cap kept. `TestPairSlowBodyCutOff` (real server, stalled body -> 400; hangs and fails without the deadline).
- **MINOR protocol doc -> FIXED.** New `docs/protocol.md` §11 covers listeners, `/v1/pair`, `/v1/me`, Bearer, 400/401/403, TLS listener, and the fingerprint with the Android/OkHttp computation.
- **NIT half pair -> FIXED.** The error gives the exact path to delete (the file that exists). A corrupt pair names both files. `TestHalfPairNamesTheFileToDelete` follows the message and recovers.
- **NIT bidi names -> FIXED (stripped, per brief).** Cc, Cf, Zl, Zp and invalid UTF-8 are removed before the 64-char cap; same rule for `pair --name`; the CLI table masks them too. Test: `TestPairNameStripped`.
- **NIT socket race -> FIXED.** `control.lock` (flock, 0600, O_NOFOLLOW) is held from Listen until Serve returns. A stale socket is removed only under the lock, and a live socket still refuses.
  `TestConcurrentListenOneWinner`: 20 rounds × 8 servers -> exactly one listens and its socket answers. I saw it fail without flock.
- **Found while testing, FIXED:** (a) every LAN `http.Server` shared one `*tls.Config` and ServeTLS writes HTTP/2 settings into it. With ≥2 LAN listeners (T05 had wlan0 + the docker bridge) that is a data race. Each server now gets a `Clone()`; `TestManagerTwoLANListenersStartCleanly` reports the race without the clone.
  (b) Pre-existing flake `TestOtherUIDRefused`: the client's write raced the server's early close (EPIPE). `Call` now reads the reply after a failed write; 300/300 runs pass.

**Config keys.** New `lan_interfaces` (list of interface names; unset or `[]` = every physical NIC; `null`, empty name, `/`, `:`, space, >15 chars or a duplicate -> error). Unchanged: `lan_enabled`, `tailscale_enabled`. **Pair output change:** the control `pair.start` result now has `addresses[]{url,kind,iface,default_route}` instead of `lan[]`/`tailscale[]` (T08 QR should use it).
## Commands (in `server/`)
`gofmt -l .` -> empty, exit 0; `go vet ./...` -> exit 0; `go test -race -count=1 ./...` -> exit 0, all 10 packages ok (`internal/hermes` included).
`go test -race -count=20` on control/app/listen/httpapi -> ok. `GOOS=darwin go vet ./internal/control ./internal/listen` -> ok.
## Manual run (temp XDG dirs, port 8760, stopped with SIGTERM, 0 listeners afterwards, temp dir deleted)
```
msg=listening addr=127.0.0.1:8760 kind=loopback
msg="listener added" kind=lan iface=wlan0 addr=192.168.1.61 subnet=192.168.1.0/24 url=https://192.168.1.61:8760
msg="listener added" kind=tailscale iface=tailscale0 addr=100.74.61.96 subnet=100.74.61.96/32 url=http://100.74.61.96:8760
msg="listener added" kind=tailscale iface=tailscale0 addr=fd7a:115c:a1e0::d628:3d61 ... url=http://[fd7a:115c:a1e0::d628:3d61]:8760
ss -ltn :8760 -> 192.168.1.61, 127.0.0.1, 100.74.61.96, [fd7a:115c:a1e0::d628:3d61] only (no 172.x)
pair (exit 0): Pairing code: <CODE> / Expires 10:58:46 (in 5m0s); single use; 5 wrong tries lock out that address
  https://192.168.1.61:8760                wlan0       LAN, HTTPS, default route
  http://100.74.61.96:8760                 tailscale0  Tailscale, HTTP
  http://[fd7a:115c:a1e0::d628:3d61]:8760  tailscale0  Tailscale, HTTP
```
Run 1: 5 wrong codes from 192.168.1.61 -> 401×5, then the right code from it -> 401; the same code over Tailscale -> 201. `/v1/me` over LAN -> 200; name `t05b`.
The log has one `pairing attempts rejected` line and 0 hits for the code, the token or "Bearer". openssl SPKI hash == printed pin.
A second `serve` -> exit 1, "lock … held: is aristotle-server already running?". With the cert deleted -> exit 1, "delete <state>/tls-key.pem and start again".
## NOT-VERIFIED
- A real container peer -> 403. My host-side probe bound to 172.28.0.1 got 200 because docker's POSTROUTING MASQUERADE rewrites it to 192.168.1.61 (on-link; `ss` showed peer 192.168.1.61). Gate covered by unit tests only.
- A real phone (T07). A live default-route change. The per-minute log over a real minute (fake clock only). `lock_other.go`/darwin: vetted only, never run.
