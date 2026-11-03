# T09b review: T09 review fixes (fresh reviewer, deep)
Scope: `git diff HEAD -- android server/internal/relay/sock_unix_test.go` + 3 untracked tests. No gradle (per brief); PM gate-12 GREEN taken as given.
I also read OkHttp 5.4.0's `ConnectPlan`/`FastFallbackExchangeFinder` bytecode (javap) for the cancel gap.

## T09 findings
1 FIXED. `PcAddresses.merge`: fresh first, then known not re-reported, distinct by URL (fresh wins), cap 8 drops from the tail, never `typed`/`lastGood`. Both inputs pass `endpointsOf` (https, IP literal, range = kind), so a refresh can't add an unsafe address. `withLink` and `remember` never touch `fingerprint`, and the link's `distinctUntilChanged` (pcId, token) does not restart on address writes. The office/home test uses the real store.
2 FIXED. `execute` = `enqueue` + `suspendCancellableCoroutine`, and `invokeOnCancellation` calls `call.cancel()`. On a late response, `response.use` closes the body before the resume, which is ignored, so nothing leaks. The refresh is detached and cancelled (not joined) in `online()`'s `finally`.
3 FIXED. `awaitClose`: `close(1000)` only after `onOpen`, `cancel()` otherwise. `Switch` calls `socket.cancel()` before `pump.cancel()`, and the later `close` on a cancelled socket does nothing.
4 FIXED. `nextRound` rethrows `CancellationException` and turns everything else into `Lost(AppError)`, which goes through `backOff` (≥1 s, doubling), so there is no tight loop. `unlessCancelled` also rethrows CE. A `CoroutineExceptionHandler` never receives CE, and it logs the type only. The accepted behaviour holds: a failing store keeps the relay up (test), and the in-memory `lastGood` stays old.
5 FIXED. `waitUntilAWriteWaits` waits for a real EAGAIN park and fails loudly after 10 s. No `t.Parallel` in the package, so no other goroutine can match. The 300 ms sleep left in the first test has a recorded reason.
6 FIXED in code, not checked on a device. The snapshot adds `vpn` and the IPv4 set of each non-VPN Wi-Fi/Ethernet link. Wi-Fi→Wi-Fi under a VPN changes `localLinks`, and VPN up/down changes `handle`/`vpn`. Signal, bandwidth, IPv6 and DNS updates are deduped by StateFlow. The seed from `allNetworks` prevents a change event at start. The callback wiring has no unit test.
7–13 FIXED: `!vpnUp` gate; ID "Terakhir berjalan"; `tnum` title; `runCatching` gone (`describeRuntime` catches RpcException/IAE only); `reached()` resets `failures`; `staleAt` = not Online; banner "Reconnecting… try N[, next in S s]" matches design.md §5. EN/ID keys match, and the old keys are removed from both files.

## Cancel gap (asked): REAL, does not matter
In OkHttp 5.4.0, `connectTcp` takes its plan off `call.plansToCancel` in a `finally`, and `find()` then calls `connectTlsEtc()`, which adds it back. Nothing checks `isCanceled` in between (the check only runs before the TCP launch). A `cancel()` in that thread-handoff window is missed.
Impact: against a silent peer, the TLS handshake waits at most the 10 s `soTimeout` (`readTimeout`, set in `connectSocket`). Against a live server, the upgrade most likely completes and is then dropped (unproven), so a short-lived ghost socket counts against the limit of 4.
The link is never delayed: `awaitClose` does not block, and late events go to a closed channel. The worker's change to the test (cancel after ClientHello) is the right fix for the flake. No product change needed.

## MINOR
A. `LinkManager.attempt`/`online`: Network wakes that arrive during the connect phase are not drained. The next `online()` receives one at once and ends a relay that has just come up (`Switch` → another round).
   This is likely after a move, because Wi-Fi gets its IPv4 address (via `onLinkPropertiesChanged`) seconds after `onAvailable`. The cost is one wasted reconnect plus a brief Online→Connecting flicker, not a storm. Fix: drain wakes when `gateway.ready` arrives, or `select` on Network wakes during the start phase too.
B. `EndpointChoice.order` + merge: stale entries of the preferred kind are tried before the live one when `lastGood` is of the other kind (e.g. home after office-over-Tailscale tries the office LAN first). Each costs up to 3 s connect (8 stored → roughly 15 s worst case). Consider a `lastGood` per kind later.

## NIT
C. `CredentialCodec`: pairings made by a T09 build (committed `abac4c3`) have `typed = null`, so only `lastGood` is protected from the cap. This only bites past 8 addresses.
D. `waitUntilAWriteWaits` matches Go runtime frame names (`(*pollDesc).waitWrite`). A runtime rename would fail it loudly after 10 s; it would not flake.

No BLOCKER, no MAJOR: no fix round needed. A and B can go into a later ticket. Wi-Fi→Wi-Fi under Tailscale still needs a device check (open item from the build report).
VERDICT: PASS-WITH-FINDINGS
