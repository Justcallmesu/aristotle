# TR1b — server fix round 1 of 2 for TR1 (review CHANGES REQUESTED)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. TR1 is uncommitted and is your base. Read `PROJECT.md`, then your input
`docs/track-record/runs/TR1/review-report.md` fully (file:line + probes), `runs/TR1/{build-brief,build-report}.md`, and the spec
`docs/track-record/spec.md` rows **Q125, P15a, P16** (new) plus P12, P13. Read the real code before editing.

## Do
1. **MAJOR 1 / P15a.** Daily fallback only on HTTP 422 or a 200 with no bars; every other failure (403, 401, 400, 5xx, network, bad body)
   leaves the record unscored for the next run (the run reports the failure, not `ok`). On a 422 for a multi-session window, retry the
   window once without the sessions older than ~50 days before falling back for those old ones only. Update §20.5.
2. **MAJOR 2 / P15a.** Daily bar: a target in the fill day never counts (fill + target, no stop -> `closed`).
3. **MAJOR 3.** Validate records on load (levels the verdict needs per `verdict.go:489-517`, RFC3339 `analyzed_at`, a valid
   `session_date`), for every file version you read; an unscorable record is skipped by the planner and logged once (code only); the job
   never panics on one bad record (add a recover-free design: no nil dereference path). A bad `analyzed_at` must never fall open to "all
   bars".
4. **Q125.** Fill = the first bar after the call whose LOW ≤ `entry_high` (limit buy at entry_high); a gap below the zone fills, then the stop
   is checked in that bar. Update the scorer table (gap down below the zone and below the stop -> loss; gap down then recovery -> per levels).
5. **P16.** 10 trading days unscored -> `unscorable` (own count in `summary`, excluded from rates; §20.5); `no_session`/`unscorable` never
   request `info_3d`; split check for `info_3d` (refetched session close vs stored `score.close` > 1 % -> leave `info_3d` empty, logged).
6. **Boot catch-up order.** `track.score` must not score from a stale bar store while `bars.daily` catches up in the same tick: wait for a
   fresh store (or run on a later tick), proven by a test.
7. **Tests that guarded nothing** (review list): `apply` must not overwrite a score (P13, direct test), stale bar store not used, a bar
   starting exactly at `analyzed_at` (decide: excluded, as "start after"), keep dedupe, the unscorable skip, the info cap.
8. **Shape.** Extract one versioned-JSON-file decoder used by both `verdicts.go` and `analyzer.go` (no behaviour change for analyses; its
   tests stay green). NIT: an unusable `stocks-analyses.json` at first start must not create an empty history (seed waits).

## Hard rules
Files: `server/**`, `docs/protocol.md`, `docs/track-record/runs/TR1b/build-report.md`. Not yours: `android/**`, `CHANGELOG.md`, other docs.
No new Go modules. Fakes only: never the real Yahoo, Hermes, port 8760 or state dir. No commits; never `git checkout/restore/stash/reset`.
RED first on a scratch copy of the pre-fix files for items 1-5 and 7; report which. Avoid `bash -c`/heredocs/chained `$(...)`: script files +
plain `bash <file>`. Gate after your LAST edit: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/track-record/runs/TR1b` exits 0 and
`cd server && go test -race -count=3 ./internal/stocks/...` ok.

## Report
`docs/track-record/runs/TR1b/build-report.md`, ≤ 15 lines: per item file:line, tests + RED-first, gate line + count, files, not done.
