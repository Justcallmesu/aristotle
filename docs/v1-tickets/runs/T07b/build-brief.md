# T07b build brief — app + connector fixes (T07 review, device test)

route: deep (security on the phone + Hermes discovery)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/design.md`, `docs/protocol.md` §1.1, §11, §12,
`docs/v1-tickets/runs/T07/review-report.md` (all findings), board rows D18, D19, D21, D22 in `docs/v1-tickets/tickets.md`, then the code.

## Fix (each with a test unless marked)
- **D21 (MAJOR)** `android/.../data/HttpClients.kt`: plain HTTP to a tailnet-range IP only when Tailscale is actually up: bind
  those clients to the active VPN `Network` (`ConnectivityManager`, `NetworkCapabilities.TRANSPORT_VPN`; use `network.socketFactory`
  for OkHttp) and refuse with a clear "Tailscale is off" error when no VPN network exists. `Proxy.NO_PROXY` on every OkHttp client.
  Unit-test the decision function (VPN present/absent × address kind); device behaviour is checked by the PM.
- **D22**: a transient Keystore decrypt failure must not wipe the pairing (wipe only on a definitive "key permanently invalidated"
  / bad tag; otherwise show a retry error); Keystore and file work off the main thread; `ServerAddress.parseIpv6` must never
  resolve DNS (pure parsing; test with a hostname-like input); move the relay connection/session-list logic out of
  `SessionsViewModel` into `data/` (e.g. `SessionRepository`) with unit tests against a fake socket; add the 3 missing security
  tests (wss upgrade with wrong pin, ws upgrade to non-tailnet host, pairing uses the probed pin); fix the 4 NITs from the report.
- **D19**: default device name = the phone's user-visible name (`Settings.Global.DEVICE_NAME`, fallback `Build.MANUFACTURER + MODEL`
  prettified), e.g. "POCO X5 Pro 5G", never the model code.
- **D18 (server)** `server/internal/hermes`: when `$HERMES_HOME` is a profile home without `gateway.sock` (e.g.
  `~/.hermes/profiles/pm`), find the root gateway the way Hermes' own clients do (read `gateway/control_socket.py`
  `resolve_client_socket_path` and `hermes_constants.get_default_hermes_root()` or equivalent in the Hermes source at
  `/home/justcallmesu/.hermes/hermes-agent/` and cite). Test with a fake profile home. The served-profile check stays.

## Hard rules
Files: `android/**`, `server/internal/hermes/**`, `docs/v1-tickets/runs/T07b/build-report.md`. Not `server/internal/relay/**`
(T06d in parallel). No new SDK packages. No commits. You may install on the attached Poco and screenshot; do not change phone settings.
Never touch the real `~/.hermes` beyond reading files.

## Verify
`cd server && gofmt -l . && go vet ./... && go test -race -count=1 ./internal/hermes/...`;
`cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug`.

## Report
`docs/v1-tickets/runs/T07b/build-report.md`, ≤40 lines: each D-item / finding -> FIXED (test) / WONTFIX (why); commands + exit codes.
