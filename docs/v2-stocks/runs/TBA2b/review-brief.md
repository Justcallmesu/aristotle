# TBA2 + TBA2b — re-review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`. Review the WHOLE uncommitted phone side of slice 3 (TBA2 + its fix
round TBA2b): `git status --short -- android` (modified + untracked). Server is committed at `3dac6ed` (contract `docs/protocol.md` §20.4 as
committed: use `git show HEAD:docs/protocol.md`); an uncommitted server ticket (TBA1c, `priceLike` only) is out of scope.

Inputs: `docs/v2-stocks/runs/TBA2/{build-brief,build-report,review-brief,review-report}.md`, `runs/TBA2b/{build-brief,build-report}.md`,
spec `docs/v2-stocks/spec.md` Q17, Q104-Q110, Q113-Q115.

## Already checked by the PM
`runs/pm-gate-tba2b/gate-summary.txt` GREEN (lint, 1348 unit tests, assembleDebug). Mutations (`runs/pm-gate-tba2b/pm-check.log`): day chip
pick by first-in-list -> BriefDayChipsTest x2 fail; a burst posts every alert -> AlertsTest "a burst for one code is one heads-up" fails.
Earlier (TBA2): first read notifies history, stale alerts notified -> killed.

## Attack list
1. Every first-review finding (MAJOR-1, MINOR-2..6, NITs) is really closed in the code, not just tested in a helper the screen ignores
   (follow the composable to the pure function).
2. Builder admits: the new tests were not seen RED (they compile only against the new APIs); the unpair double clear in `AppContainer` has
   no test; the brief PAGE's pending line is not cut at 09:00 (only the card is). Judge each: is a real regression possible, and what is the
   cheapest guard (e.g. a pure function the page also uses)?
3. MINOR-5: `setOnlyAlertOnce` on an update of an existing tag; does a later alert for the same code still make a heads-up when it should
   (new answer, minutes later)? Is "already in the tray" read correctly (`activeNotifications`)? Lock-screen public text still code/digit-free.
4. File moves (`StockNotices.kt`, `StockOpen.kt`, `AlertsScreen.kt`, `StockAlertsViewModel.kt`): pure moves, no behaviour change, no
   duplicates left behind.
5. `StocksBackStack.open(StockOpen)` and the repeat-tap rule: Back still unwinds to Home with no loop; deep link from a notification while
   a different stock page is open.
6. Q115 states end to end (card and page): trading day before the slot, after 09:00 with no brief, weekend/holiday, job off, `/jobs` unknown.
7. Code shape per `PROJECT.md`; strings EN+ID; tests that guard nothing.

## Rules
Read-only: write ONLY `docs/v2-stocks/runs/TBA2b/review-report.md`. If you run Gradle, do it on a copy in your scratch dir. No adb, no
server. No git checkout/restore/stash/reset/commit. Avoid `bash -c`/heredocs: script files + plain `bash`.
Report: Verified (file:line), Findings (BLOCKER/MAJOR/MINOR/NIT + fix), `VERDICT: ...`. Under ~35 lines.
