# TR1 — server: verdict history, scorer job, track-record endpoint

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `71a90bf`+ (clean tree except `docs/`). Read `PROJECT.md`, then fully:
`docs/track-record/spec.md` (**Q116-Q120, Q120a (PM reading, build it as written), Q120b, P10-P14**), `docs/track-record/runs/TR0/report.md`
(probe results, the ordering rule, the look-ahead finding), `docs/protocol.md` §20 intro + §20.1 (analysis) + §20.2 (jobs). Code you build on:
`server/internal/stocks/analyzer.go` (`AnalysisResult` :119-147, `keep` :713), `verdict.go` (verdict values :37-40, level rules :489-517),
`bars.go` (bar store), `provider.go` (`Provider` :15-23), `yahoo.go`, `scheduler.go` + the registrations in `stocks.go:148-160`, `idxcal.go`
(`IsTradingDay`), `http.go` (routes, `requireDevice`). Pattern files: `bars.go` for a versioned JSON state file (`statefile.WriteAtomic`, a
newer-version file left untouched, an unusable file logged and kept in memory); `brief.go`'s registration for a daily job.

## Do
1. **History (P10).** New `stocks-verdicts.json` (version 1): one compact record per kept verdict, appended in `keep` (id, code, held,
   analyzed_at, session_date, market_state, model, trigger, verdict, confidence, entry_low/high, target, stop, price, flags; NO reasons,
   summary, news, indicators). Seed once, at start, from the results in `stocks-analyses.json` when the history file does not exist yet
   (dedupe by code+analyzed_at). Never rewrite past records except to attach their score.
2. **Provider (TR0 §1).** Add one method for a past window of intraday bars (`period1`/`period2`, interval 5m) to `Provider` and the
   Yahoo client, through the same client/headers as `Chart`; fakes in tests. Daily bars come from the bar store, else this method with
   interval 1d (A2).
3. **Scorer (Q117-Q120a, P12, P13).** A pure function `score(record, intradayBars, dailyBars) -> Score` (no I/O), table-tested:
   - Window = the record's `session_date`; only bars that START after `analyzed_at` count (P12).
   - buy_today/add: entry zone reached? no -> `not_triggered` (Q118). Yes -> first of target/stop after the fill in 5-minute order: `win` /
     `loss`; a bar touching both -> `loss`; a target in the fill bar does not count; neither by the session close -> `closed`, result % =
     close vs entry_high. Result % for win/loss from entry_high to target/stop.
   - hold: stop hit first -> `loss`; target (when given) reached first -> `win`; neither -> `right` if the stop held (Q120a), result % = close
     vs price.
   - avoid/sell: `right` when the session close ≤ price at the call, else `wrong`; result % = close vs price.
   - wait: no right/wrong; `would_have` % = session close vs (entry_high if given, else price) (Q120a).
   - Every record also gets `info_3d` %: the close 3 trading days after `session_date` vs the same base (Q117, info only; filled when
     those bars exist, may be filled by a later run).
   - No 5-minute bars available (older than ~60 days, Yahoo error, empty) -> score from the daily bar with stop first, `source:"daily"`.
     No daily bar for the session (no trade, unknown code) -> `no_session`, never a loss.
   - Score carries `rule: 1`, `source: "5m"|"daily"`, `scored_at`.
4. **Job (P11).** `track.score` at 17:00 WIB, `TradingDays: true`, catch-up always; scores every unscored record whose session is closed,
   then fills missing `info_3d`. At most one intraday request per code per run (one window covering its unscored sessions), paced like the
   bars job; a Yahoo block stops the run (the rest waits for the next). Listed in `GET /v1/stocks/jobs` (editable `on` like `news`).
5. **Endpoint.** `GET /v1/stocks/track-record?code=` (device auth): `{"summary":[...], "calls":[...], "scored_through": date, "pending": n}`.
   `summary` = raw COUNTS grouped by verdict × model × trigger × confidence (win, loss, closed, not_triggered, right, wrong, no_session,
   sum/avg of result %, sum/avg of info_3d %, n). **No rates, no minimum-count rule**: the phone computes those (open user picks). `calls` =
   newest first, max 50, filtered by `code` when given. Document it as `docs/protocol.md` §20.5 (shape, job, rules incl. P12 and the
   stop-first fallback).
6. Logs: codes, counts, states, error codes only.

## Acceptance tests (fakes only; tests seen RED first on a scratch copy where the API allows)
Scorer table: every verdict type × each outcome above, incl. both-in-one-bar -> loss, target in the fill bar ignored, look-ahead (a 14:00
call ignores a 10:00 touch), not_triggered, daily fallback stop-first, no_session, Friday short session, a call made after 16:00 scored on the
next session, info_3d across a weekend + a holiday. History: append on keep, seed once (second start does not duplicate), newer-version file
untouched, unusable file -> in memory + logged. Job: weekend/holiday no run; catch-up scores two missed sessions in one run with one request
per code; Yahoo block stops cleanly. Endpoint: auth required, counts match the records, `code` filter, 50 cap.

## Hard rules
Files: `server/**`, `docs/protocol.md`, `docs/track-record/runs/TR1/build-report.md`. Not yours: `android/**`, `CHANGELOG.md` (put a "Doc
text" Server line in the report), other docs. No new Go modules. Fakes only: never the real Yahoo, Hermes, port 8760 or the real state
dir. No commits; never `git checkout/restore/stash/reset`. Avoid `bash -c`/heredocs/chained `$(...)`: script files + plain `bash <file>`.
Gate after your LAST edit: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/track-record/runs/TR1` exits 0 and
`cd server && go test -race -count=3 ./internal/stocks/...` ok.

## Stop and report if
A rule in the spec cannot be scored from the data (name the case); the analyzer's `keep` has a path that bypasses it; the scheduler cannot
express the job.

## Report
`docs/track-record/runs/TR1/build-report.md`, ≤ 20 lines: per item what changed (file:line), tests and which were RED first, gate line +
go-tests count, files, decisions you took, not done.
