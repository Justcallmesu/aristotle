# T07c build brief — HTTPS with the pinned certificate on every listener (Q49)

route: deep (security boundary on both sides)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` §2 + Q3, Q7b, Q49, `docs/protocol.md` §11, §12,
board row D23, `docs/v1-tickets/runs/T07b/review-report.md`, then the code: `server/internal/{listen,app,tlscert,httpapi}`,
`android/.../data/{HttpClients,TailnetRoute,AndroidVpnRoutes,ServerAddress,PinnedTls,CertificateProbe,CredentialStore}.kt`,
the pair screen, manifest and network security config.

## Goal
No cleartext between phone and laptop on any network. The Tailscale listener serves HTTPS with the same certificate as the LAN
listener; the app pins that one fingerprint for every address of a PC and refuses `http://` entirely.

## Server
- Tailscale listeners use TLS with the same cert/key as LAN (same `tls.Config` cloning rule as T05b). Add the tailnet IPs to
  the cert SANs only if cheap; pinning is by SPKI, so SANs are not required — keep the existing cert (fingerprint must NOT change
  for already-paired phones). Plain HTTP stays only on the loopback health listener.
- `pair` output: every remote URL is `https://`; one fingerprint line.
- The T06d trackedConn / NetConn path must work on the Tailscale TLS listener too (test).
- Tests: Tailscale listener refuses a plain-HTTP request; serves `/healthz` over TLS; relay works over it.

## App
- Every remote request is `https://` with the pinned trust manager. Delete the cleartext interceptor, the VPN-binding decision
  (`TailnetRoute`, `AndroidVpnRoutes`) and their tests, `ACCESS_NETWORK_STATE` if nothing else needs it, and set the network
  security config to `cleartextTrafficPermitted="false"` with no exceptions.
- Pairing over a Tailscale address shows the fingerprint compare card exactly like LAN.
- Credential store: one pinned fingerprint per PC, used for all its addresses (LAN and Tailscale). Stored `http://` base URLs from
  earlier builds: migrate to `https://` with the same host:port and keep the pin (if no pin is stored, require re-pairing).
- Address rule: private LAN or tailnet IP -> allowed; anything else refused (unchanged). `Proxy.NO_PROXY` stays.
- Tests: no code path builds an `http://` URL for a remote host; migration; pin applies to a tailnet URL.

## Docs
`docs/protocol.md` §11.1 table and §12 upgrade text: `wss://` on every listener; remove cleartext wording. Board D23 resolved.

## Hard rules
Files: `server/**`, `android/**`, `docs/protocol.md` §11/§12, `docs/v1-tickets/runs/T07c/build-report.md`. No new modules / SDK
packages. No commits. Do not stop the demo server on port 8760 (the PM restarts it). Never connect to the real Hermes.

## Verify
`cd server && gofmt -l . && go vet ./... && go test -race -count=1 ./...`;
`cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug`;
`grep -rn 'http://' android/app/src/main` shows no remote-URL construction (report the remaining hits and why they are fine).

## Report
`docs/v1-tickets/runs/T07c/build-report.md`, ≤35 lines.
