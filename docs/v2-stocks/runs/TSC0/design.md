# TSC0 — design: slice 2 screener + slice 3 brief/alerts

Read 2026-11-05 (no network): `docs/v2-stocks/spec.md` Q84-Q103/P1-P7, `docs/spec.md` Q14-Q22/Q31, `server/internal/stocks/**`, protocol §12/§20/§20.1,
`android/.../background/**`. Labels: VERIFIED = read in code/fixtures here; ASSUMED = not checked. All new routes are additive (no `ProtocolVersion` bump, §0).

## 0. Facts the design rests on
- Universe: `universe.json` is **embedded** (`go:embed`), 89 rows, 80 tagged LQ45/IDX80 (45 ⊂ 80), valid to 2027-01-29. VERIFIED. Q85 says "editable on the
  PC": add an optional override `stocks-universe.json` in the state dir (same schema; invalid -> logged, bundled list used). Screened set = 80 + watchlist
  (≤ 50); quoted set adds the holdings (≤ 50, for alerts and the brief): ≤ 180 codes, typically ~90.
- Spark (`v7/finance/spark`, 20 symbols/call) carries price, `previousClose`, day high/low/volume and **closes only** (no open, no `regularMarketOpen`): VERIFIED
  `testdata/spark.json`. So a gap needs a chart call. Chart `1d` bars carry OHLCV. Market has a shared 429 backoff (1-15 min). VERIFIED `provider.go`.
- No scheduler exists in the server; the news poller runs only while a phone asked in the last 30 min. VERIFIED. No server-wide `WriteTimeout` (only
  ReadHeader 10 s, Idle 2 min, `listen/manager.go:166`): a long-poll can be held. VERIFIED. Phone OkHttp read timeout 10 s (`HttpClients.kt:64`).
- Relay `/v1/ws` = Hermes pass-through, exists only while Hermes is reachable (4503 otherwise), frames unchanged (§12). Phone `LinkService` (FGS) only
  keeps the process alive while "keep connected" is on and paired, also with Hermes down (`BackgroundPolicy`). Channels: `link`, `turn_finished`, `needs_you`. VERIFIED.

## 1. Screener (slice 2, Q85/Q86)
**Data budget (Yahoo).** (a) `bars` job 16:20 WIB trading days, one chart call (daily interval) per quoted code (~90, ≤ 180), paced 1 per 1.5 s, stops
on a block: first time `1y`, later `5d` merged into the store (overlap off by > 0.5 % -> refetch `1y`: split adjustment); full `1y` reseed Saturdays.
(b) `scan` every 10 min (tunable 5-30), 09:15-16:15 minus the break (+10 min delay: 12:15-13:40, Fri 11:45-14:10): ⌈codes/20⌉ spark calls (typ. 5, ≤ 9).
(c) Gap confirm at the first scan ≥ 09:15: chart `1d` only for codes whose first 5-min close is ≥ (gap threshold − 1 pp) from the base (few).
≈ 36 scans × 5 + 90 + few ≈ **280 calls/day** typical, ≤ 520 worst case. A block skips the slot (result keeps the last good scan, `stale`); no retries burst.
**Storage — recommend JSON file, no SQLite (PM call, proposed P8).** `stocks-bars.json` (state dir, 0600, `statefile.WriteAtomic`): `{"version":1,
"codes":{"BBCA":{"fetched_at","bars":[[t,o,h,l,c,v],…]}}}` ≈ 90-180 codes × 250 bars ≈ 1-2 MB, read once at start, rewritten once a day. Every query is
"series of one code": no joins, no ad-hoc SQL. SQLite = a new third-party module (`modernc.org/sqlite`, pure Go, ~+6 MB, `modernc.org/libc` tree; or
`ncruces/go-sqlite3`, wazero) for no query need: not proposed; revisit for months of intraday history. `stocks-screener.json`: last intraday + 5 EOD.
**Setups** (pure functions over completed daily bars D = bars dated before `session_date`, last = D[n]; live P/V/Hd/Ld from spark; B = D[n].C = limit base;
reuses `indicators.go` sma/rsiWilder/volume pacing). Intraday mode during sessions; EOD mode at 16:20 = the same rules on the just-completed bar.
| id | fires when (intraday) | EOD form | defaults / tunables |
|---|---|---|---|
| `breakout` | P > max(H of D[n-19..n]) and vol_ratio_paced ≥ m | C(n) > max(H of D[n-20..n-1]) and V(n) ≥ m × avg20 before it | lookback 20 (10-60), m 1.5 (1.0-5) |
| `volume_spike` | vol_ratio_paced ≥ k, only after ≥ 30 elapsed trading minutes | V(n) ≥ k × avg V of D[n-20..n-1] | k 2.0 (1.5-10) |
| `ma_cross` | EOD only (shown all next day): MA20(n) > MA50(n) and MA20(n-1) ≤ MA50(n-1) = `up`; mirror = `down` | same | fast 20, slow 50 (fixed v2) |
| `rsi_rebound` | RSI14 with P as provisional close ≥ L while RSI14(n) < L | RSI(n-1) < L ≤ RSI(n) | L 30 (15-45) |
| `gap` | g = (open − B)/B × 100; `up` g ≥ x, `down` g ≤ −x (open from chart `1d` first bar) | — | x 2.0 (0.5-10), up on, down on |
Each setup has `on`. `vol_ratio_paced` = V / avg20 ÷ elapsed fraction (330/270 min, existing). Bearish forms (`ma_cross down`, `gap down`) never make a
code a candidate; they show as chips on watchlist/holding rows only (USER Q4). **Score** = Σ weights of bullish setups fired (breakout 3, volume_spike 2,
ma_cross up 2, rsi_rebound 2, gap up 1) + 0.1 × min(vol_ratio_paced, 5), 1 decimal; sort score desc, then code. Candidate = ≥ 1 bullish setup.
**Results model.** `{"at","mode":"intraday|eod","session_date","data_as_of","universe":{"count","source":"bundled|file","valid_to","expired"},
"scan":{"state":"idle|running","last_run","next_run","error","codes_ok","codes_failed"},"stale","candidates":[{"code","name","in_watchlist","held",
"price","change_pct","score","setups":[{"id","dir":"up|down","values":{…numbers used…},"first_fired_at"}],"data_as_of"}],"flags":[{"code","setups"}]}`.
**REST (§20.2).** `GET /v1/stocks/screener?mode=intraday|eod` (default: intraday while a session date trades and a scan ran today, else eod) -> the object;
`POST /v1/stocks/screener/scan` -> `202` (manual scan; `409 scan_running`; `429 too_soon` < 2 min after the last); `GET`/`PUT /v1/stocks/screener/settings`
`{"scan_minutes":10,"setups":{"breakout":{"on":true,"lookback":20,"vol_mult":1.5},"volume_spike":{"on":true,"mult":2.0},"ma_cross":{"on":true},
"rsi_rebound":{"on":true,"level":30},"gap":{"on":true,"min_pct":2.0,"up":true,"down":true}}}` (whole object, ranges above, else `400 bad_request`),
stored in `stocks.json` → state version 2 (v1 read as defaults). Errors as §20. Before the first `bars` run: `200` with `candidates:[]`, `scan.error:"no_bars"`.
**Phone.** Stocks home gets a "Screener" section under the watchlist: top 3 candidates + "See all (N)" + data time. Screener page: rows = code, name,
price/change (stock palette Q91), score, setup chips (`Breakout`, `Vol ×2.3`, `MA ↑`, `RSI rebound`, `Gap +2.4 %`); filter chips by setup; EOD/Today
toggle; pull-to-refresh = GET (scan button with cooldown in the ⋮ menu); tap -> stock detail (Analyze there). Top-bar gear -> settings sheet: per setup a
switch + stepper(s), scan interval, Save/Defaults (Q86: no formula editor). Watchlist rows get small chips for setups firing today.

## 2. Brief + alerts (slice 3, Q18/Q21/Q87)
**Scheduler (server, `stocks/jobs.go`).** One goroutine ticking every 30 s on **wall clock** (Go timers stop during laptop suspend; a tick compares
`now` to each job's due time). Jobs: `bars` 16:20, `eod_screen` after `bars`, `scan` (sessions, §1), `news` (every 10 min 06:00-16:30 trading days, plus
the existing on-demand polling), `brief` 08:30, `alerts` (after each scan and news poll), `sweep` (existing, daily). Editable in v2 (Q20): on/off for every
job except `sweep`; time for `brief` and `bars`; interval for `scan`. `stocks-jobs.json`: per job `{on, at|every, last_run, last_status, last_error}`,
`auto_runs:{date, used}`. **Catch-up at boot (Q31):** `bars` missed (store older than the last completed session) -> run now; `brief` missed and before
16:00 of its trading day -> run now (`late:true`), else recorded `missed`; `scan` resumes at the next slot; nothing else catches up.
REST: `GET /v1/stocks/jobs` -> `{"jobs":[{"id","on","at","every_min","last_run","last_status","last_error","next_run","editable":[…]}],"auto_runs":{"used","cap":5}}`;
`PUT /v1/stocks/jobs/{id}` `{"on":true,"at":"08:30"}` (fields per `editable`; `at` HH:MM 05:00-23:00, `every_min` 5..30).
**08:30 brief.** Inputs (no Yahoo call needed: bars are stored): news since the previous close (16:00) up to 08:25, all feeds, matched codes; watchlist +
holdings each with last close, change, MA20/50 dist, RSI14, trend, s1/r1, pivot, ARA/ARB for today, holding P/L % (no lots/avg in the prompt beyond
`hold:pl_pct`); yesterday's EOD candidates (top 10 with setups). One run in `aristotle-stocks` with P5 session settings (`run_budget` 120, same 120 s
timeout, one repair turn, P7 cleanup). Same untrusted-data SYSTEM block as TA1b. Output `stock_brief/1`: `{"schema","session_date","headline" (≤ 160),
"summary" (≤ 600),"focus":[{"code","stance":"watch_buy|watch|avoid","note" (≤ 200),"sources":[…]}] (≤ 8, codes from DATA),"news":[{"text","sources"}] (≤ 5),
"risks":[{"text","sources"}] (≤ 3)}`. **No prices in the reply**: levels are cited by id (`lvl:BBCA:r1`) and the phone renders the stored numbers, so
nothing can be invented (Q97). Kept per day in `stocks-briefs.json` (30 days) with the DATA snapshot ids; `GET /v1/stocks/brief?date=YYYY-MM-DD`
(default latest) -> `{"date","state":"pending|running|done|failed|missed","late","error","result"}`; `GET /v1/stocks/briefs` -> dates + headlines;
`POST /v1/stocks/brief` -> rerun today (on-demand AI, not counted). Hermes down at 08:30 -> retry every 5 min until 09:00, then `failed`.
**Intraday alerts** (computed in `alerts` after each scan/news poll; trading days only; all carry `data_as_of`):
- `level` — kept verdicts whose `session_date` is today (day-trade horizon Q96): buy_today/add -> `entry` (P within [entry_low, entry_high]),
  `target`/`stop` (max/min of P and the spark 5-min closes **after `analyzed_at`**, never the day high/low from before the verdict); hold ->
  `target`/`stop`. Holdings use their code's kept verdict; no verdict = no level alert (USER Q2).
- `news` — a new article with a **strong** match on a watchlist/holding code (weak matches never push), 08:00-16:30 (outside -> brief, USER Q6).
- `setup` — a bullish setup newly fires on a **watchlist** code -> alert, and an **automatic analysis** (`trigger:"auto"`) if `auto_runs.used < 5`
  today (Q87; resets 00:00 WIB; counted at job start, a `busy`/Hermes-down refusal is not counted), at most one auto run per code per day, skipped when
  a verdict for today is < 60 min old. Its result posts an `analysis` alert ("BBCA: Buy today, med"). On-demand Analyze and the brief never count.
- Dedupe: key `(date, kind, code, level|article id|setup)` fires once per day; ≤ 3 alerts per code per day, ≤ 30 per day overall (then one summary
  alert); nothing pushed while `stale`. Store `stocks-alerts.json`: ring of 300 / 7 days, monotonic `seq` persisted.
**Delivery — recommend REST long-poll from the phone's process.** `GET /v1/stocks/alerts?after=<seq>&wait=0..50` answers at once when items with
`seq > after` exist, else holds up to `wait` s -> `{"seq","items":[{"seq","id","kind":"level|news|setup|analysis|brief","code","title","text","at",
"data_as_of","detail":{…}}],"auto_runs":{"used","cap"},"reset":false}`; `after` missing -> newest 50 (list page); `after` > latest -> `reset:true`.
Why: (1) the relay socket is wrong — it dies with Hermes (stocks must not, TS2b MAJOR-1) and must forward Hermes frames unchanged; (2) a timer poll
(coroutine `delay`, WorkManager ≥ 15 min, Doze alarms ≥ 9 min) does not wake reliably with the screen off; a held request wakes the phone on the
reply packet, the same mechanism the relay's turn notifications already rely on (T10); (3) a new `/v1/events` WebSocket would also work but adds a
second keepalive/supersede/cap protocol on both sides for one event type. Phone: `StockAlertWatcher` in the AppContainer (process-wide), runs while
paired and the process lives (FGS keeps it when the app is closed; "keep connected" off = alerts only while open, USER Q7); per-call OkHttp read timeout
70 s; cursor in DataStore (first run starts at the latest `seq`: no flood); backoff as the relay (1-30 s) on errors; pauses on 401 like the rest.
**Notifications.** New channels: `stock_alerts` (IMPORTANCE_HIGH, heads-up: level, setup, analysis) and `stock_news` (DEFAULT: news, brief ready).
VISIBILITY_PRIVATE with a public version "Stock alert" (no codes/prices on the lock screen; holdings privacy). Tag = `stock:<kind>:<code>` (newer
replaces older). No action buttons. Tap: level/setup/analysis/news -> stock detail of `code` (new `MainActivity.openStock(code)` intent, Stocks mode);
brief -> brief page. Settings › Notifications lists the two channels; Settings › Stocks gets "Jobs & alerts" (job switches/times, alert kinds, auto-AI
switch, "Auto analyses today 2/5"). Stocks home: brief card at the top 08:30-16:00 (headline + "Read"), bell -> alerts list (from `GET /v1/stocks/alerts`).

## 3. Tickets, order, risks
| Id | Side | Scope | Depends |
|---|---|---|---|
| TSC1 | server | scheduler (wall-clock tick, `stocks-jobs.json`, catch-up), bar store + `bars` job, universe override, `GET/PUT /v1/stocks/jobs`, protocol §20.2 jobs part | — |
| TSC2 | server | setups (pure, table tests per formula), intraday `scan` + `eod_screen`, gap confirm, `/v1/stocks/screener` (+ `scan`, `settings`), state v2 | TSC1 |
| TSC3 | android | Screener section + page + settings sheet + watchlist chips (contract from §20.2 doc, fake server) | TSC2 doc |
| TBA1 | server | alerts engine + store + long-poll `/v1/stocks/alerts`, auto-run trigger + counter (analyzer `trigger`), news job hours | TSC2 |
| TBA2 | server | brief job: DATA, prompt, `stock_brief/1` parse/validate/repair, `stocks-briefs.json`, `/v1/stocks/brief(s)`, `brief` alert | TSC1, TSC2 |
| TBA3a | android | `StockAlertWatcher`, two channels, notifications + tap targets, alerts list | TBA1 |
| TBA3b | android | brief card + page (levels rendered from ids), Settings › Stocks › Jobs & alerts | TBA2, TBA3a |
Order: TSC1 → TSC2 → (TSC3 ∥ TBA1) → TBA2 → TBA3a → TBA3b. TBA1 and TBA2 both touch `analyzer.go`/`jobs.go`: not in parallel. Slice 2 ships at TSC3.
**Risks.** Yahoo throttling at ~280-520 calls/day (Q16 accepted; shared backoff, slot skipped, `stale` shown). 10-min delay: a level alert is ≥ 10 min
late — every alert shows its data time (Q14d). Long-poll wake with the screen off on HyperOS is ASSUMED (device check in TBA3a; fallback = `/v1/events`
socket). Opus for brief + 5 auto runs/day burns the Opus week fastest (Q103, USER Q8). Gap open from the first `1d` bar ASSUMED equal to the IDX
opening price (pre-open auction); check on day 1. Always-on news polling: ~18 RSS fetches/h on trading mornings. PC asleep at 08:30 = late/missed (Q31).

## 4. USER questions (not decided by Q-rows)
1. Storage: OK to keep bar history in a JSON file (no new dependency, PM P8)? SQLite would need approving a new module (`modernc.org/sqlite`).
2. Holdings without a kept verdict get no price alert. Want a hand-set stop/target per holding (e.g. on the holding sheet), or a default "−X % from avg"?
3. Brief language: there is no tap to read `lang` from (Q101). Use the language the phone last reported (sent with settings), default Indonesian?
4. Bearish signals (gap down, MA death cross): chips on watchlist/holdings only (proposed), or also listed as "avoid" candidates?
5. Automatic AI run (Q87): on any bullish setup on a watchlist stock (proposed), or only `breakout`/score ≥ 3? Holdings too, or watchlist only (Q87 text)?
6. News outside 08:00-16:30 (e.g. 19:00 on a held stock): hold for the brief (proposed) or push? Brief on non-trading days: skip (proposed) or a recap?
7. With "keep connected" off, alerts arrive only while the app is open. Acceptable, or alerts need their own always-on switch?
8. Model: brief and auto runs on Opus 5.5 like Analyze (Q103), or Sonnet for the automatic ones to spare the Opus week?
9. Thresholds: defaults above (breakout 20 d × 1.5 vol, spike ×2, RSI 30, gap 2 %, scan every 10 min) — OK as starting values?
