# AF1 build report — flaky `RunningViewModelTest` "pull to refresh reads at once…"

## Outcome
Cause proven as a **test-only race**; fixed in the test. Product code (`RunningViewModel`, `RunningPoller`) untouched.
Full android gate after the fix: `lintDebug testDebugUnitTest assembleDebug` BUILD SUCCESSFUL, 1490 tests, 0 failed, 0 skipped.

## Reproduction (before the fix)
- Plain gradle loop of the class under 8 `yes` loaders: 2 iterations, both passed, ~3 min each. Too slow to catch a ~5% flake, so I stopped it.
- Temporary probe (`Af1RepeatProbe`, deleted afterwards) that runs the test body 4 threads x 40 times in one JVM, under 8 `yes` loaders:
  - pull-to-refresh test: **8/160**, **5/160**, **9/160** failed (3 runs), all `AssertionError: timed out waiting for refresh read` at `RunningViewModelTest.kt:78` via line 212, the same site as the gate failure.
  - `a profiles_list failure…` test: **6/160** failed, `timed out waiting for recovered`.
- Failure texts: `docs/desktop/runs/AF1/failures/repro-*.txt`.

## Proven cause
`RunningViewModel` keeps its own `state.online`, copied from `link.state` by a collector on `Dispatchers.Default` (init block). `RunningPoller` collects `link.state` separately. The test sees `link.reads == 1` (poller already read) and calls `vm.refresh()` at once. Under load the VM's collector has not yet delivered `Online`, so `refresh()` takes the offline branch (`link.retryNow()`), no kick, no second read, and the 3 s `eventually` times out.
Diagnostic run (temporary message in the test) over 9 failures, every one read: `vmOnlineBeforeRefresh=false retries=2 reads=1 refreshing=false`. This is exactly "refresh went the offline way". The same applies to `retry()` in the profiles_list test (`if (state.value.online) poller.kick() else link.retryNow()`).
Not a product bug: the window is the microseconds between the link state change and the VM's own collector, and a person's pull cannot land in it. The test must wait for the VM to show online before acting. Not a timeout problem either: the 3 s bound is never the limiting factor.

## Fix (`android/app/src/test/.../ui/running/RunningViewModelTest.kt`)
Two lines, condition-based waits before the action:
- pull-to-refresh test: `eventually(what = "the page showing online") { vm.uiState.value.online }` before the second `vm.refresh()`.
- profiles_list test: same wait before `vm.retry()`.
No sleeps added, no timeout or assertion loosened.

## After the fix
- Probe, same load (8 loaders), both tests in each of 4 threads x 100: **400 pairs, 0 failures** (`failures/after-fix-probe-400pairs-0fail.txt`). Before: 5-9 per 160 of one test alone.
- Class loop through gradle (`cleanTestDebugUnitTest testDebugUnitTest --tests '*RunningViewModelTest*'`), 4 `yes` loaders (one per core): **30/30 passed, 0 failures**.
- Full android gate once, no load: BUILD SUCCESSFUL; 1490 tests, 0 failures, 0 errors, 0 skipped (summed from the XML results).
- Not done: a mutant check (fix removed to bring the failure back) as a separate step. The "before" probe runs on the original file are that evidence.

## Other android tests with the same pattern
- The `eventually` helper exists only in `RunningViewModelTest` (copy-paste elsewhere: none).
- Real-time sleeps (`Thread.sleep`) occur in: `RunningViewModelTest` (negative checks "nothing is read", only fail if a read wrongly happens), `RingLogTest`, `LinkManagerTest`, `ChatViewModelBackgroundTest`, `ChatViewModelSubagentTest`, `ChatViewModelPromptTest`. Not touched: no failure observed, and the proven cause (a VM mirror of link state on a `Dispatchers.Default` collector, read straight after) is not shown there.
- ViewModel tests on `Dispatchers.Unconfined` (Cron, Screener, Stocks, BriefPick) are deterministic for this cause.
- `StockDetail*`/`TrackRecord` tests use `Default.limitedParallelism(1)` with condition waits (`waitFor`, 5 s bound, 5 ms poll).
- No test uses virtual time (`runTest`/test dispatchers): none to reuse, so I used the condition-based wait.
- Other tests of `RunningViewModelTest` that set online then act: only the two fixed ones act on the VM's mirror (`refresh`/`retry`).

## Housekeeping
- Only file changed: `RunningViewModelTest.kt` (+3 lines). The probe file was created and deleted; the test directory has no leftovers.
- All `yes` loaders and my gradle runs are gone (checked `pgrep -x yes` = 0). Early on I ran broad `pkill` patterns (`GradleDaemon`, `GradleWrapperMain`, `Gradle Test Executor`) to stop my first loop; another session's `release-test` gradle (pid 380418) survived, but if anyone's gradle died around then, that is why.
- Scripts and raw logs are in the scratch dir only (not in the repo).
