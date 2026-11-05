# TSC1 review — commit d2e1255 (scheduler, bar store, universe override, jobs API)

Scope: `git show d2e1255` only, extracted to scratch (`git archive`). Re-ran `go vet` and `go test -race ./internal/stocks/... ./internal/app/...`: ok. Also ran `internal/stocks` under TZ=America/New_York and Pacific/Kiritimati: ok. No new modules (go.mod untouched). Probes were throwaway scratch tests.
**MAJOR**
1. Data race: `PUT /v1/stocks/jobs/{id}` while that job runs. `update()` writes the whole struct (`*j = n`, scheduler.go:612) under `mu`, but the run goroutine reads `j.spec.ID` without the lock (scheduler.go:382/385; `safeRun` reads `j.spec.Run` the same way). Reproduced with `-race`: a body that sleeps 200 ms, then PUT `{"on":true}` gives "DATA RACE scheduler.go:382 vs :612". A user can hit this with a normal toggle during the daily bars run (2-8 min). It is benign today because the same values are rewritten, but it breaks the race-clean gate. Fix: before `wg.Go`, copy `id := j.spec.ID` and `fn := j.spec.Run` into locals, or have `update` assign only `on/at/every/rec` instead of the whole struct. Add a `-race` test for PUT during a run.
**MINOR** (no round)
2. Interval `lastSlot` is kept only in memory. A restart within 2 min of a slot that already ran runs it again (probe: 2 runs for one slot). No interval job exists yet; TSC2 `scan` should persist it, or accept this and say so.
3. If `stocks-jobs.json` is unusable, `handled` is never saved. Then every restart re-runs the `bars.daily` catch-up: ~90-300 Yahoo calls each time.
4. Calls per day can double. Boot on a trading day before 16:20, after missing the previous slot: a catch-up run happens now, then the 16:20 run. The design rule was "catch up only when the store is older than the last completed session"; the code uses the `handled` date. Also, a run stopped by shutdown or a block is not resumed until the next day (as the brief says). The analyzer falls back to Yahoo for codes that are not fresh, so the data stays correct.
5. `at` may be set inside a session (e.g. 10:00). Then the store never counts as fresh the next day, so the analyzer always uses Yahoo. A 16:15 floor for `bars.daily` would be clearer.
6. The quoted set can reach 300 codes (universe ≤ 200 per the build brief). The design budget was ≤ 180 calls. Note it in design/P8.
**Verified OK**
- Scheduler: fixed-offset WIB (no host TZ, no DST); the wall-clock 30 s tick catches suspend jumps; holidays, weekends and Fri→Mon via `IsTradingDay`.
- Only the latest slot is caught up; Q31 16:00 cutoff (probe: 15:59:59 runs late, 16:00 is `missed`); `handled` is saved at launch, so no repeat after a restart.
- No overlap; edits to on/off and time behave; panic is recovered; shutdown cancels and waits up to 5 s; no launch after close.
- Bar store: atomic 0600 write; caps applied on put and on load; a corrupt file starts empty and is logged.
- Bar store: concurrent readers are safe (map under lock, slices never changed in place, dailyBars clones).
- Bar store: `SessionDate` rolls at 16:00, so the 16:20 run keeps that day's bar and a run during a session drops the partial bar; the freshness rule is correct (Mon 16:05 → Yahoo).
- Bars job: 1.5 s pacing; a 429/5xx/backoff stops the run; the partial store is saved; prune happens only after a full run with a usable `stocks.json`; codes never fetched or fetched longest ago go first next time.
- Universe: `^[A-Z]{4}$`, 1-200 distinct codes, body ≤ 16 KiB, strict file parse, bundled fallback with `file_error`.
- Routes sit behind the device token; jobs PUT bounds are 05:00-23:00 and 5..30, fields limited to `editable`.
- Protocol §20.2 and the CHANGELOG match the code.
VERDICT: CHANGES REQUESTED — 1 MAJOR (#1 data race on PUT during a run; one-line fix + -race test); minors optional.
