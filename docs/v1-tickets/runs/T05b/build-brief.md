# T05b build brief — listeners and pairing: review fixes (security)

route: deep. Fix round for T05 (`docs/v1-tickets/runs/T05/review-report.md`, read it fully — verdict CHANGES-REQUIRED).

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` §2 + Q3/Q7b, the T05 build brief
and report, then the T05 code (uncommitted working tree under `server/`, everything except `server/internal/hermes/`).

## Facts on this laptop (PM-verified)
Interfaces: `wlan0` (physical: `/sys/class/net/wlan0/device` exists; default route `via 192.168.1.1 dev wlan0`),
`tailscale0` (`/sys/class/net/tailscale0/tun_flags` exists), `docker0`, 11× `br-*`, 2× `veth*`, `lo`.

## Fix (each with a test)
1. **MAJOR — Tailscale by interface, not by address.** An address counts as Tailscale only on a TUN interface (tun_flags
   present) whose name starts with `tailscale`. A 100.64/10 or fd7a:115c:a1e0::/48 address on any other interface is
   ignored (never plain HTTP). Test: `wlan0` holding `100.80.1.2` -> no endpoint. Make the sysfs checks injectable.
2. **LAN = physical interfaces only** (`/sys/class/net/<if>/device` exists), plus optional config `lan_interfaces`
   (list of names; when set, only those). Docker/bridge/veth/virbr are excluded by the physical rule.
3. **LAN peer must be on the listener's own subnet** (peer IP within the interface address's prefix); else 403.
4. **Output**: `pair` prints each address with its interface name, default-route interface first, Tailscale after.
5. **Pairing DoS**: wrong-code lockout counted per peer IP (5 per IP per code; a global cap of 20 then invalidates);
   rejection logging rate-limited (e.g. one summary line per minute). Single-use, 5-min expiry unchanged.
6. **Slow body**: `POST /v1/pair` gets a per-request read deadline (e.g. 10 s) via `http.ResponseController`, not a
   server-wide timeout (T06 WebSockets must stay open). Body size capped (e.g. 4 KiB).
7. **Docs**: add `docs/protocol.md` §11 "aristotle-server API (phone side)": `/v1/pair` request/response/errors,
   `/v1/me`, Bearer auth, the 401/403 meanings, which listener uses TLS, and the fingerprint definition
   (SHA-256 of the DER SubjectPublicKeyInfo, uppercase hex in 4-char groups) with how Android computes it
   (`certificate.publicKey.encoded` -> SHA-256). Do not touch §1–§10.
8. NITs: device names stripped of control and bidi-override characters (length cap 64); on start, a half-written cert/key
   pair gives an error message that says exactly which file to delete; stale control socket removal is safe against two
   servers starting at once (fail if another live server answers on it).

## Hard rules
- Files you may edit/create: `server/**` EXCEPT `server/internal/hermes/**` and `go.mod`/`go.sum`; `docs/protocol.md` (new §11 only);
  `docs/v1-tickets/runs/T05b/build-report.md`. Std lib + syscall only.
- Never bind wildcard. Manual runs: temp XDG dirs, stop afterwards. No commits.

## Verify
- `cd server && gofmt -l . && go vet ./... && go test -race -count=1 ./...` (if only `internal/hermes` fails, T04b is mid-edit: test your packages and say so).
- Manual (temp dirs): `serve` binds exactly wlan0's 192.168.1.61 (HTTPS) and tailscale0's addresses (HTTP); no docker/br addresses; `pair` output ordering as in 4. Paste the bind log + pair output (code masked).

## Report
`docs/v1-tickets/runs/T05b/build-report.md`, ≤45 lines: each review finding -> FIXED (test) / WONTFIX (why); config keys; commands + exit codes; manual output; NOT-VERIFIED.
