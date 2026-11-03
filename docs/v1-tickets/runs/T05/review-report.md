# T05 review — listeners, pairing, device auth (security boundary)

Reviewer: fresh deep reviewer. Read PROJECT.md, spec §2 + Q3/Q7/Q7b/Q7c, build brief/report, all T05 code.
`go test -race -count=1 ./...` -> all 10 packages ok. Probe: a throwaway copy of `internal/listen` in scratch (repo untouched, now deleted).
Host facts: `/sys/class/net/wlan0/device` exists; `br-*`/`docker0` have `bridge/` and no `device`; `tailscale0` has `tun_flags`.

## Findings

**MAJOR (proven)** `internal/listen/addr.go:82-100` (+ `gate.go:178-185`): the Tailscale kind is chosen **by address only**. The interface name and type are never checked.
Probe: `Select([{wlan0, 100.80.1.2}])` -> `http://100.80.1.2:8760 tailscale`. Some Wi-Fi, hotel, ISP-CGNAT and carrier networks hand out 100.64/10 addresses.
On those networks the server opens a **plain-HTTP** listener that admits every 100.64/10 peer (other CGNAT customers, the same Wi-Fi) and prints it under "Tailscale (HTTP)".
The user can then send the pairing code and Bearer token in cleartext. That breaks spec §2 ("plain HTTP inside Tailscale") and Q3 (no public exposure).
Fix: accept a tailnet endpoint only on a tun interface (`/sys/class/net/<if>/tun_flags` exists) whose name starts with `tailscale`. Optionally add a `tailscale_interface` config key.
Add `Iface.Kind`/`Tun` filled by `SystemInterfaces`, plus a Select test that has 100.x on `wlan0`.

**MINOR** `addr.go:82-100`, docker bridge (brief ask). Any UP+RUNNING bridge or VPN with an RFC1918 address gets an HTTPS listener (probe: `br-2c44` 172.28.0.1 and `wg-office` 10.8.0.3 both selected).
LAN endpoints sort by address, so 10.x and 172.x print before 192.168.x and the docker URL appears first.
Recommended fix, in order:
(1) LAN only on physical NICs: `/sys/class/net/<if>/device` exists. That excludes bridges, veth, tun and wg, and on this laptop leaves exactly `wlan0`. Add an optional `lan_interfaces` allowlist as the escape hatch.
(2) Print the interface name next to each URL, and list the default-route interface first (`/proc/net/route`, std lib).
(3) Make the peer gate on-link: a LAN listener admits only peers inside its own interface prefix (`ipnet` from `Addrs()`).
Today `172.28.0.2 -> 192.168.1.61:8760` passes the gate (probe `peerAllowed(lan,172.28.0.2)=true`), so removing the bridge listener alone does not keep containers out.
Tokens and codes still protect the routes, so this is not a bypass. Item 1 is enough for v1.

**MINOR** `pairing/pairing.go:322-327`: the 5-try lockout is global and unauthenticated. Any LAN, tailnet or container peer can kill the active code with 5 POSTs during the 5-minute window (pairing DoS).
Each failure also logs `pairing attempt rejected` (`httpapi/pair.go:196`) with no throttle.
Acceptable for v1. If changed: count failures per peer IP, keep a global ceiling (for example 20 tries; 20/2^40 is still negligible), and log one rejection line per minute.

**MINOR** `listen/manager.go:324-330`: no read deadline on request bodies. `ReadHeaderTimeout` only covers headers, so an admitted peer can trickle the 4 KB `/v1/pair` body forever and hold connections.
Fix: call `http.NewResponseController(w).SetReadDeadline(now+10s)` in `pairHandler`. Do not set a server-wide `ReadTimeout`: T06's WebSocket would break.

**MINOR** (doc, for T07) `docs/protocol.md` does not specify `POST /v1/pair`, `GET /v1/me`, the 401/400/403 semantics, or the pin definition.
The pin is SHA-256(SubjectPublicKeyInfo DER), which equals Android `MessageDigest("SHA-256").digest(cert.publicKey.encoded)`; OkHttp pins are base64 of the same hash.
These exist only in code comments and the build report. The PM should add an "aristotle-server API" section before T07.

**NIT** `tlscert/tlscert.go:67-72`: key and cert are two separate atomic writes. A crash between them leaves a half pair that fails startup until both are deleted manually.
The error message says so, and nobody can have pinned that key yet. Option: when only the key exists and no device is paired, regenerate.
**NIT** `httpapi/pair.go:228-236`: `unicode.IsControl` lets through format characters (U+202E bidi override and others) in phone-chosen names, which reach the CLI table and logs. Also reject `unicode.Is(unicode.Cf, r)`.
**NIT** `control/control.go:297-313`: stale-socket check then remove is racy if two servers start at once. Bind fails safely (EADDRINUSE) or a socket is replaced. Harmless for a single systemd unit.

## Verified OK
- Wildcard never bound: `BindTCP` refuses unspecified/off-kind addresses; loopback `Config.Validate` allows only 127.0.0.1/::1; `Select` drops `0.0.0.0`/`::`.
- Peer gate uses `RemoteAddr` only (X-Forwarded-For ignored). IPv4-mapped peers are unmapped before classify. Zoned addresses fail closed. The gate wraps each listener.
- TLS: ECDSA P-256, PKCS8 key 0600 via `statefile`, 20-year validity, TLS ≥1.2, reused across restarts. A half pair or loose mode is a hard error. `ServeTLS` clones the shared config.
- Codes: 8 chars from a 32-char Crockford alphabet (no I/L/O/U), `crypto/rand` with `&31` (no bias), 40 bits. Single active code, single use, monotonic 5-minute expiry, 5th failure kills it.
  Constant-time compare. All work is under one mutex (20-way race test, 1 winner). Bad JSON does not use up an attempt.
- Tokens: 32 random bytes, base64url. Only SHA-256 hex is stored. Compare is constant-time across all devices with no early exit. Revoke deletes the entry and the next request gets 401.
- `devices.json` is written through temp (0600) + fsync + rename + dir fsync. Reads use O_NOFOLLOW + fstat, check owner and mode, and reject unknown fields.
- Control socket is 0600 in a 0700 dir, SO_PEERCRED uid check before the request is decoded, logs only the command name. Non-Linux refuses every peer.
- Logs: method/pattern/status only, `route=unmatched`; no code, token or Authorization (captured-slog test plus serve e2e). The pairing code is never logged at issue.
- D1 fixed (`logging.go:133-138`). D2 fixed (O_NOFOLLOW|O_DIRECTORY open, then fstat/fchmod on the handle). D3 fixed (injected `Stat_t` uid in config + statefile). D5 fixed (`null` / non-object / null value refused).

VERDICT: CHANGES-REQUIRED
