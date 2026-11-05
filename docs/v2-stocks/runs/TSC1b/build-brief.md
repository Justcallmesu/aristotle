# TSC1b — fix round for TSC1 (review MAJOR + minors)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/runs/TSC1/review-report.md` (file:line), `runs/TSC0/design.md`
(bars freshness rule), then `server/internal/stocks/{scheduler,bars}.go` (commit d2e1255).
## Do
1. MAJOR: no data race when `PUT /v1/stocks/jobs/{id}` runs during a job: `update` changes only the editable fields (on/at/every) under the lock and
   never replaces the struct; the run goroutine gets its ID and Run func as locals. Add a `-race` test: a slow job body + PUT during the run.
2. Persist the last interval slot (so a restart never re-runs it) in `stocks-jobs.json`.
3. Unusable `stocks-jobs.json`: keep the handled date in memory for the process lifetime so a catch-up runs at most once per process; log it.
4. Bars catch-up follows the design: run only if the store is older than the last completed session; a catch-up that completed today suppresses the
   16:20 run of the same day.
5. The bars job time is bounded to 16:15-23:00 (PUT 400 otherwise); protocol §20.2 updated.
6. Cap the bars set at 180 codes (universe first, then watchlist, then holdings) and log when trimmed; document.
## Hard rules
Only `server/**`, `docs/protocol.md` §20.2, the report. Another worker edits `android/**`. Fakes only; never call Yahoo/Hermes; no port 8760; no real state dir.
No commits; never git checkout/restore/stash/reset. Gate after your LAST edit: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TSC1b`
exits 0 + `go test -race ./internal/stocks/...`. Report ≤ 10 lines.
