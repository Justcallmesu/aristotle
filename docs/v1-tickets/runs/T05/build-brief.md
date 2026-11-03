# T05 build brief — listeners, device auth, manual pairing, devices list/revoke

route: deep (security boundary: network exposure, tokens, pairing)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` §1 v1.1/v1.2/v1.9, §2 rules,
decisions Q3, Q7, Q7b, Q7c, Q26, and the board's deferred findings D1, D2, D3, D5 in `docs/v1-tickets/tickets.md`.
Then the real code in `server/` (T03/T03b). Pattern: keep the existing package roles (`internal/config`, `internal/httpapi`, `internal/app`).

## Goal
The phone can reach aristotle-server on the laptop's Wi-Fi address (HTTPS, pinned self-signed cert) and its Tailscale
address (HTTP inside WireGuard), pair with a one-time code, and then authenticate every request with its own device
token. The user can list and revoke devices from the laptop.

## Non-goals
QR (T08), the WebSocket relay (T06), systemd install (T21), any Hermes access (T04).

## Requirements
1. **Listeners** (new `internal/listen` or similar):
   - Wi-Fi/LAN: every non-loopback interface address in RFC1918 IPv4 (10/8, 172.16/12, 192.168/16) -> HTTPS. Tailscale:
     addresses in 100.64.0.0/10 and fd7a:115c:a1e0::/48 -> plain HTTP. Loopback listener from T03 stays (health + local control below).
   - Same port for all (config `port`, default 8760). Never bind `0.0.0.0`/`::` (existing guard).
   - Peer gate per listener: LAN listener accepts only private-range peers; Tailscale listener only tailnet peers; else 403.
   - Re-scan interfaces every 10 s; add/remove listeners when the set changes (log once per change).
   - Config: `lan_enabled` (default true), `tailscale_enabled` (default true). Exact-case keys (T03b rule).
2. **TLS**: self-signed ECDSA P-256 cert + key generated on first start into the state dir (key file 0600), reused after;
   SANs = current LAN IPs (regenerate when a new LAN IP appears is NOT needed: the phone pins the SPKI fingerprint, not the hostname — document this). Fingerprint = SHA-256 of the cert's SubjectPublicKeyInfo, shown as uppercase hex in 4-char groups.
3. **Local control** for the CLI: unix socket `<state>/control.sock` (0600, removed on shutdown) serving JSON commands
   `pair.start`, `devices.list`, `devices.revoke`. Only the same uid may connect (check peer credentials, SO_PEERCRED).
4. **Pairing**: `aristotle-server pair [--name NAME]` asks the running server (via control socket) for a code; prints the
   LAN and Tailscale addresses with port, the 8-character code (Crockford base32, no ambiguous chars), its expiry
   (5 min) and the fingerprint. One active code at a time; single use; 5 wrong attempts invalidates it.
   Phone calls `POST /v1/pair {"code","device_name"}` on a LAN/Tailscale listener -> `201 {"device_id","token"}`.
   Code is in the BODY, never the path or query (D1 spirit).
5. **Devices**: token = 32 random bytes, base64url; stored only as SHA-256 in `<state>/devices.json` (0600, atomic write
   via temp file + rename) with id, name, created_at, last_seen_at. `aristotle-server devices list|revoke <id>`.
   Middleware: `Authorization: Bearer <token>` on every `/v1/*` route except `/v1/pair`; constant-time compare;
   unknown/revoked -> 401. `GET /v1/me` returns the device's id and name (proves auth end to end).
   Revoke takes effect on the next request (T06 will also close live sockets).
6. **Deferred findings**: D1 unmatched routes log `route=unmatched`; D2 state dir opened without following symlinks and
   chmod'ed via the handle (or equivalent race-free approach, justify); D3 test the other-owner refusal by injecting file
   info; D5 config file `null` -> error.
7. No token, code or Authorization header ever appears in logs (test it with a captured slog handler).

## Tests (table-driven where natural, `-race`)
Interface filtering (feed fake interface lists); wildcard refused; peer gate; cert created once and reused, key 0600;
fingerprint format; code alphabet/length/expiry/single-use/5-attempt lockout; pair -> token -> `/v1/me` 200; bad token 401;
revoke -> 401; devices.json 0600 and atomic; control socket rejects other uid (inject the peer-cred check); log redaction.

## Hard rules
- Files you may create/edit: `server/cmd/**`, `server/internal/**` EXCEPT `server/internal/hermes/**` (T04 in parallel),
  `docs/v1-tickets/runs/T05/build-report.md`. Do not edit `server/go.mod`/`go.sum` (std lib only; if you truly need a
  module, stop and report).
- Never bind wildcard. Never write outside the configured state dir. No commits, no git state changes.
- Manual run against the real network is allowed (it binds the laptop's real LAN/Tailscale IPs) but stop it when done
  and use a temp XDG_STATE_HOME/XDG_CONFIG_HOME.

## Verify
- `cd server && gofmt -l . && go vet ./... && go test -race -count=1 ./...`
- Manual: start `serve` with temp dirs; `pair --name test`; `curl -sk --resolve` (or `curl -k https://<lan-ip>:8760/v1/pair`)
  with the code -> token; `curl -k -H "Authorization: Bearer …" https://<lan-ip>:8760/v1/me` -> 200; same over the
  Tailscale IP with http; `devices revoke` -> 401. Paste outputs with token values masked.

## Stop and report if
SO_PEERCRED or interface enumeration needs anything beyond std lib + `syscall`; or a requirement here weakens a spec §2 rule.

## Report
`docs/v1-tickets/runs/T05/build-report.md`, ≤50 lines: files; config keys added; endpoints; tests by area; commands + exit
codes; manual run output (masked); D1/D2/D3/D5 -> FIXED; NOT-VERIFIED list.
