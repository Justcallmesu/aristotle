# TBA2 + TBA2b + TBA2c — final re-review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`. Review the WHOLE uncommitted phone side of slice 3
(`git status --short -- android`). This is after fix round 2 of 2: a CHANGES-REQUIRED now goes to the user, so separate real
BLOCKER/MAJOR (a user-visible wrong behaviour, data or privacy leak, lost/silent alert) from things that can be deferred.

Inputs: `docs/v2-stocks/runs/TBA2/review-report.md`, `runs/TBA2b/review-report.md` (the findings TBA2c fixes), `runs/TBA2c/{build-brief,
build-report}.md`, spec `docs/v2-stocks/spec.md` Q17, Q104-Q110, Q113-Q115; contract `docs/protocol.md` §20.4 (committed, `8809c74`).

## Already checked by the PM
`runs/pm-gate-tba2c` GREEN (lint, 1358 unit tests, assembleDebug). Mutations (`runs/pm-gate-tba2c/pm-check.log`): quiet whenever a tray copy
exists -> AlertsTest MAJOR-2 test; stale-generation answer written -> AlertsTest + AlertWatcherTest; opening the page keeps the old pick ->
BriefPickTest. All changed files are under `android/**` (checked against `runs/TBA2c/pre.txt`).

## Attack list
1. TBA2b review MAJOR-1/2 and both MINORs are closed in the code the screens and `Notifier` actually run (follow the wiring, not only the
   pure functions). In particular: `postTime` lookup per tag via `activeNotifications`; the latest chip when `pending`; resume returns to
   latest; `MinuteClock` lifecycle (stops when not visible, no leak).
2. Notifications end to end once more: one heads-up per code per answer, a later alert rings, lock-screen public text code/digit-free.
3. Generation counter: every write path in `StockAlertStore` (take, list, mark seen) respects it, or explain why a path need not.
4. Q115 states identical on card and page, with the job's own time in every string; `stocks_brief_page_title` / `stocks_brief_none` still
   hard-code 08:30 (builder left them) — is that user-visible wrong when the job time is changed?
5. No regression in what the two earlier reviews verified (Q109 policy, tap alias, back stack, stop/target validation).

## Rules
Read-only: write ONLY `docs/v2-stocks/runs/TBA2c/review-report.md`. Gradle only on a copy in your scratch dir. No adb, no server. No git
checkout/restore/stash/reset/commit. Avoid `bash -c`/heredocs: script files + plain `bash`.
Report: Verified (file:line), Findings (BLOCKER/MAJOR/MINOR/NIT + fix), `VERDICT: ...`. Under ~35 lines.
