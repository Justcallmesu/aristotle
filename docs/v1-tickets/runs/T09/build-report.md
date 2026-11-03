# T09 build report: app connection core, every address, automatic switching, reconnect, PC offline (+D24/D26/D27/D28)
**Files.** Server: `httpapi/{httpapi,pair}.go` (`Address`, `Deps.Addresses`, 201 `addresses`, `GET /v1/addresses`), `app/{controlcmds,serve}.go` (one `addresses()` feeds `pair`, the 201 body
and `/v1/addresses`; the `pairAddr` "tailscale (HTTP)" comment is gone; long comment fixed), `relay/sock_unix.go` (D27: embeds `net.Conn`, not `*net.TCPConn`; own `SyscallConn`; every error
reads `write tcp …`), `relay/liveness.go` (`transportOf` stops after 8 layers, long line), `hermes/discover.go` (D26: resolve symlinks first, `Stat` markers, a duplicate served entry is
ambiguous). Docs: `protocol.md` §11.2 (both endpoints and the address rules), §12 (the LOWAT wording at old :497, reconnect rules by close code). App, new in `data/`: `PcAddresses`,
`EndpointChoice`, `Backoff`, `LinkState` (+`LinkEnds`), `LinkManager`, `NetworkWatch`; `ui/sessions/OfflineBanner.kt`, `ic_cloud_off.xml`. Changed: `CredentialCodec/Store`, `PcCredentials`,
`KeystoreTokenKey` (D24), `HttpClients` (D26), `ServerClient` (pair addresses, `addresses()`, `HttpLinkDialer`; `/v1/me` client and `MeResult` removed), `RelayRpc` (`linkClosed`),
`SessionRepository`, `SessionsViewModel/Screen`, `ConnectionPage`, `AppContainer`, `MainActivity` (`onStart` → foreground retry), `PairViewModel/Screen`, manifest (`ACCESS_NETWORK_STATE`),
strings EN+ID. `LinkOutcomes.kt` deleted.
**LinkState machine** (`LinkManager`, owned by `AppContainer`, `Dispatchers.Default`, one socket at a time). It follows `CredentialStore.state`: Paired starts it, anything else cancels it →
`Idle`. Start or pairing → `Connecting` → a round over the ordered addresses. For each address: open `/v1/ws`, wait up to 20 s for `gateway.ready`. Upgrade 401 or close 4401 → `Revoked`
(AppContainer wipes, pair screen, no retry). 4505 → `HermesUpdated` (waits only for Check again). 4503 → `HermesDown` (quiet retry every 10 s; any wake retries at once). Any other close
(1000/1001/1011/1013/4502/4429…), or open with no ready → `Offline(since, ConnectionLost)`. A transport failure, 403 or pin mismatch before open is a *miss*: try the next address. All
addresses missed → `Offline` with `CertificateChanged` (any pin mismatch) / `TailscaleMayBeOff` (tailnet unreachable while off Wi-Fi or the PC has no LAN address) / `Unreachable`. Ready →
`Online(endpoint, generation)`: failures reset, `lastGood` and `GET /v1/addresses` stored (no Keystore call). Backoff 1 s → 30 s cap with jitter in [step/2, step];
`Offline.nextAttemptAtMillis` is null while a round runs (banner shows the verdigris line only then). Wakes: network change (and pool eviction), `onStart`, Retry now; all end a backoff wait
at once. A network change while Online switches (new round, best kind first). Frames: OkHttp → `trySend` (unbounded) → parsed on the link's coroutine; responses go to `RelayRpc`, events and
server requests to `events: SharedFlow`. No `pingInterval`.
**Endpoint rule** (`EndpointChoice.order`): on Wi-Fi/Ethernet/unmetered non-cellular (a VPN carries its underlying transport) LAN first, else Tailscale first; the other kind always follows;
within a kind `lastGood` first, then stored order. OkHttp connect timeout 3 s. Separate `ConnectionPool` for LAN and Tailscale (D26).
**Store + D24/D28.** Entry = `addresses [{url,kind}]` + `last_good` + one pin. T07c `base_urls` → the only address (kind from its range) until the first refresh. The upgrade is written back
only after a successful decode; a pin-less or address-less entry is wiped without a write, and `load()` returns true → one-line "Pair it again" notice (`PairNotice.PairAgain`). D24:
`MissingKeyPolicy`: on SDK < 31 the first key miss is a transient `KeyStoreException` (pairing kept, Retry); a second miss in a row wipes. D28 server depth cap and "Tailscale may be off"
hint are done.
**Tests by name.** Go: `TestPairAndAddressesListTheListeners`, `TestPhoneGetsEveryAddressPairPrints`, `TestTrackedConnCopyAndWritevGoThroughDrainTracking`,
`TestTrackedConnWriteErrorsLookLikeTCPConns`, `TestTransportOfIsBounded`, `TestRootFallbackResolvesLinksLikeHermes`, `TestServesNeedsExactlyOneEntryWithAnID`. Kotlin: `EndpointChoiceTest`
(order table, prefersLan), `BackoffTest` (schedule, jitter bounds), `LinkEndsTest` (every close code, misses, offline cause), `LinkManagerTest` (15: Wi-Fi LAN-first fallback, off-Wi-Fi
Tailscale-first, lastGood first, 4401/HTTP 401 no retry, 4401 while online, 4505 waits for user only, 4503 quiet retry, 1000/1001/1011/1013/4502/4429 backoff + reconnect, attempt counter
reset, network change retries at once, foreground retries, switch while online, Tailscale-may-be-off, RPC over the live relay, forget closes), `CredentialStoreTest` (T07c migration,
http→https, pin-less wipe with no write + notice flag, `remember`, no-address wipe), `MissingKeyPolicyTest` (3), `ServerClientTest` (pair addresses filter, `/v1/addresses`),
`SessionRepositoryTest` (4), `PinnedConnectionTest.eachAddressKindHasItsOwnPoolAndNoClientPings`, `PairViewModelTest` (stores every reported address, typed one first). Mutations run by me:
re-embedding `*net.TCPConn` fails both drain subtests; dropping the network wake and the failure reset fails 3 `LinkManagerTest` cases.
**Commands.** `cd server && gofmt -l . && go vet ./... && go test -race -count=1 ./...` → exit 0, gofmt silent, 11 packages ok. The first run failed:
`TestTrackedConnCopyAndWritevGoThroughDrainTracking/io.Copy` (the peer read before the buffers filled). I added a 200 ms wait; the test then passed 20/20 `-race`. `cd android && ./gradlew
--no-daemon lintDebug testDebugUnitTest assembleDebug` → exit 0, 111 tests, 0 failures. Lint: no new warnings (only the old version/CustomX509 ones). Poco: `adb install -r` ok. On cellular +
Tailscale VPN (Wi-Fi off, the original bug) the screenshot shows `100.74.61.96 · via Tailscale` and the list. The stored T07c entry is migrated to `addresses` + `last_good` on device
(`run-as`, token redacted).
**NOT-VERIFIED.** Offline banner, the reconnect progress line and the 4503/4505 pages on device (that would need the demo server stopped or phone settings changed; both are off-limits).
Wi-Fi↔cellular switching on device. `/v1/addresses` on device: the demo server on :8760 runs the T07c build, so the app keeps its stored list. The Android 10–11 Keystore path (no such
device; tested only through the pure policy). The final APK is installed; the phone was locked for the second screenshot. Board rows D24/D26/D27/D28 are left to the PM.
