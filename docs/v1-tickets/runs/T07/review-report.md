# T07 review — pairing, pin, credential store, session list (fresh reviewer; code read, gradle not run)

**Checked and holding.**
- Pin: no path sends application data unpinned. `CapturingTrustManager` throws in `checkServerTrusted`, so the handshake ends before the client Finished (own `SSLContext`, no shared session cache).
- Pairing: `POST /v1/pair` uses the probed pin (`PairViewModel.kt:146`). The address field is disabled while busy and an edit resets the step, so the card cannot show one host and pin another. `PinnedHostnameVerifier` re-checks the pin.
- Cleartext guard: the unguarded base client is reachable only via `forEndpoint`, redirects are off, and `ws://` becomes `http://` and passes the guard. Names, public IPs, mapped/compat IPv6 and leading-zero/hex IPv4 are refused (fail closed).
- AES-GCM: a new `Cipher` per encrypt with a Keystore-chosen random IV, 256-bit key, AAD = pc+device id. A lost key makes decode fail, the entry is wiped and the app returns to pairing.
- Backup: `allowBackup=false` + cloud and device-to-device rules exclude every domain. Logging: no `Log`/`println`; `DeviceToken` redacted; exceptions carry only host or method.
- Design: palette A matches design.md role by role, no dynamic colour, type/shape tokens per §2/§3, verdigris only on the running spinner and text, no hex in composables. EN/ID: same keys and placeholders (checked by script).
- Protocol: no `pingInterval`; Bearer on the upgrade; 4401 and HTTP 401 (upgrade or `/v1/me`) wipe and return to pairing; 4503/4505 have their own pages; other closes show the generic page with retry. Ignoring `srq-` is safe: Hermes sends server requests only to clients attached to a session (`tui_gateway/server_requests.py`), and this screen resumes none.

**MAJOR (unproven)** `data/HttpClients.kt:18-24` / `ServerAddress.isTailnetHost`: "plain HTTP inside Tailscale" is checked by
the IP range only. 100.64.0.0/10 is also carrier and hotel CGNAT space (protocol §11.1). With Tailscale off, `http://100.x`
goes out over cellular or Wi-Fi. The pairing code and the Bearer token (every `/v1/me`, `/v1/ws`) then reach whatever host
holds that address. OkHttp also uses the system/Wi-Fi proxy, so a configured proxy sees the token. T09 endpoint switching
and T10 unattended reconnects make this repeat. Fix: build tailnet clients on the VPN `Network`: the `TRANSPORT_VPN`
network whose `LinkProperties` routes cover the IP, via `.socketFactory(vpn.socketFactory)`. Refuse ("Tailscale is off")
when there is none. Add `.proxy(Proxy.NO_PROXY)` on all clients. Ticket it before T09/T10 ship.

**MINOR** `CredentialCodec.kt:35-40` + `CredentialStore.kt:45-52`: `runCatching` treats every decode failure as "key gone",
including a transient Keystore error (daemon busy early after boot, which matters once T10 starts at boot). The pairing is
then wiped for good and the server keeps an orphan device. Wipe only on `AEADBadTagException`,
`UnrecoverableKeyException`, a missing key or bad JSON; otherwise keep the entry and show an error.
**MINOR** Keystore runs on the main thread. `AppContainer` is built in `MainActivity.onCreate`, and the store's init
decrypts. `PairViewModel.kt:151-161` runs `credentials.save` (encrypt) on Main without a catch, so a Keystore exception
crashes the app after the one-time code has been used. Move it to `Dispatchers.IO` and map failures to a message.
**MINOR** `ServerAddress.kt:114-120`: any `[0-9a-fA-F:.]+` string goes to `InetAddress.getByName`. A non-literal (`1:2`,
`192.168.1.5:8760:`) falls through to a DNS lookup. On the device it is refused only because StrictMode's
`NetworkOnMainThreadException` is swallowed by `runCatching`, so "never does a DNS lookup" is false off the main thread or
on the JVM. Use a pure IPv6-literal parser or `android.net.InetAddresses.parseNumericAddress` (API 29 = minSdk) behind a seam.
**MINOR** `ui/sessions/SessionsViewModel.kt:107-191`: `RelayLink` holds the ready timeout, RPC routing, close handling and
the refresh debounce. That is transport orchestration inside a ViewModel, and it has no tests. Brief item 7 puts transport
in `data/`, and T09/T11 will grow this code. Move it to `data/` (e.g. `RelayConnection`) and test it with a fake socket:
4401 → Revoked, ready timeout, refetch on `sessions.changed`.
**MINOR** Missing security tests: a pinned `wss://` upgrade to another key is refused (`RelaySocketTest` uses only the
right pin); a `ws://` upgrade to a non-tailnet host is refused by `CleartextGuard`; pairing uses the probed pin. Not
verified: Conscrypt keeping `PinMismatchException` in the cause chain (JVM tests use SunJSSE); if not, generic page, still closed.

**NIT** `ConnectionPage.kt:66`: the Details block uses `surfaceContainerLow`; design.md §4 puts code blocks on `surfaceContainerHighest`.
**NIT** `SessionsScreen.kt:193`: `bodyMedium.copy(fontWeight = W600)` is an ad-hoc style in a screen; add a token to `AristotleTextStyles`.
**NIT** `RpcFrames.kt:55`: a string id `"5"` matches pending call 5 (`longOrNull` parses string content); require `!id.isString`.
**NIT** `Fingerprint.kt:32`: `isDigit()` accepts Unicode digits. `AristotleApp.kt:23,29`: ViewModels keyed by epoch or pcId pile up in the store (harmless now).
**FYI** The build report's deviations (no QR, 4503 copy, `scope:"live"`, `started_at` as activity) are fine; `scope` needs a PM decision before T11.

VERDICT: PASS-WITH-FINDINGS
