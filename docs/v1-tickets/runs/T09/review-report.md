# T09 review: connection core (fresh reviewer, deep)
Scope: `git diff HEAD -- server android docs/protocol.md` + untracked files. My run: `cd server && go test -race -count=1 ./...` → all 11 packages ok. No gradle (per brief).
Checked OK: address trust (`PcAddresses.endpointsOf`: https only, IP literal, RFC1918/tailnet range must match `kind`; path/userinfo/query refused by `ServerAddress.parse`; `withLink`/`remember` never touch the pin; Bearer goes only after the pinned TLS check);
close codes vs §12 (`LinkEnds.ofClose`: 4401/HTTP 401 → Revoked + wipe, 4505 waits for Check again only, 4503 every 10 s, the rest backoff; 403/transport/pin miss → next address); Backoff in [S/2,S], 1→30 s, reset on `gateway.ready`;
`collectLatest` cancels and joins the old PcSession, so one socket at a time and no state from a cancelled session; socket opens on collect and closes on cancel; no `pingInterval`; frames leave OkHttp via an unbounded `callbackFlow` buffer;
NetworkCallback (default network, StateFlow-deduped, so signal-strength updates do not wake); foreground wake via `onStart` is ignored while Online, so no storm; D24 (SDK < 31 excuses one miss), D26 (pool per kind + evict on network change),
D27 (`net.Conn` embed; re-embedding `*net.TCPConn` would fail the new test's `io.ReaderFrom` check), D28 (the upgrade is written only after decode; PairAgain notice; depth cap), `/v1/addresses` behind `requireDevice` with a 401 test, EN/ID key parity.

## MAJOR (unproven)
1. `data/LinkManager.kt:259-262` + `CredentialStore.kt:82`: each refresh replaces the stored address list with the server's current list, so every address the PC is not bound on at that moment is forgotten, the typed one included.
   Scenario: the laptop is at the office and the phone connects over Tailscale, so the refresh stores the office LAN + tailnet addresses and drops the home LAN address. Back home, with Wi-Fi on and Tailscale off on the phone, every address misses
   ("doesn't answer on Wi-Fi or Tailscale") until Tailscale is turned on or the phone pairs again. The T07c build kept the typed address. The same loss happens if the laptop's LAN is still down after resume when the phone connects.
   Fix: merge instead of replace. Put the fresh entries first, then keep previously known addresses (at least the last-good one per kind) after them, with a small cap. Add a test for it.

## MINOR
2. `LinkManager.kt:235-246` with `ServerClient.kt:107-114`: `refresh` runs a blocking `execute()` inside `online()`'s `coroutineScope`. `refresh.cancel()` does not interrupt it, so when the relay ends or a Switch arrives during that request,
   the scope waits up to 3 s connect + 10 s read. Meanwhile the state stays Online and `live` is still set. Fix: launch the refresh in the link scope (not joined), or cancel the OkHttp call when the coroutine is cancelled.
3. `RelaySocket.kt:69-72`: `awaitClose` calls `ws.close(1000)` even on a socket still connecting or on a network that is gone. OkHttp then keeps connecting and waits up to 60 s for the close reply, and the server keeps its side for up to 40 s.
   Since T09, a network Switch triggers this; quick flapping can reach the 4-socket limit, and the resulting 4430 is treated as a backoff (it recovers). Use `cancel()` when the socket never opened or after a network change. (unproven)
4. `AppContainer.kt` link scope (`SupervisorJob() + Dispatchers.Default`) has no CoroutineExceptionHandler. Any unexpected throw in the link (for example `storage.put` in `remember`) escapes `collectLatest` and crashes the app, and the link stays dead. Add a handler, or catch per round and log.
5. `server/internal/relay/sock_unix_test.go:107`: the D27 drain test depends on `time.Sleep(200ms)` to fill the buffers. Per the build report the first run failed before this sleep was added, so it can flake on a loaded CI machine.
   Fix: poll until the writer is actually blocked (for example, wait for the peer's receive queue to stop growing) instead of a fixed sleep.
6. `NetworkWatch.kt:212-228`: a change is detected only by (handle, prefersLan). With Tailscale VPN as the default network, a Wi-Fi → Wi-Fi move keeps the same VPN handle and the same prefersLan, so nothing is emitted:
   no immediate retry and no pool eviction. Recovery then waits for the server's ping timeout (≤ 40 s) plus backoff. (unproven; could also follow underlying network or LinkProperties changes.)

## NIT
7. `LinkEnds.kt:109-117`: on cellular with Tailscale on and the PC asleep, the banner still says "Tailscale may be off". Checking `TRANSPORT_VPN` would sharpen the hint (D28 wording is met as written).
8. `values-in/strings.xml:55`: "Sedang berjalan pukul %1$s" reads as "is running at"; suggest "Terakhir berjalan pukul %1$s".
9. `OfflineBanner.kt:169`: the HH:MM in "PC offline since" is not tabular (design.md §2: times use `tnum`).
10. `LinkManager.kt:260`: `runCatching` swallows CancellationException (harmless because `remember`'s `withContext` rethrows). Catch IOException explicitly instead.
11. `LinkManager.kt:274-277`: `reached()` on 4503/4505 resets `offlineSince` but not `failures`, so a later loss resumes the old backoff step.
12. `SessionsScreen.kt:234`: during Connecting after a Switch, stale rows still show the live "Running" spinner (`offlineAt` is set only while Offline).
13. Banner copy merges Offline and Reconnecting ("PC offline since …" with "Trying again…" or "Try N failed · next in S s") instead of design.md's "Reconnecting… try N, next in S s". This is acceptable under the brief's "progress line only while reconnecting".

No BLOCKER. Finding 1 should be fixed (or recorded on the board as a decision) before T10+ builds on the stored list.
VERDICT: PASS-WITH-FINDINGS
