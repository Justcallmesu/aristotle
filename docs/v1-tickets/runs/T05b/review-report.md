# T05b review — listener and pairing security fixes

Fresh deep reviewer. Read PROJECT.md, wave-4 brief, T05b build brief/report, T05 review, all T05+T05b code outside `internal/hermes`, `docs/protocol.md` §11.
`gofmt -l .` empty; `go vet ./...` ok; `go test -race -count=1 ./...` -> all 10 packages ok.
Probes (throwaway copy of `server/` in scratch, deleted): (a) keep-alive: fast `POST /v1/pair` (401), wait > deadline, slow `GET /healthz` on the same conn -> 200;
(b) HTTP/2 over TLS: stalled pair body -> 400 in ~200 ms, then `/healthz` on the same h2 conn -> 200. (c) `serve` on port 18761, temp XDG: binds exactly wlan0 192.168.1.61 + tailscale0 v4/v6 + loopback;
`pair` order wlan0 (default route) then tailscale0; second `serve` -> exit 1 "lock … held"; SIGTERM -> 0 listeners left.

## Previous findings
- **MAJOR Tailscale by address -> FIXED.** `addr.go:73-75,147`: needs `Tun` (sysfs `tun_flags`) AND name prefix `tailscale`; neither alone suffices. fd7a on wlan0, 100.x on tun0/usb0/non-TUN tailscale0 -> nothing (test). Other ULAs never classify. Residual on the gate side: see MINOR 1.
- **MINOR docker/bridge -> FIXED.** (1) `lanIface` physical-or-allowlist (`addr.go:86-91`); sysfs injectable (`sysfs.go`), fails closed. (2) default-route iface first, iface printed (seen live). (3) `gate.go:35-36` on-link subnet check, peer unmapped first; no subnet -> 403. IPv6 never served on LAN, so no v6 bypass.
- **MINOR pairing DoS -> FIXED.** `pairing.go:99-118`: per-peer 5, global 20, locked peer not compared/counted; map ≤20 keys per code, replaced on `Start`, so bounded. Residual by design: a LAN host with 4 aliases still kills a code (20 wrong) — accepted per brief.
- **MINOR slow body -> FIXED.** `pair.go:43` per-request deadline; no server `ReadTimeout`; `statusWriter.Unwrap` lets the controller reach the conn. net/http resets the deadline per request (probe a) and h2 applies it per stream (probe b).
- **MINOR protocol doc -> FIXED.** §11 checked line by line against code: ranges, TLS/HTTP split, 400/401/403/404/500, `dev_<12 hex>`, 43-char token, Bearer case-insensitive, `WWW-Authenticate`, no-store, 4 KiB/10 s, lockout numbers, pin (SPKI SHA-256, 16 groups). §1–§10 untouched (diff is +65/-0).
- **NIT half pair -> FIXED** (`tlscert.go:59-62,76-79` names the present file). **NIT bidi -> FIXED** (`pair.go:114-116` Cc/Cf/Zl/Zp/invalid UTF-8; same rule for `--name`).
- **NIT socket race -> FIXED.** `lock_unix.go` flock O_NOFOLLOW 0600 in the 0700 state dir, held until `Serve` returns (after socket unlink); stale-socket removal only under it. See NIT 3 for ordering.
- **Shared tls.Config race (found by builder) -> FIXED.** `manager.go:150` clones per server; `ServeTLS` mutates only its own copy.

## New findings
**MINOR (unproven)** `listen/gate.go:35-38` + `manager.go:211-216`: the Tailscale listener admits any 100.64/10 or fd7a source whatever interface the packet came in on.
On this host: table 52 holds only per-peer /32 routes, `rp_filter` effective loose (wlan0=2), `arp_ignore=0`. So on a Wi-Fi that hands out 100.64/10 (the T05 MAJOR scenario), a non-tailnet neighbour,
or the user's phone with Tailscale off, can reach `http://100.74.61.96:8760` in cleartext. The only thing that stops this is tailscaled's netfilter `ts-input` DROP rule (`! -i tailscale0 -s 100.64.0.0/10`), which is on by default; I could not check it without root.
Fix: bind each listener to its interface with `SO_BINDTODEVICE` via `net.ListenConfig.Control` (syscall only, unprivileged since Linux 5.7). This also hardens LAN listeners. Test the option value with an injectable control hook.
**NIT 1** `app/serve.go:101-104` + `listen/manager.go:141`: `remoteHandler` builds a new router, and with it a new `rejectLog`, for every listener and every re-add.
With 3 listeners that is up to 3 "pairing attempts rejected" lines per minute, and an interface flap resets the limiter. Fix: create one `rejectLog` on `server` and pass it in `Deps`.
**NIT 2** `app/cli.go:49` hard-codes "5 wrong tries". Use `pairing.MaxAttemptsPerPeer`.
**NIT 3** `app/serve.go:38,65-73` vs `108`: the control lock is taken in `run`, after `devices.Open` and `tlscert.LoadOrCreate`. The probe saw the second `serve` log "tls certificate" before it was refused.
If two first starts run at the same time, each writes its own key and cert. Each file is written atomically, but the pair can end up mixed, and the next start then fails with "corrupt pair". Fix: take the lock in `Serve` before `newServer`.
**NIT 4** `docs/protocol.md` §11: the `device_name` rule leaves out that invalid UTF-8 is also stripped. The LAN row leaves out that the interface must be up and running. Neither changes T07's behaviour.

## Not verified
A real CGNAT network or container peer; tailscaled's iptables rules (needs root); darwin `lock_unix` path (vet only).

VERDICT: PASS-WITH-FINDINGS
