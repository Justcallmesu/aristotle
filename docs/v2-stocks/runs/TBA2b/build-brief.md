# TBA2b — phone fix round for TBA2 (review CHANGES-REQUIRED)

Repo `/home/justcallmesu/project/personal/Aristotle`. Fix round 1 of 2. Read `PROJECT.md`, then your input
`docs/v2-stocks/runs/TBA2/review-report.md` fully (each finding has file:line and a proposed fix; verify against the real code before
following it), `runs/TBA2/build-brief.md`, `build-report.md`, spec `docs/v2-stocks/spec.md` Q109, Q113-Q115, contract `docs/protocol.md`
§20.4. TBA2 is NOT committed: everything uncommitted under `android/**` is TBA2's work and your base.

## Do
1. **MAJOR-1 brief day chips** (`BriefScreen.kt` ~:202-216): select and pick by DATE against `latest.date`, show the chips whenever a kept
   brief other than the latest exists. Move the decision into `BriefUiState` (pure) and test it: latest = made-up `pending` today with
   list = [yesterday] (yesterday's chip opens yesterday, not pending); single kept brief equal to latest (no chips); normal 7-day list.
2. **MINOR-2 (Q115) brief card**, phone side only (the server contract does not change): (a) while `/jobs` has not answered and the
   newest brief is older than today, the card is `Unknown`, never "not a trading day"; (b) `pending` at or after 09:00 WIB reads
   "No brief today" (the job was turned on after its slot). Pass the clock in; tests for both and for the existing states
   (pending before 08:30 -> "Comes at <job time>", weekend/holiday -> not a trading day, job off -> brief is off, missed).
3. **MINOR-3 unpair race:** clear the alert store again after the credentials are wiped (as `clearAlerts()` does), so a late long-poll
   answer cannot write the old PC's cursor/feed back or post a notification. Also clear a pending `stockOpen` on unpair (NIT). Test.
4. **MINOR-4 Q109 wiring:** make the target pure (`AlertPolicy.target(credentials, link, visible, keep): <target>?` or similar) and use it
   in `AppContainer`; tests for keep on/off, Stop from the notification, unpaired, PC unreachable, app visible.
5. **MINOR-5 burst:** one heads-up per code per answer: post only the newest alert per tag (its lines already list the others), and
   `setOnlyAlertOnce(true)` on updates of an existing tag. Test the "what to post" decision as a pure function.
6. **MINOR-6:** the page's pending line uses the job's `at`, not the constant "08:30".
7. **NITs:** focus rows validate the code with `StockCodes.valid` before opening; extract the `StockOpen` -> stack mapping into one
   function (`StocksBackStack.open(StockOpen)` or similar) used by both call sites; a repeat tap does not push the same Brief/Alerts page
   twice; fix `AlertWatcherTest.kt` ~:128-129 so it asserts something real; move `StockAlertsViewModel`/`AlertsScreen` and
   `StockNotices`/`StockOpen` into their own files (one responsibility per file; pure moves, no behaviour change).

## Non-goals
No server change, no `docs/protocol.md` change (another reviewer is reading the server now). No new libraries. No new strings unless
item 2(b) needs one ("No brief today"): append EN + ID only.

## Hard rules
- Files you may edit/create: `android/**`, `docs/v2-stocks/runs/TBA2b/build-report.md`. Not yours: `server/**`, all other docs,
  `CHANGELOG.md`.
- No commits; never `git checkout/restore/stash/reset` (keep copies in your scratch dir to undo).
- Tests seen RED first: copy the pre-fix files to your scratch dir and run the new tests against that copy; record which went RED.
- A Gradle `--tests` filter matches CLASS names: `grep -n '^class' <file>` first.
- No adb, never connect to a server. No `android.builder.sdkDownload` changes; no installs.
- Avoid `bash -c`, heredocs and chained `$(...)` (they need approval nobody can give): script files run with plain `bash <file>`.
- Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TBA2b` exits 0.

## Stop and report if
A fix needs a server/contract change; MINOR-5's grouping would change what the lock screen shows (it must stay digit/code-free).

## Report
`docs/v2-stocks/runs/TBA2b/build-report.md`, ≤ 15 lines: per item what changed (file:line), tests added and which were RED first, gate
summary line + test count, files touched, anything not done.
