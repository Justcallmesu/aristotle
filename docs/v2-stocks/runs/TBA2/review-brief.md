# TBA2 — review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md` first. Review TBA2, the phone side of slice 3, which is
uncommitted in `android/**` (modified + untracked files; list them with `git status --short -- android`). Inputs:
`docs/v2-stocks/runs/TBA2/build-brief.md`, `build-report.md`; spec `docs/v2-stocks/spec.md` Q17, Q18, Q87, Q101, Q104-Q110,
**Q113-Q115**; contract `docs/protocol.md` §20 holdings + **§20.4**; `docs/design.md`, `docs/design/v2/stocks-notes.md`.
Server code is READ ONLY context and is being changed right now by another worker (TBA1b: tests, `priceLike`, retry rules; no
contract change). Judge the phone against §20.4 as committed in the tree plus TBA1's server code; ignore half-written server edits.

## Already checked by the PM (hunt elsewhere)
- PM gate `GATE_ONLY=android` GREEN: lint, 1338 unit tests, assembleDebug (`runs/pm-gate-tba2/gate-summary.txt`). The builder's
  stray "FAIL android (1)" run did not reproduce.
- Mutations (`runs/pm-gate-tba2/mutation.log`): first read notifies history -> killed by AlertsTest x2 + AlertWatcherTest x1;
  stale > 30 min alerts notified -> killed by AlertsTest "only new, loud and fresh alerts are notified".

## Attack list
1. **Q109 enforcement:** `AlertPolicy.polling` is used at `AppContainer.kt:289`. Follow the real inputs (`visible`, `keep`): is
   "app visible" really process-foreground, does "keep connected" stopped from the notification count as off, does the watcher stop
   (no lingering long-poll, no leaked coroutine) on unpair, background, PC switch? Does any other path poll `/alerts` outside it?
2. **Lock-screen privacy:** VISIBILITY_PRIVATE + public version with no code and no digit for every alert kind (level, setup, news,
   analysis, brief, summary). Grouping per code must not leak the code in the group summary.
3. **Notification tap / `OpenStock` alias:** non-exported? Can another app send an intent that opens a stock page with crafted extras
   or reaches anything beyond the stocks back stack? Back from the opened page behaves (no loop, cf. TM3a2).
4. **Long-poll lifecycle:** 70 s read timeout only on that call; backoff 1-30 s; early empty answer waits 5 s; cancel on target change;
   cursor per PC persisted; `reset` handling; no duplicate notifications across reconnect or process restart.
5. **Q115 (PM reading — confirm):** what the brief card shows when the newest stored brief is from an earlier day. Check every case:
   trading day before 08:30, trading day after 09:00 with no brief (missed), weekend/holiday, brief job off. State whether "not a
   trading day" appears on a trading day before the job, and give the exact fix if so.
6. **Holding stop/target (Q104, Q113):** validation matches the server's `checkLevels` (tick grid of the price's own band, stop < avg,
   stop < target, target NOT checked against avg); an edit always resends both (a PUT without them clears them).
7. **Brief page:** model text only plain `Text` (no markdown/link rendering); cited values only from `refs`; disclaimer Q17; Run again
   only for today; the 5 s re-read stops when the brief is no longer running or the page is gone.
8. **Settings › Stocks › Alerts:** alerts job switch PUT, "N of 5" auto runs, the Q109 line; MINOR-6 (TalkBack live region only on the
   words, not the countdown).
9. Strings: every new string has EN + ID (`values-in/`), no hardcoded UI text; code shape per `PROJECT.md` (state in ViewModels,
   transport in `data/`, pure reducers tested, no duplication).
10. Tests that guard nothing (assert on their own fakes), tests that recompute the expectation with the code's own formula.

## Rules
Read-only review: change no file except your report. No adb, no server connection. No git checkout/restore/stash/reset. You may copy
files to your scratch dir for probes. Avoid `bash -c`/heredocs; script files + plain `bash`.

## Report
`docs/v2-stocks/runs/TBA2/review-report.md`: Verified (with file:line), Findings (BLOCKER / MAJOR / MINOR / NIT, each with file:line,
evidence, the fix), then `VERDICT: APPROVED | PASS-WITH-FINDINGS | CHANGES-REQUIRED`. Keep it under ~40 lines.
