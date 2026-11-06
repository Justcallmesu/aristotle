# TBA2c — phone fix round 2 of 2 for TBA2 (re-review CHANGES-REQUIRED)

Repo `/home/justcallmesu/project/personal/Aristotle`. This is the LAST fix round allowed before the PM stops and asks the user, so be
thorough. Read `PROJECT.md`, then your input `docs/v2-stocks/runs/TBA2b/review-report.md` fully, plus `runs/TBA2b/build-brief.md` and
`build-report.md`. TBA2 + TBA2b are uncommitted under `android/**` and are your base. The reviewer's probe test is in the PM scratch dir
`~/.hermes/profiles/pm/cache/scratch/rev-tba2b/` (read it; do not copy it blindly). Server is committed (`8809c74`); contract
`docs/protocol.md` §20.4.

## Do
1. **MAJOR-1 picked brief day never resets.** The pick is cleared every time the brief page is opened (from Home's card, from the
   "Today's brief is ready" notification, from Alerts) and when a new latest brief arrives for a later date. The chip row always offers the
   latest (today's, even when it is `pending`) next to the kept days, so the user can return to it. Pure state + tests: open-after-pick shows
   latest; pending today + list [yesterday] -> chips [today(latest), yesterday], both reachable; the reviewer's re-entry probe passes.
2. **MAJOR-2 later alerts go silent.** `setOnlyAlertOnce` only when the same tag's tray notification was posted less than **60 s** ago
   (PM value; from `StatusBarNotification.postTime`), else a normal heads-up. Put the decision in a pure function
   (`shouldStayQuiet(lastPostedAtMillis: Long?, nowMillis: Long): Boolean` or similar) with tests: no tray copy -> alert; 10 s -> quiet;
   5 h-old unread setup alert then a stop hit -> alert. The burst rule (one notice per tag per answer) stays.
3. **MINOR brief page vs card after 09:00.** One pure status function used by BOTH the Home card and the page's status line (pending before
   the slot, "No brief today" at/after 09:00 WIB, missed, not a trading day, job off, unknown while `/jobs` has not answered). Use the
   job's own `at` everywhere: `stocks_brief_late` / `missed` strings must not hard-code 08:30 (format with the job time; EN + ID).
   The 09:00 cut must also apply on an idle screen: re-evaluate on a ticking clock (e.g. a minute ticker while the screen is visible), not
   only on recomposition.
4. **MINOR unpair race.** Add a generation (epoch) to `StockAlertStore`: `clear()` bumps it; the watcher captures it before each poll and
   `take(...)` drops an answer whose generation is stale (nothing written, nothing returned to notify). Test it in the store (stale answer
   after clear -> no cursor, no feed, no notice) so it FAILS on the current code.
5. **NITs:** remove the duplicated "Stop from the notification" assertion (or make it test something different); drop the redundant
   `open(Stock)` same-code branch and its test if it cannot fail; fix the KDoc backslashes at `BriefViewModel.kt` ~:56; remove the double
   blank line at `BriefScreen.kt` ~:70-71.

## Non-goals
No server or contract change. No new libraries. Lock-screen public text stays code/digit-free.

## Hard rules
- Files you may edit/create: `android/**`, `docs/v2-stocks/runs/TBA2c/build-report.md`. Not yours: `server/**`, all docs, `CHANGELOG.md`.
- No commits; never `git checkout/restore/stash/reset` (copies in your scratch dir to undo).
- RED first: copy the current (pre-fix) files to your scratch dir; each new test must FAIL against that copy where the API allows (items 1,
  2, 4 at least — write the test against the existing API first, then change it). Report which went RED.
- Gradle `--tests` filters match CLASS names (`grep -n '^class'` first). No adb, no server, no installs.
- Avoid `bash -c`, heredocs, chained `$(...)`: script files + plain `bash <file>`.
- Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TBA2c` exits 0.

## Stop and report if
A fix needs a server change; Android's API cannot give the tray notification's post time for item 2 (then report the alternative).

## Report
`docs/v2-stocks/runs/TBA2c/build-report.md`, ≤ 15 lines: per item what changed (file:line), tests and which were RED first, gate line +
test count, files touched, anything not done. Include a "Doc text" CHANGELOG Phone line for the whole slice-3 phone work (TBA2's line in
`runs/TBA2/build-report.md` updated if anything user-visible changed).
