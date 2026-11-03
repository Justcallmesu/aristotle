# T07b build report — app security + connector discovery

**D21 (MAJOR) FIXED.** `data/TailnetRoute.kt` `Routes.decide(kind, vpnRoutesAddress)` → SystemDefault/ViaVpn/TailscaleOff. Tailnet clients use
`TailnetSocketFactory`. It checks on every new socket and binds the socket to the VPN network whose routes cover the IP (`AndroidVpnRoutes`:
`TRANSPORT_VPN` + `RouteInfo.matches`, `Network.bindSocket`). With no such network it throws `TailscaleOffException` → `ConnectionProblem.TailscaleOff`
→ address error or a "Tailscale is off" page (EN+ID). Every client sets `Proxy.NO_PROXY`. Sockets are `Socket(Proxy.NO_PROXY)` (`DirectSocketFactory`,
probe too), because `Socket()` asks the ProxySelector for SOCKS. Manifest gains `ACCESS_NETWORK_STATE`. Tests: `TailnetRouteTest` (decision table; tailnet
traffic goes out only via the VPN; Tailscale off → nothing sent for REST, pair or `/v1/ws`; LAN never asks for the VPN; ProxySelector never consulted).
**Device: NOT verified.** The APK installed (Success, no crash, permission granted), but the Poco was asleep and locked, so I took no screenshot.
Tailscale is installed; its VPN was down. PM to check: Tailscale off → "Tailscale is off"; Tailscale on → tailnet works.

**D22 FIXED.**
- Transient Keystore error keeps the pairing. `DecodeResult` Ok/Unreadable/Unavailable. Wipe happens only on: bad tag, missing key (decrypt uses
  `KeystoreTokenKey.existing()` and never creates a key), invalidated key, or an unparsable entry. `CredentialState.Unavailable` shows a page with
  Retry and Pair again. Tests: `CredentialStoreTest`, `KeystoreFailuresTest`. Not unit-tested: an UnrecoverableKeyException caused by
  android.security.KeyStoreException (matched by class name; the JVM android.jar cannot construct that class).
- Off main: `CredentialStore.load/save/wipeActive` are suspend on IO behind a Mutex (commit=true). A failed save while pairing gives `PairMessage.StoreFailed`, not a crash.
- `parseIpv6` is a pure RFC 4291 parser with no `getByName`. Tests: `hostnameLikeColonInputIsRefusedByPureParsing` (13 inputs, e.g. `1:2`, `192.168.1.5:8760:`), `ipv6LiteralsParseToTheirBytes`.
- Relay logic moved to `data/SessionRepository.kt` (`feed(): Flow<SessionFeedEvent>`, seams `PcLink`/`RelayChannel`); `LinkOutcomes` moved to `data/`.
  `SessionRepositoryTest` (fake socket): 4401 → Revoked; `/v1/me` 401 never opens the socket; ready timeout; one refetch per burst; ignored before the first list; list error; 4503.
- Security tests: `RelaySocketTest.wssUpgradeToAnotherKeyIsRefusedBeforeAnyRequest`, `…wsUpgradeToANonTailnetHostIsRefusedByTheCleartextGuard`,
  `PairViewModelTest.pairingUsesThePinTheUserCompared` (plus: an address edit drops the pin; tailnet → null pin; TailscaleOff; save failure).
- NITs: Details block on `surfaceContainerHighest`; `AristotleTextStyles.liveStatus`; `RpcFrames` requires `!id.isString` (test); ASCII-only
  `Fingerprint.parse` (test U+0663/U+FF11); `RouteStores` gives each route its own ViewModelStore and clears it on route change (no pile-up).

**D19 FIXED.** `DeviceName.of(Settings.Global.DEVICE_NAME, MANUFACTURER, MODEL)` (`DeviceNameTest`). On the Poco, `device_name` = `POCO X5 Pro 5G`.

**D18 FIXED.** `server/internal/hermes/discover.go`: if `<home>/gateway.sock` is not running and home is `<root>/profiles/<name>` of a provable Hermes
root, identify goes to the root's socket. The root is accepted only when its `served_profiles` lists this exact home. Tickets are then minted there, with
launch profile = this home. This follows Hermes' client path: `hermes_cli/gateway_runtime.py:289-305` → `:209-236` (`_served_by_multiplexer`), `:98-113`,
`:155-159` (`control_home_for`); `gateway_runtime_multiplex.py:18-24`; `hermes_constants.py:319-357`. Tests: 4 new (fake profile home → Dial and Do minted
for pm; not served → ErrHermesNotRunning and nothing minted; no root marker or a dot name → not followed; no gateway at all). Live identify-only run:
`Home=~/.hermes/profiles/pm` → `~/.hermes/gateway.sock`, launch `…/profiles/pm`. **PM decision:** under `HERMES_HOME=profiles/pm` the relay's
launch profile is now pm, not default (as with Hermes' own clients). protocol.md §1.1/§12 not updated (outside my files).

**Mutation checks** (each broke its test, then I restored the code): no VPN socket factory; no `!isString`; every decode failure treated as permanent;
null pin in pair; no `listed` guard; no root fallback; no served check; root-marker rule always true.
**Commands.** `cd server && gofmt -l .` (no output), `go vet ./...`, `go test -race -count=1 ./internal/hermes/...` → all exit 0.
`cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug` → exit 0 (87 tests, 0 failed; lint shows only warnings that were there before).
Demo server on 8760 untouched. No commits. A `git mv` staged the LinkOutcomes rename by mistake; `git restore --staged` undid it, and the index is clean.
