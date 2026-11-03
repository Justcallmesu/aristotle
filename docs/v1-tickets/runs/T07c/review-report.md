# T07c review — HTTPS + pin on every listener (Q49, D23)
Re-ran `cd server && go test -race -count=1 ./...`: all ok. Android not run (gradle off-limits; PM gate-10 green).
**Checks (all hold)**
- No plain remote listener: `listen/manager.go:135` `bind` refuses before any socket when `TLS==nil`; `:163` per-server `Clone`,
  `:168` `ServeTLS` is the only serve path (no `Kind` branch), re-scan rebinds included. A cert-less TLS config makes `ServeTLS` fail,
  never plain. Only `srv.Serve` is loopback `/healthz` (`app/serve.go:166`, loopback-only host). Cert: `LoadOrCreate` keeps an
  existing pair; the new LAN+tailnet SANs (`serve.go:112-115`) reach only a new cert, so pins do not change.
- No app cleartext: `HttpClients.kt:43` `connectionSpecs(MODERN_TLS)` only (OkHttp throws before dialing; test asserts no socket),
  `ws://` maps to `http://` so it fails the same way; NSC `cleartextTrafficPermitted="false"`, no domain-config, no debug overlay;
  redirects off; `baseUrl` always `https://`; relay URL is `baseUrl.resolve("/v1/ws")`; probe sends nothing.
- One pin per PC: `PcCredentials.fingerprint`/`PcTarget.pin` non-null; `pinnedTo(pin)` is the only client builder (pair, REST,
  relay). `thePinAppliesToATailnetUrl`: wrong pin sends no request.
- Migration: `httpsBaseUrl` maps only `http://`→`https://` (no downgrade); missing/bad pin → `Unreadable` before any Keystore call
  (`CredentialCodec.kt:51-53`) → entry wiped, no token kept without a pin. `PcTarget` re-derives `https://` from the address anyway.
- Dead code: grep for TailscaleOff, CleartextRefused/Guard, PairStep.Tailnet, fp_tailnet, VpnRoutes, TailnetSocket,
  isTailnetHost, networkOfHost, LANAddrs, forEndpoint, ACCESS_NETWORK_STATE over server/android/docs: nothing; both locales clean.
- `transportOf` (`relay/liveness.go:145`): prod chain `tls.Conn`→`trackedConn` (embeds `*net.TCPConn`, no `NetConn`), so it stops
  at the WrapListener wrapper, the right conn; non-Unix stops at `*net.TCPConn`.
- protocol.md §11 intro, §11.1, §11.3, §12 match the code (TLS 400, nothing bound without TLS, migration rule).
**Findings**
- MINOR `CredentialStore.kt:72-80`: a pin-less T07b tailnet pairing is wiped to the pair screen with no notice saying why. Meets
  the brief (re-pair required); a `PairNotice` would help the Poco user. PM call.
- NIT `relay/liveness.go:146`: unbounded loop; a `NetConn()` returning itself would hang the hijack. No such type exists and the
  multi-layer case serves only the test fixture `tailnetPeerConn` (`relay_test.go:288`). Optional depth cap.
- NIT: Tailscale off on the phone now shows the generic "can't reach" page (or "certificate changed" if a CGNAT host answers TLS
  on 8760). Safe (pinned, token never sent), just less specific.
- NIT `CredentialStore.kt:85` writes the upgraded entry even when `decode` then wipes it; `app/serve.go:47-48` comment overlong.
- PM: board D23 still "IN T07c" (`tickets.md:76`), left to PM by file scope.

VERDICT: PASS-WITH-FINDINGS
