# T09 build brief — app connection core: every address, automatic switching, reconnect, "PC offline"

route: deep

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` §1 v1.2, Q3, Q25, Q27, Q49, `docs/design.md`
(banners, connection pages, offline state), `docs/design/mockups.html` screen 5 + the offline banner on screen 2, `docs/protocol.md`
§11, §12 (close codes, keepalive, client obligations), board rows D24, D26, D27, D28 in `docs/v1-tickets/tickets.md`, then the code
(`android/.../data/*`, `SessionsViewModel`, `server/internal/httpapi`, `server/internal/relay`).

## Problem (found on the device)
Pairing stores only the address the user typed. With Wi-Fi off and Tailscale on, the app keeps retrying the LAN address.

## Server
1. `POST /v1/pair` 201 body gains `addresses: [{url, kind: "lan"|"tailscale", iface}]` (the same list `pair` prints, all `https://`).
2. New `GET /v1/addresses` (Bearer) returns the current list, so the app refreshes after a DHCP change.
3. D27: `trackedConn` must not inherit `ReadFrom` / writev from the embedded `*net.TCPConn` (route them through `Write` or stop
   embedding); unify error text; fix the `protocol.md:497` wording; long line.
4. Docs §11: both endpoints.

## App
1. Credential store per PC: all addresses (kind + url), last-good address, one pin (unchanged). Migration from T07c entries
   (one url): keep it as the only address until the first `GET /v1/addresses` fills the rest.
2. **Endpoint choice** (pure, unit-tested): on an unmetered/Wi-Fi network try LAN addresses first, else Tailscale first; always
   fall back to the other kind; last-good address first within a kind. Per-attempt connect timeout ~3 s.
3. **One process-wide connection** (`LinkManager` or similar in `data/`, owned by the Application, not a ViewModel): opens
   `/v1/ws`, exposes `StateFlow<LinkState>` = Connecting / Online(address kind) / Offline(since, reason) / Revoked / HermesDown /
   HermesUpdated; screens observe it. SessionRepository uses it.
4. **Reconnect**: exponential backoff with jitter (1 s → 30 s cap), reset on success; immediate retry on a network change
   (`ConnectivityManager.NetworkCallback`, needs `ACCESS_NETWORK_STATE`), and when the app returns to the foreground. No retry on
   4401 (wipe + pair screen) or 4505 (Hermes updated page). 4503 Hermes not running: retry every 10 s quietly. 1001/1011/1013/4502:
   backoff. No OkHttp `pingInterval`; hand frames off the OkHttp reader thread at once (§12 client obligations).
5. **UI** per design.md: offline banner "PC offline since HH:MM" on the session list (verdigris progress line only while
   reconnecting), connection pages for Hermes down/updated; small text showing "via Wi-Fi" / "via Tailscale".
6. D24 (Android 10–11 keystore daemon error must not wipe), D26 (separate OkHttp connection pools per endpoint kind or compare
   socket factories; NITs), D28 (one-line "please pair again" notice when an old pairing is wiped; unwrap depth limit is server —
   include it; specific "Tailscale may be off" hint when only tailnet addresses are unreachable).

## Tests
Endpoint choice table; backoff schedule; state machine transitions per close code (fake socket); migration; network-change triggers
a reconnect; server: pair response + `/v1/addresses` + trackedConn ReadFrom goes through drain tracking.

## Hard rules
Files: `android/**`, `server/**`, `docs/protocol.md` §11/§12, `docs/v1-tickets/runs/T09/build-report.md`. No new modules / SDK
packages. No commits. Do not stop the demo server on port 8760 (PM restarts it). Never connect to the real Hermes from tests.
You may install on the attached Poco and screenshot; do not change phone settings.

## Verify
`cd server && gofmt -l . && go vet ./... && go test -race -count=1 ./...`;
`cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug`.

## Report
`docs/v1-tickets/runs/T09/build-report.md`, ≤45 lines: files; LinkState machine; endpoint rule; tests by name; commands + exit codes; NOT-VERIFIED.
