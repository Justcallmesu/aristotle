# TBA1 — server: slice 3 (08:30 brief, alerts, auto AI runs) + TSC2 review minors

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read fully: `docs/v2-stocks/spec.md` (Q18, Q21, Q31, Q87, **Q104-Q110**, Q103, P5-P8),
`docs/v2-stocks/runs/TSC0/design.md` **§2 (slice 3 design) and §3**, `runs/TS4/report.md`, `runs/TSC2/review-report.md` (findings 1-5), `runs/TA0/report.md`
(model pin per session at create time), `docs/protocol.md` §12 (relay socket / how the phone gets pushes today), §20-§20.3. Code: `server/internal/stocks/**`
(scheduler, screener, hits log, analyzer, news, state), `server/internal/hermes/analysis.go`, relay/notification code the phone already receives.

## First: TSC2 minors (prerequisites for alerts)
1. A scan must not overwrite a newer settings re-rank (compare a settings revision; drop/re-rank a stale result). 2. Settings PUT rejects `null`
fields (400). 3. Hits log: mark the origin (scan / eod / rerank) and only scan+eod hits may trigger alerts; log bullish hits for watchlist + held
codes even when illiquid or outside the universe (Q107 covers holdings + watchlist). 4. Day's open: retry on a later scan when the first try failed or
had no bar today; keep opens out of the phone chart cache (own small map). 5. A stock with no trade today keeps its stored daily signals/flags (order
the stale-quote check before the prev-close check).

## Then: slice 3 per TSC0 §2 with the user's decisions
- **Holdings stop/target (Q104):** optional `stop`/`target` per holding (validated: tick grid, stop < avg/target); state file stays version-compatible.
- **Alerts:** price alerts (holding stop/target hit; kept verdict entry/target/stop hit, Q18), setup hits on watchlist/holdings (scan+eod origin), breaking
  news on watchlist/holdings during 08:00-16:30 (Q108: outside that window news waits for the brief). Dedupe per code+kind per day; quiet outside market
  hours except the brief. Delivered to the phone per TSC0 §2 (long-poll or the relay event path — use what TSC0 chose; Q109: no delivery when the phone
  is not connected; keep a ring so the app shows missed alerts on open).
- **08:30 brief (Q18, Q105, Q108, Q110 Opus):** a job at 08:30 WIB on trading days only; inputs: overnight/pre-open news for watchlist + holdings,
  yesterday's eod screen, holdings P/L, kept verdicts; a tool-less Hermes run (reuse the analyzer client, Opus) producing a short structured brief in
  the language last sent by the phone else Indonesian; stored (last 7) + an alert "Brief ready". Catch-up per Q31 (before 09:00 run, else mark missed).
- **Auto AI runs (Q87, Q107, Q110):** when a watchlist/held code reaches score ≥ 2 setups (scan/eod origin), run the analyzer automatically with model
  **Sonnet** pinned per session (verify the create param in the Hermes source; if not possible, stop and report), max 5/day (counter persisted, resets
  at 00:00 WIB), never twice per code per day; result kept like a manual run, flagged `auto`, and an alert.
- Endpoints per TSC0 §2 (alerts feed, brief latest/list, holdings stop/target fields, auto-run counter in a status endpoint); jobs registered on the
  scheduler (`brief`, `alerts`, `news`) with Q20 on/off. Docs: `docs/protocol.md` §20.4 (+ §20.2 jobs line). CHANGELOG [Unreleased] Server lines.
Logs never contain prompt, verdict, brief text or holdings values.

## Hard rules
Only `server/**`, `docs/protocol.md`, `CHANGELOG.md`, `docs/v2-stocks/runs/TBA1/build-report.md`. No new Go modules. Fakes only (fake Hermes, fake Yahoo,
fake clock); never connect to the real Hermes; no real sessions; no port 8760; no real state dir. A PM gate runs on `android/**` in parallel — not yours.
No commits; never git checkout/restore/stash/reset. Gate after your LAST edit: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TBA1`
exits 0 + `go test -race ./internal/stocks/... ./internal/hermes/...`. Report ≤ 20 lines. Stop and report if TSC0 §2 conflicts with Q104-Q110.
