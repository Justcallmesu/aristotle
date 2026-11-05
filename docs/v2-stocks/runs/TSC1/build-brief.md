# TSC1 — server: scheduler, bar store, universe override, jobs API (slice 2 foundation)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read fully: `docs/v2-stocks/runs/TSC0/design.md` (**your design**: scheduler, bars, universe,
jobs sections), `docs/v2-stocks/spec.md` (Q20, Q31, Q85, **P8**), `docs/protocol.md` §20/§20.1, `server/internal/stocks/**` (provider, yahoo, calendar,
state via statefile, analyzer sweep), `server/internal/app/serve.go`.
## Do
1. Scheduler (`internal/stocks/scheduler.go`): wall-clock check every 30 s (laptop suspend safe), WIB, trading-day aware (calendar); job list with
   id, enabled, time or interval; persisted settings in the stocks state (only on/off + times + scan interval editable, Q20); catch-up at boot per
   Q31 (run late if still before 16:00 else mark missed) with last-run/last-error per job; no job body logic beyond `bars.daily` here (TSC2/TBA add
   theirs through a registration API). Pure tests with a fake clock incl. suspend jumps, holidays, Friday.
2. Bar store (P8): `stocks-bars.json` (daily bars ≤ 1y per code for the universe ∪ watchlist ∪ holdings), loaded at start, rewritten atomically once
   after the 16:20 `bars.daily` job; the job fetches with the existing provider (1y range, polite pacing, stop the run on a block, resume next day);
   size cap and pruning of codes no longer in the set; corrupt file -> start empty + log. Indicators/analyzer may read from it (keep their current
   path working; prefer the store when fresh).
3. Universe override: optional `stocks-universe.json` in the state dir (LQ45/IDX80 lists + valid-until); validated; falls back to the embedded list;
   `GET/PUT /v1/stocks/universe` (codes `^[A-Z]{4}$`, ≤ 200).
4. Jobs API: `GET /v1/stocks/jobs` (list with next run, last run, last error), `PUT /v1/stocks/jobs/{id}` (enabled, time/interval within bounds).
5. `docs/protocol.md` §20.2 Jobs + universe; CHANGELOG [Unreleased] one Server line (top of Added).
## Hard rules
Only `server/**`, `docs/protocol.md`, `CHANGELOG.md`, `docs/v2-stocks/runs/TSC1/build-report.md`. Std lib + existing modules only. Fakes only; never call
Yahoo, never connect to Hermes, no port 8760, no real state dir. A reviewer reads the last two commits meanwhile (read-only). No commits; never git
checkout/restore/stash/reset. Gate after your LAST edit: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TSC1` exits 0 + `go test -race
./internal/stocks/...`. Report ≤ 12 lines.
