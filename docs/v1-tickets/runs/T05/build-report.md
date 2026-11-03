# T05 build report — listeners, device auth, manual pairing, devices list/revoke

**Files.** New: `internal/listen/{addr,gate,manager}.go` (classify, select, peer gate, 10 s re-scan, `BindTCP` refuses wildcard/loopback/off-network);
`internal/tlscert` (ECDSA P-256 self-signed, SPKI fingerprint); `internal/statefile` (0600, O_NOFOLLOW read, temp+fsync+rename+dir-fsync write);
`internal/pairing` (codes); `internal/devices` (store); `internal/control` (unix socket, `peercred_linux.go` SO_PEERCRED via `syscall.GetsockoptUcred`);
`internal/httpapi/{auth,pair,logging}.go`; `internal/app/{serve,cli,controlcmds}.go`. Edited: `config/config.go`, `httpapi/httpapi.go`, `app/app.go`. Tests: `*_test.go` in each.
Std lib + `syscall` only (`go list -deps ./cmd/...` lists no third-party module). I did not touch `go.mod`/`go.sum`/`internal/hermes` (T04 changed go.mod).

**Config keys added.** `lan_enabled` (default true), `tailscale_enabled` (default true). Exact-case, a `null` value is refused, and so is a non-object file.
**Endpoints.** On LAN (HTTPS) and Tailscale (HTTP): `GET /healthz` (open), `POST /v1/pair` (open; code in the JSON body, 4 KB cap, strict fields) -> `201 {device_id, token}`,
`GET /v1/me` (Bearer) -> `{device_id, name}`, other `/v1/*` -> 401 without a token and 404 with one. Loopback keeps only `/healthz`.
**Control socket** `<state>/control.sock` 0600: `pair.start {name}`, `devices.list`, `devices.revoke {id}`. A live socket blocks a second server; a stale one is replaced; the socket is removed on shutdown.
**CLI.** `pair [--name NAME]` prints the code, expiry, LAN/Tailscale URLs and fingerprint. `devices list` and `devices revoke <id>` exit 1 when the server is not running.

**Decisions to check.** (1) Interfaces must be UP **and RUNNING**, so down docker bridges are skipped. (2) `--name` wins over the phone's `device_name`. If neither is given, the name is `device`.
(3) A half cert pair (one of the two files missing) or a loose key mode is a startup error, not a silent regeneration, because regenerating would break every phone's pin. (4) `last_seen_at` is written at most once a minute per device.
(5) Revoke deletes the entry. (6) Peer-gate 403s are not logged. (7) Crockford input aliases are accepted (O->0, I/L->1, hyphens/spaces ignored).

**Tests (all `-race`).** listen: Classify table, Select table (fake interfaces: loopback, down, link-local, public, `0.0.0.0`, `::`, duplicates), BindTCP wildcard/loopback/off-network refused,
PeerGate table, manager add/remove logged once per change, bind failure logged once then retried, scan error keeps listeners, real serve -> 403 for a non-tailnet peer, then stop.
tlscert: created once and reused (same fingerprint), key and cert 0600, P-256, fingerprint regex + SPKI hash, half pair refused, 0644 key refused.
pairing: alphabet/length over 500 codes, 5 min expiry boundary, single use, 4 wrong tries keep the code, the 5th kills it, a new code replaces the old, 20 concurrent redeems -> 1 winner.
devices: 32-byte base64url token, revoke -> refused, reload keeps revocation, file 0600 holds only the hash, no temp file left, last_seen throttle, loose/unknown-field file refused.
statefile: atomic replace (new inode, 0600 over an old 0644), failed rename leaves no temp file, writing over a symlink does not follow it, symlink/loose/`../` reads refused, other-owner refused (injected).
control: round trip with real SO_PEERCRED, injected other uid -> forbidden and the handler never runs, stale/live/non-socket path handling. httpapi: pair -> `/v1/me` 200, name precedence,
bad requests (code only in the query, unknown field, long name, control chars, 5 KB body) are 400 and do not use up an attempt, reuse -> 401, auth table, revoke -> 401 on the next request,
captured-slog redaction (code, token, `Bearer`, `Authorization` absent). app: e2e CLI `pair` -> HTTP pair -> `devices list/revoke` -> 401, server log free of secrets, CLI without a server, second server refused.

**Commands** (in `server/`): `gofmt -l .` -> empty, exit 0; `go vet ./...` -> exit 0; `go test -race -count=1 ./...` -> exit 0 (all 10 packages ok, `internal/hermes` included).

**Manual run** (temp `XDG_STATE_HOME`/`XDG_CONFIG_HOME`, real IPs, port 8760; stopped with SIGTERM; 0 listeners on 8760 afterwards; temp dir deleted):
```
state: drwx------ dir; srw------- control.sock; -rw------- tls-cert.pem tls-key.pem (devices.json 0600 after pairing)
pair --name test -> exit 0; code <CODE>, "Expires 10:38:44 (in 5m0s)"; Wi-Fi https://172.28.0.1:8760, https://192.168.1.61:8760;
  Tailscale http://100.74.61.96:8760, http://[fd7a:115c:a1e0::d628:3d61]:8760; fingerprint 7E54 D87B … BCA7 EAA7
POST https://192.168.1.61:8760/v1/pair wrong code -> 401; right code -> 201 {"device_id":"dev_36798b45364e","token":"***"}; same code again -> 401
GET /v1/me Bearer *** : https LAN 200, http 100.74.61.96 200, http [fd7a:…] 200 -> {"device_id":"dev_36798b45364e","name":"test"}; bad token -> 401
openssl SPKI SHA-256 of the served cert == the printed fingerprint; pairing over Tailscale -> 201 (dev_b20f1342585b, curl-ts)
curl --interface 100.74.61.96 https://192.168.1.61:8760/healthz -> 403; curl --interface 192.168.1.61 http://100.74.61.96:8760/healthz -> 403
devices list -> 2 rows, exit 0; devices revoke dev_36798b45364e -> exit 0; /v1/me after revoke: LAN 401, Tailscale 401
serve.log: 0 matches for both codes, the token and "Bearer"; unmatched loopback path logged as route=unmatched; restart -> created=false, same fingerprint
```
**Deferred findings.** D1 FIXED (`route=unmatched` + status; tested). D2 FIXED (`syscall.Open` O_NOFOLLOW|O_DIRECTORY, then fstat and fchmod on the handle; the files inside are
read with O_NOFOLLOW + fstat and written by rename, which never follows a link). D3 FIXED (fake FileInfo with `Stat_t{Uid: uid+1}`, in both config and statefile). D5 FIXED (`null`/`[]` -> "must be a JSON object").

**For the PM.** The UP+RUNNING docker bridge `br-2c441c1f2fd0` (172.28.0.1) gets a LAN listener and is printed first under "Wi-Fi". The brief says "every RFC1918 address", so I followed it.
Containers on that bridge pass the peer gate but still need a code or token. Options: show the interface name next to each URL, or add an interface allow/deny setting.

**NOT-VERIFIED.** A real phone (T07). A live interface change (the 10 s re-scan is covered by fake-interface tests only). A peer with a different uid on the socket (only injected).
An expired code and the 5-try lockout over a real listener (unit/handler tests only). `peercred_other.go` only vetted (`GOOS=darwin go vet ./internal/control` ok), never run.
