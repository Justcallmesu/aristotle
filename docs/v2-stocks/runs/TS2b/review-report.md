# TS2b re-review (fresh reviewer, read-only; no gradle/adb/server)
**MAJOR-1 is still open for HermesDown. The code on disk does not match the build report.**
- **Where:** `LinkState.kt:57` reads `is LinkState.HermesDown -> null`. The KDoc above it, the build report and the tests all expect `address`. So with Hermes down, `serverEndpoint` is null, and `reachable`, `api()`, `shownAndReachable` and the market line all act as if the PC were offline.
- **Why this slipped:** `LinkState.kt` was last written at 09:53:51. The gate (`gate-summary.txt`) ran at 09:52:57, so its PASS predates this line.
- **Tests that should fail as the code stands:** `LinkEndsTest.theServerEndpointIsKnownWheneverAristotleServerAnswered`, `StocksViewModelTest.stocksLoadWhileHermesIsDown` and `holdingsAndNewsLoadOnEveryShowAndWhenTheServerComesBack` (its line 121 sets HermesDown).
- **Fix:** change that line to `is LinkState.HermesDown -> address`, then rerun the android gate.
- `StocksSheets.kt` was also edited after the gate (09:53:12, the same moment as `build-report.md`). It reads correctly, but the gate has not compiled it. Rerun the gate before PASS.
**Checked OK:**
- HermesUpdated and Online give the address (`LinkManager.kt:230,235` pass `end.address`). Certificate changed, Idle, Connecting, Stopped and Revoked give null, and `StocksViewModelTest` covers these.
- **No change to chat or relay behaviour.** The only LinkManager edit is the extra address argument. Reconnect, backoff, `awaitUser`, the 10 s Hermes-down retry and `reached()` are untouched.
- Every consumer matches on the type with `is`, or maps the state first: discovery (`LanDiscovery.kt:372`), background (`BackgroundRules.kt:16,33-34`), the notifier (`LinkService.kt:68`, address-free `ConnectionNotice`), the log line (`AppContainer.kt:318`, class name only, no address logged), Settings, Chat and the Home pages.
- `ofClose` is now non-null; both callers (`LinkManager.kt:367,511`) already pass a non-null endpoint.
- One side effect, harmless: HermesDown values on different addresses no longer compare equal, so a retry on a new address can emit again. Every consumer maps or dedupes after that, so nothing visible changes.
- m1 closed: `noticeFor` plus `ConnectionNotice` show above the Stocks body for a changed certificate (Pair again) and Hermes updated (Check again). A refused token goes to Pair, so it never reaches Home. `ConnectionNoticeTest` covers it.
- m2 closed: Holdings and News report visibility through `LifecycleResumeEffect` and load when shown and reachable. A test covers this.
- m3 closed: the preview block is now `surfaceContainerLowest`. My recomputation gives light 6.44/6.42/6.44:1 and dark ≥ 10.45:1.
- m4 closed: `HomeModeSwitch.kt` is its own file, `heightIn(48)` is gone, and the touch area uses the Q67 hit-test expansion.
- No new MAJOR beyond the one above.
Not verified: on-device head height and touch area (no adb); the tests themselves (no gradle).
VERDICT: CHANGES-REQUIRED
