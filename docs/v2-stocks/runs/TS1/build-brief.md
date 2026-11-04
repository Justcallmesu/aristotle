# TS1 — server: stocks module, slice 1 (`/v1/stocks/*`)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read fully: `PROJECT.md`, `docs/v2-stocks/spec.md` (Q84-Q90, P1-P4,
research findings), `docs/v2-stocks/runs/TS0/report.md` (**§7 is your contract**, §0 client fingerprint, §3 Yahoo rules, §4 RSS + matcher,
§5 holidays, §6 tick/lot/AR), `docs/protocol.md` §11 and §16. Pattern files to copy: `server/internal/pcstatus/**` (service + handler +
injected readers + tests), `server/internal/statefile/**` (atomic JSON state), `server/internal/httpapi/httpapi.go` (Deps, `requireDevice`),
`server/internal/app/**` (wiring).

## Do
Build `server/internal/stocks/` per TS0 §7 layout: Service with injected Provider (Yahoo), News fetcher, clock and logger; `stocks.json`
state (watchlist ≤ 50, holdings ≤ 50, fee settings, `version:1`) via statefile; pure `pl.go` (formulas in TS0 §7, integer IDR, round half
up, pl_pct 2 dp); Yahoo provider (browser User-Agent, `v7/finance/spark` in 20-symbol batches for quotes, `v8/finance/chart` for bars with
the range→interval map, drop all-null bars, fill today's null 1d close from the quote, `prev_close` rule from TS0 §3, TTL cache: quotes 60 s,
intraday bars 60 s, daily bars 15 min; on 429 backoff and answer from cache with `stale:true`, else `503 upstream_blocked` + `retry_after_s`);
news (the three feeds with the corrected Kontan URL, poll every 10 min only while some device asked in the last 30 min — no traffic when
nobody looks, `encoding/xml`, ring of 300, dedupe by link, ticker matcher with stop-list, strong/weak); `idxcal.go` (session hours incl. Friday,
bundled holidays 2026-2027, market state open/break/closed + next_open, lot, tick table, AR table dated); `universe.go` with embedded
`universe.json` (LQ45 from TS0 + IDX80 + a bundled ticker→name list for search; mark the IDX80 rows' source in a comment); search = bundled
list first, then Yahoo filtered to `.JK`; adding an unknown code checks the chart endpoint (404 → `422 unknown_symbol`). Mount at
`/v1/stocks/` behind `requireDevice` via `httpapi.Deps.Stocks` (nil → no route); wire in `app`. Logs never contain holdings values.
Add `docs/protocol.md` **§20 Stocks** (the contract as built). CHANGELOG [Unreleased] one Server line.

Tests (table-driven, fakes only, no network): P/L math incl. fees and rounding; state round-trip, limits, reorder same-set rule, idempotent PUT;
each endpoint's 2xx/4xx/5xx; Yahoo JSON fixtures (copy trimmed samples from `~/.hermes/profiles/pm/cache/scratch/ts0/` if still there, else
hand-write from TS0 §3) incl. null bars and 429 → stale cache; market state across Friday, lunch break, holiday, weekend (WIB, injected clock);
tick table bands; news parse per feed + matcher false positives (IHSG, MSCI, K-CASH, BUMI/EMAS as words); news polling stops when idle.
Optional: ONE live smoke run (≤ 10 requests total to Yahoo/RSS, browser UA), result in the report; never in the test suite.

## Hard rules
Only `server/**`, `docs/protocol.md` §20, `CHANGELOG.md` [Unreleased], `docs/v2-stocks/runs/TS1/build-report.md`. Std lib + existing deps
only (no SQLite, no new module). Never bind a listener; never touch port 8760 or the real state dir; demo/test dirs are temp XDG dirs.
No commits; never git checkout/restore/stash/reset. No adb.
Verify: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TS1` exits 0. Report ≤ 15 lines.
Stop and ask (write it in the report and stop) if the contract in TS0 §7 cannot be built as written.
