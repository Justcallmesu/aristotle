# TSC2 — server: five setups + scan + `/v1/stocks/screener`

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read fully: `docs/v2-stocks/spec.md` (Q85, Q86, Q87, **Q106 + Q111 PM readings**,
P1, P8), `docs/v2-stocks/runs/TSC0/` (design) and **`runs/TS4/report.md` (binding corrections: gap open from the DAILY bar open; no gap prefilter;
pace volume by the quote's market time, not wall clock; spark summary fields only; settings in a separate `stocks-screener.json`; Rp 5 bn/day
liquidity floor; schedule + Yahoo budget; contract §20.3)**, `docs/protocol.md` §20, code `server/internal/stocks/**` (TSC1 scheduler, bar store,
universe, indicators.go — reuse; analyzer).
## Do
Per TS4: setup evaluators (pure, table-tested with hand values, incl. stale/no-signal rules), scan job on the TSC1 scheduler (10-min slots in
delay-shifted windows; budget logged per scan), ranking score, bearish signals only as flags on watchlist/holding codes (Q106), hits log for
slice 3 (no alerts/AI here), `stocks-screener.json` (version 1, defaults = Q111/TS4 thresholds, tunable, validated ranges), endpoints `GET
/v1/stocks/screener`, `GET/PUT /v1/stocks/screener/settings`, `POST /v1/stocks/screener/scan` (manual, rate-limited) per TS4 §4; docs §20.3;
CHANGELOG [Unreleased] one Server line. Also fix the analyzer's volume pacing to use market time (TS4 finding) with a test.
## Hard rules
Only `server/**`, `docs/protocol.md` §20.3 (+ the analyzer pacing note), `CHANGELOG.md`, report. `server/internal/stocks/verdict.go` has an
uncommitted PM delta (TM3): do not edit it. No new Go modules. Fakes only; no network; no real Hermes; no port 8760; no real state dir. A design
worker writes `docs/design/v2/` in parallel. No commits; never git checkout/restore/stash/reset. Gate after your LAST edit: `GATE_ONLY=server bash
docs/v1-tickets/gates.sh docs/v2-stocks/runs/TSC2` exits 0 + `go test -race ./internal/stocks/...`. Report ≤ 15 lines.
