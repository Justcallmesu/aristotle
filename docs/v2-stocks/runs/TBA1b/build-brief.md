# TBA1b — server fix round for TBA1 (review CHANGES-REQUIRED)

Repo `/home/justcallmesu/project/personal/Aristotle`. Fix round 1 of 2. Your input is `docs/v2-stocks/runs/TBA1/review-report.md`
(read it fully), plus `runs/TBA1/build-brief.md` and `build-report.md`. Spec: `docs/v2-stocks/spec.md` Q18, Q87, Q107-Q110. Contract:
`docs/protocol.md` §20.4. TBA1 is NOT committed: `server/internal/stocks/{alerts,brief,levels}.go` and `tba1_test.go` are untracked, and
other server files are modified. All of that is TBA1's work and is your base. Read the real code before editing it.

## Do
1. **MAJOR-1 (required).** Add `TestBriefNeverOnWeekendOrHoliday` with the reviewer's shape: `newBriefHarness` at Fri 2026-11-06 17:00.
   (a) `briefJob().next_run` == `2026-11-09T08:30:00+07:00`. (b) `tickAt` Sat 11-07, Sun 11-08 and holiday 2026-12-25, each at 08:30:05:
   fake Hermes opened 0 sessions, `GET /brief?date=2026-11-07` -> 404 `no_brief`, no `brief` alert, job `last_due` not on those days.
   (c) Every wait is bounded (`waitFor` with a deadline that FAILS the test), never a bare `sched.wait()`; under the mutant the test must
   fail cleanly, not hang. Add the same weekend check for the `news` and `alerts` jobs (no fetch / no alert on Sat). The PM will mutate
   `TradingDays: true` -> `false` at `stocks.go:159` (the brief job registration); your test must fail on that mutant.
2. **MINOR-2 `priceLike` (brief.go ~:769).** A bare 1990-2099 counts as a year only next to a year word (`tahun`, `year`, `FY`), a month
   name (EN + ID) or `Q1`-`Q4`; else it is price-like. A number followed by `%` or `persen` is never price-like (so "volume 150%" passes).
   Indicator names like `MA200`, `EMA50`, `RSI14` pass. `<n> ribu|rb|k` (incl. "4,9 ribu") is price-like. Table test with the reviewer's
   probes plus these cases.
3. **MINOR-3.** A hit first logged with origin `rerank` must be upgraded when a later `scan`/`eod` fires the same (date, code, setup, dir):
   it then alerts and counts toward Q107 exactly once. Test: settings PUT mid-day -> rerank logs it -> next scan -> one setup alert.
4. **MINOR-4.** When the alerts state cannot be saved (`persist=false`, alerts.go ~:118), make NO automatic analyzer runs (the Q87 cap
   must survive restarts; money is spent per run). Log the reason once (no codes or text). Test it. The clock-jump nit: leave it.
5. **MINOR-5.** The brief retries `hermes_lost` only when the loss happened BEFORE `prompt.submit` (Check / Open); a loss after submit
   has spent an Opus turn and is final (state failed, code kept). Same rule for `busy`/`hermes_unavailable` if they can arise after submit.
   Find where the submit happens and carry a "submitted" fact out of `make`; do not guess from the error code. Test both sides.
6. **Nits.** (2) After waking from `b.after`, re-check the 09:00 cutoff before another try (a suspend can wake it late); test with the
   fake clock. (3) Fix `docs/protocol.md` §20.4: the real check order (`not_trading_day` before Hermes) and the retry rule from item 5.
   (4) Cite `canonical_sessions.py:28` (not :29) everywhere it appears (`grep -rn 'canonical_sessions.py:29'` over server/ and docs/).
   (1) If the Hermes session snapshot read BEFORE `prompt.submit` shows a model other than the pinned Sonnet for an auto run, abort that
   run (delete the session, do not submit, log `model_mismatch` without text). If the snapshot is only read after submit, do NOT
   restructure: say so in the report.
   Leave nits (5) and (6) alone.

## Non-goals
No contract changes beyond the doc fixes above (TBA2, the phone, is built against §20.4 and is being gated now). No android changes.

## Hard rules
- Files you may edit: `server/**`, `docs/protocol.md`, `docs/v2-stocks/runs/TBA1b/build-report.md`. Not yours: `android/**`,
  `CHANGELOG.md`, every other doc. A PM Gradle gate runs on `android/**` in parallel: ignore it.
- No new Go modules. Fakes only (fake Hermes, fake Yahoo, fake clock); never connect to the real Hermes or port 8760; no real state dir.
- No commits; never `git checkout/restore/stash/reset` (not even to undo your own edit: keep a copy in your scratch dir).
- Tests seen RED first: copy the pre-fix files into your scratch dir, run each new test against that copy, record which went RED.
- Gate after your LAST edit: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TBA1b` exits 0, and
  `cd server && go test -race -count=3 ./internal/stocks/... ./internal/hermes/...` is ok.
- Logs never contain prompt, verdict, brief text or holdings values.

## Stop and report if
An item needs a §20.4 contract change; the "submitted" fact cannot be known without restructuring the Hermes client; a fix changes
Q107's meaning (≥ 2 distinct bullish scan/eod setups).

## Report
`docs/v2-stocks/runs/TBA1b/build-report.md`, ≤ 15 lines: per item what changed (file:line), tests added and which were RED first,
gate summary line + go-tests count, files touched, anything not done.
