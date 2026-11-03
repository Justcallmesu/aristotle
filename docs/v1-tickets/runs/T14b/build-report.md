# T14b build report — sessions screen minors

Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T14b` → exit 0 (PASS android; lint, unit tests, assemble).

## Changes (android/** only)
1. **History failure keeps live rows** (`SessionRepository`): a failing `scope:"stored"` call no longer becomes `ListFailed`. `Sessions.historyProblem` is set, live rows are sent, and the list ends in a `HistoryEnd.Failed` footer, rendered as "Couldn't load history · Retry" (`SessionsScreen`, `retryHistory()`, EN/ID strings). A failing live call is still `ListFailed`.
2. **Traffic**: `sessions.changed` refetches live only. The stored page is cached per request (profile, limit, nonce) and refetched only on show, load more and reload/Retry/rename. Refetch is coalesced: the first event opens a 2 s window (`FeedTimings.liveRefetchMillis`), later events join it, so at most one refetch per 2 s. The watcher keeps its own 1 s `refreshDebounceMillis`. Pull to refresh added (`PullToRefreshBox`, `SessionsViewModel.refresh()` → `reload()`; offline it is `retryNow()`).
3. **Unserved profile**: `ListFailed.unserved` (reason `profile_mismatch`, profile ≠ launch). `ProfileSelection.fallBack()` forgets the profile and sets `fellBackFrom`. The VM follows the selection flow and shows a one-time snackbar `ProfileFellBack`. `SessionWatcher(onUnserved=…)` calls it on `profile_mismatch`, wired in `AppContainer`, so the background watcher falls back with no screen open and notifications continue.
4. **NITs**: NIT-8 `sessions_empty_body` says "PC" (EN/ID). NIT-7 no auto-paging while searching (the Load more button remains). MINOR-1 wording fixed in the `rename()` KDoc ("last writer wins, unlike the Desktop").

## Tests that failed before the fix
Old behaviour restored in a scratch copy (`scratch/t14b-before`, no git); 8 of 40 failed there and all pass now:
- `SessionRepositoryTest`: aFailedHistoryKeepsTheLiveRowsAndReportsTheProblem, sessionsChangedRefetchesOnlyTheLiveList, aStreamOfSessionsChangedGivesAtMostOneLiveRefetchPerWindow, aBurstOfSessionsChangedRefetchesOnceAfterAList (updated to 3 `session.list` calls).
- `SessionWatcherTest.anUnservedProfileIsReportedNotWatchedSilently`.
- `SessionsViewModelTest`: aFailedHistoryKeepsTheLiveRowsAndRetryRecovers, sessionsChangedRefetchesLiveOnlyAndPullToRefreshBoth, anUnservedRememberedProfileFallsBackToTheLaunchProfileWithOneNotice.

New, not run against old code: `ProfileSelectionTest` (2), `SessionRowsTest.aFailedHistoryKeepsItsRetryRowEvenWhileSearching`, `aFailedLiveCallIsStillTheListsFailureAndNamesAnUnservedProfile`. The repository stream test asserts 2..4 refetches over ~65 events, which is timing-dependent.

## Not done
- `docs/protocol.md` §4 still says "as Hermes intends" (outside allowed files). Only the KDoc was fixed.
- NIT-5, NIT-6 and NIT-9 are not in the files touched, or need a design change. Left alone.
- On a failed Load more, the history rows are replaced by the Retry row until the retry succeeds.
- Pull to refresh is not covered by a UI test; the VM path is tested. Not installed on the phone.
