# T05c build report

- **D9 FIXED** `hermes/rest.go` `dropUnlisted` deletes by exact map key. Test `TestDoStripsNonCanonicalCredentialKeys` (lowercase `authorization`, `x-hermes-gateway-ticket`, `cookie`).
- **D10 FIXED** allowlist Accept, Accept-Encoding, Content-Type, Content-Length, User-Agent. Test `TestDoForwardsOnlyAllowlistedHeaders` (X-Forwarded-*, Forwarded, X-Real-Ip dropped). Old test's `X-Keep` expectation flipped.
- **D12 FIXED** fake `closeAfterAck`; `TestCloseCodesSurfaceOnReadAfterDial` (1013 -> ErrDraining, 4403 -> ErrForbidden, `CloseCode`).
- **D13 FIXED** `listen/bind_linux.go`: `deviceControl` (injectable setter) via `ListenConfig.Control`, used by `BindTCP` with `e.Iface`. `bind_other.go` is a no-op off Linux (not built here). SO_BINDTODEVICE was NOT refused: kernel `7.1.4-arch1-1`, unprivileged, no EPERM.
  Tests: setter called once with fd+iface; setter error refuses the bind; real option on `lo`; nonexistent iface fails.
- **D14 FIXED** (a) one `httpapi.RejectLog` on `server`, passed via `Deps.Rejects` (test `TestSharedRejectLogLimitsAcrossRouters`: 3 routers -> 1 line). (b) `printPairing` uses `pairing.MaxAttemptsPerPeer` (asserted in `TestPrintPairing`). (c) control lock taken first in `newServer` (`control.Server.Lock/Unlock`; `Listen` reuses it); `TestSecondServerRefused` now checks the 2nd server fails in `newServer` without logging "tls certificate". (d) `docs/protocol.md` §11: invalid UTF-8 stripped; LAN interface must be up and running (+ SO_BINDTODEVICE line).
- **D15 FIXED** `decodeEnvelope(answer)`, `sizeOK` removed.

## Gates (cd server)
- `gofmt -l .` -> empty, exit 0
- `go vet ./...` -> exit 0
- `go test -race -count=1 ./...` -> all packages ok, exit 0

## Manual (temp XDG dirs under scratch, port 18762, stopped afterwards; real ~/.hermes untouched)
- `ss -ltn`: 192.168.1.61%wlan0:18762, 127.0.0.1:18762, 100.74.61.96%tailscale0:18762, [fd7a:115c:a1e0::d628:3d61]%tailscale0:18762 (same set as T05b).
- `curl http://127.0.0.1:18762/healthz` -> `{"status":"ok","version":"dev"}`; `curl -k https://192.168.1.61:18762/healthz` and `http://100.74.61.96:18762/healthz` -> same, so binds still serve with the device pin.
- `pair` prints wlan0 HTTPS first, tailscale0 after, "5 wrong tries".
- 2nd `serve` -> "lock ... held: is aristotle-server already running?", refused before cert load.
- Server killed; `ss` shows 0 listeners on 18762.

Not verified: a real foreign-interface packet being dropped (needs a second host). An earlier attempt left a stray empty-state run dir `scratch/t05c` (failed start, nothing listening).
Files: server/** (rest.go, control.go, conn-adjacent tests, listen/bind_*.go, manager.go, httpapi/{httpapi,ratelog,pair}.go + tests, control/control.go, app/{serve,cli}.go + tests), docs/protocol.md §11.
