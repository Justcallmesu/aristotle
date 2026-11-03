# T07c build report — HTTPS + pin on every listener, no cleartext (Q49, D23)

**Server.** `listen.Manager` serves every endpoint with `ServeTLS` and a per-server `TLS.Clone()` (T05b rule, no `Kind`
branch left); without a TLS config nothing is bound (never falls back to plain). `Endpoint.URL` is always `https://`; `pair`
says "Tailscale, HTTPS" and prints one fingerprint line "for every address". Plain HTTP only on loopback `/healthz`. The
cert is untouched (`LoadOrCreate` loads the existing pair); tailnet IPs join the SANs only for a newly created cert
(`listen.Addrs` replaces `LANAddrs`). `relay` hijack: `transportOf` unwraps every `NetConn()` layer, not just `*tls.Conn`,
so a wrapper above `WrapListener` (the tests' fake tailnet peer) still reaches the `trackedConn`.
**Tests:** `TestTailscaleListenerRefusesPlainHTTP` (TLS layer's 400, handler not reached), `TestManagerWithoutTLSBindsNothing`,
`TestTailscaleListenerIsPinnedTLS` (real `newServer`: plain 400; `/healthz` 200 and `/v1/ws` gateway.ready over TLS pinned
to `srv.cert.Fingerprint`), `TestRelayOverTailscaleTLSListenerWatchesWrites` (T06d `s.raw` is the `trackedConn`).
Mutations: Tailscale back to `Serve` → 3 tests fail; one-layer unwrap → relay test fails.
**App.** Deleted `TailnetRoute.kt`, `AndroidVpnRoutes.kt`, `TailnetRouteTest.kt`, `CleartextGuard`, the `TailscaleOff`/
`CleartextRefused` problem/issue/page/strings (EN+ID), `PairStep.Tailnet`, `ACCESS_NETWORK_STATE`. NSC
`cleartextTrafficPermitted="false"`, system anchors, no exceptions. `HttpClients.pinnedTo(pin)`: `NO_PROXY`,
`DirectSocketFactory` (own file), pinned trust manager + hostname verifier, `connectionSpecs(MODERN_TLS)` only, so OkHttp
refuses `http://`/`ws://` before opening a socket. `baseUrl` always `https://`. Pairing always probes and shows the compare
card; pin is non-null in `PairingApi`, `PcTarget`, `PcCredentials`. Migration: `CredentialCodec.upgrade` rewrites `http://`
base URLs to `https://` (same host:port; pin and sealed token kept, no Keystore call), `CredentialStore` persists it; a
missing/bad pin decodes as `Unreadable` → wiped → pair screen. Address rule unchanged.
**Tests:** `thePinAppliesToATailnetUrl` (https://100.101.102.103 via a redirecting socket factory: right pin 200, wrong pin
`CertificateChanged`, no request), `plainHttpIsRefusedBeforeConnecting`, `relayToATailnetAddressIsPinnedWss`,
`everyAcceptedAddressGetsAnHttpsBaseUrl`, migration (persisted, token blob unchanged), no pin → re-pair, PcTarget tailnet pin,
`aTailnetPairingComparesAndStoresThePin`. Mutations: no `connectionSpecs` / no migration → 2 tests fail.
**Docs.** protocol.md §11 intro, §11.1 table + TLS-only bullet + pair output, §11.3 every listener (pin, migration), §12
`wss://` on both. Board D23 not edited (tickets.md outside my file list): PM to mark it resolved.
**Gates.** Server: gofmt empty, vet ok, `go test -race -count=1 ./...` all ok. Android: lintDebug testDebugUnitTest
assembleDebug BUILD SUCCESSFUL (re-run after the mutation checks), 87 tests 0 failed; 11 lint warnings, all pre-existing.
`grep -rn 'http://' android/app/src/main`: XML namespace URIs (manifest, drawables, mipmap), the Apache licence URL in
`font_certs.xml`, a KDoc mention in `HttpClients.kt`, and `LEGACY_SCHEME` in `CredentialCodec.kt` (matched against old
stored entries to rewrite them, never used to connect). None builds a remote URL.
**For the PM.** Restart the demo server (8760, not touched) to get the TLS Tailscale listener, then on the Poco pair over
100.x: same fingerprint as LAN. A T07b tailnet-only pairing has no pin: wiped at first start, pair screen without a notice.
Tailscale off + 100.x now shows the generic "can't reach" page (TailscaleOff page gone).
