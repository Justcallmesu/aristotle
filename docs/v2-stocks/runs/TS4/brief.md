route: deep.

# TS4 — research + design for slice 2 (screener), read-only

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/spec.md` (Q84-Q86 screener: universe LQ45+IDX80+watchlist, built-in
setups breakout 20-day high with volume, volume spike x2, MA20/50 cross, RSI rebound, gap up/down, each on/off + tunable; Q87 AI budget; Q14 day
trading; P1 "SQLite when slice 2 needs bar history"), `runs/TS0/report.md` (Yahoo limits: spark 20/call, chart ranges), `server/internal/stocks/**`
(TS1/TA1: provider, cache, indicators.go — reuse!), `PROJECT.md` (std lib first; SQLite allowed for v2).
Answer, with evidence and numbers, in `docs/v2-stocks/runs/TS4/report.md` (≤ 100 lines):
1. Scan schedule within IDX hours (calendar in idxcal.go): when to scan (e.g. 09:15, every 15 min in sessions, after close), request budget per scan
   (~80-130 codes: spark for quotes + daily bars cached once a day + intraday only for candidates), staying under Yahoo's tolerance (TS0 §0, 429 backoff).
2. Storage: is SQLite needed now (bar history per code for MA50/52w, candidate history)? Compare (a) modernc.org/sqlite (pure Go, size, licence),
   (b) JSON/gob cache files per code, (c) in-memory + daily refetch. Recommend one with the cost (deps, binary size, migration).
3. Exact rule definitions and default thresholds for each Q86 setup (formulas on daily + intraday bars, min liquidity filter e.g. value traded,
   no-signal on stale data), a score to rank candidates, and how a watchlist hit feeds slice 3's alerts later.
4. Server contract: `/v1/stocks/screener` (latest run: time, candidates with setup ids, key numbers, score), `/v1/stocks/screener/settings` (per setup
   on/off + thresholds), universe edit endpoints (Q85 editable list) — style of protocol §20.
5. Phone UI proposal in the v2 stocks design language (`docs/design/v2/stocks-notes.md`): where the screener lives (Stocks home section + its own
   page?), candidate row, setup chips, settings page, empty/closed/stale states; "Analyze" on a candidate reuses TA2.
6. Ticket split (server / phone), risks. No code, no commits, edit nothing else.
