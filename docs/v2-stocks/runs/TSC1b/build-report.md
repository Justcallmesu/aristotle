# TSC1b build report
- MAJOR fixed: `update` now validates, then changes only on/at/every (+rec) in place and rolls those back on a save error; the job struct is never replaced. `launchLocked` copies ID and Run into locals under the lock (`safeRun` takes the func). `-race` test `TestSchedulerPutDuringRunIsRaceFree` (job blocked, 20 PUTs mid-run).
- Interval slot persisted as `last_slot` in `stocks-jobs.json` (loaded at register); test restarts within the grace.
- Unusable jobs file: handled date stays in `rec` for the process (catch-up once per process), now logged ("kept in memory only"); test.
- Bars catch-up: new `JobSpec.Wanted` hook; `barsWanted` runs a late slot only if a quoted code is not `barsFresh`, and skips the on-time run when a catch-up completed `ok` today (slot recorded handled, logged). Tests for both.
- Bars `at` bounded to 16:15-23:00 (`JobSpec.MinAt`; PUT 400, stored value below it ignored); bars set capped at 180 (universe, watchlist, holdings; Warn on trim). Existing bars tests re-ordered for the new order (universe first). `docs/protocol.md` §20.2 updated.
- Also fixed a flake in `TestSchedulerLoopReadsWallClock` (tick sent while the boot run was still running).
- Gate: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TSC1b` and `go test -race ./internal/stocks/...` — see final run below.
