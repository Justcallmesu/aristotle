# TS4 — slice 2 (screener): schedule, storage, setups, contract, phone UI, tickets

Read 2026-11-05: spec Q84-Q111/P1-P8, TS0 report, **TSC0 design §1** (earlier slice-2 design; TS4 keeps it and corrects it), `server/internal/stocks/**`
at c71018c (TSC1/b/c already ship the scheduler, `stocks-bars.json`, universe override), protocol §20/§20.2, stocks-notes, Android `ui/stocks/*`.
Live probe Thu 2026-11-05 13:40:43-13:40:58 WIB (session 2 open): 10 Yahoo calls, Go + browser UA, 1.5 s apart, **all 200**; bodies in
`~/.hermes/profiles/pm/cache/scratch/ts4probe/`. VERIFIED = measured or read in code; ASSUMED = not checked.
## 0. New facts (they change TSC0)
- **Delay**: spark `regularMarketTime` was 601-639 s behind the fetch on all 20 codes → data time = now − 10 min. VERIFIED.
- **Gap open**: BBCA daily bar today O = **5050**, first 5-min bar O = **5025**. Daily V 100 810 800 = spark `regularMarketVolume`, while the 5-min bars
  sum to 81 836 500; the missing 18.97 M shares (18.8 %) are most likely the pre-opening auction (ASSUMED). So the IDX open is the **daily bar's O**
  (`chart ?interval=1d&range=5d`), not TSC0's "first bar of chart `range=1d`" (5-min series). Prev close 4930 → gap +2.43 % vs +1.93 %: opposite
  sides of the 2 % default.
- **Gap pre-filter saves nothing**: TSC0's chart call only when the first 5-min close is within 1 pp of the threshold held for 33 of 40 codes today →
  budget one daily chart call per screened code, once a day.
- **Spark = closes only** (no open), and its 5-min series is sparse (BBCA 30 closes vs 37 chart bars): use only its `meta` (price, previousClose, day
  high/low, volume, market time). VERIFIED.
- **Pacing bug**: `tradingMinutes(now)` (`indicators.go`) paces 10-min-old volume by the wall clock: at a 09:40 scan, 40 min against 30 min of data →
  ratio **25 % low**. The screener paces by `market_time`; the analyzer has the same small error.
- **Liquidity**: mean(C×V) over 20 sessions: KPIG 5.5, BKSL 4.9, PNLF 6.0, CBDK 10.5, RATU 19.7 bn (chart 3mo) → IDX80's thin end ≈ **Rp 5 bn/day**.
- **Settings file**: `stocks.json` accepts version 1 only (`state.go:99`); v2 there would switch off an older binary's watchlist → own file.
## 1. Scan schedule and Yahoo budget
- **Job `screener.scan`**: TSC1 interval job, trading days, `every_min` 10 (editable 5-30) + `on`. Windows (wall clock) = session + 10-min delay + 5 min of
  data: Mon-Thu **[09:15,12:15) + [13:40,16:15)** = 18 + 16 = **34 slots**; Fri **[09:15,11:45) + [14:10,16:15)** = 15 + 13 = **28**. The 16:05/16:10
  slots see the pre-closing auction.
- **Per scan**: universe (80, §20.2 `count`) ∪ watchlist (today BBCA, already in it) = 80-130 codes → **⌈N/20⌉ = 4-7 spark calls**, 1.5 s apart
  (~6-10 s; 35 KB, 50-485 ms each). It warms the 60 s quote cache the phone also reads.
- **Gap open**: the first scan with data time ≥ 09:00 fetches `chart 1d/5d` per liquid code, 1.5 s apart (≤ 80 calls, ≤ 2 min), kept in memory for
  the day (refetched after a restart).
- **EOD**: at the end of `bars.daily` (16:20, built: 80-180 chart calls at 1.5 s), from the store, **0 extra calls**.
- **Day total (Mon-Thu, 80 codes, 10 min)**: 136 spark + ≤ 80 gap + 80 bars = **≤ 296 calls**; peak 4 calls/10 min plus two ~2-min bursts at 40/min.
  At 5 min: 67 × 4 = 268 spark, ≤ 428 total. Yahoo blocks by fingerprint, not volume (TS0 §0); its volume limit is ASSUMED.
- **Block** (429/5xx/backoff in force): the scan stops, no retry; the last good result is served with `stale:true`; the shared `Market` backoff (1→15 min).
## 2. Storage — keep P8 (one JSON store, built in TSC1); no SQLite now
| Option | Cost | Verdict |
|---|---|---|
| (a) `modernc.org/sqlite` v1.60.1 (BSD-3) | 5 direct + 6 indirect modules (libc, mathutil, fileutil, x/sys, pprof…) vs our 4; binary +~6 MB (TSC0, ASSUMED: offline build failed, libc v1.77.1 not cached) on 14.0 MB; migrations; user approval (PROJECT.md) | no: each query reads one code's series |
| (b) JSON/gob file per code | 80-180 files, no cross-code snapshot, 180 fsyncs per run | no |
| (c) memory + refetch | 80-180 chart calls after each restart/resume before the first scan | no |
| **built**: `stocks-bars.json`, read once, rewritten by `bars.daily` | ~55 B/bar × 250 × 80-180 ≈ **1.1-2.5 MB** (cap 32 MiB) | **keep** |
New `stocks-screener.json` (0600, atomic, tens of KB): `{"version":1,"settings","last_intraday","eod":[last 5 sessions],"hits":[ring 500, seq]}`;
a bad file → defaults + log, PUT `500 state_error` (as jobs). Revisit SQLite only for months of intraday history.
## 3. Setups (pure, table-tested), ranking, slice-3 hook
**Inputs.** D = completed daily bars from the store (`completedBars`), n = last, B = D[n].C (limit base); spark meta: P, Vt (today's volume),
Tm (`market_time`); O = day open (§1); avg20V = mean V of D[n-19..n]; f = `tradingMinutes(Tm)` ÷ 330 (Fri 270); vr = Vt ÷ (avg20V × f).
Reused from `indicators.go`: `sma`, `rsiWilder`, `AutoReject`, `barsFresh`.
| id | intraday (each scan) | EOD (on D[n]) | defaults (range) |
|---|---|---|---|
| `breakout` | P > max H of D[n-19..n] and vr ≥ m; ≥ 15 data-min | C(n) > max H of D[n-20..n-1] and V(n) ≥ m × avg V of D[n-20..n-1] | lookback 20 (10-60), m 1.5 (1-5) |
| `volume_spike` | vr ≥ k; ≥ 30 data-min (morning skew) | V(n) ≥ k × avg V of D[n-20..n-1] | k 2.0 (1.5-10) |
| `ma_cross` | none; the last EOD result is shown with its date | MA20(n) > MA50(n) and MA20(n-1) ≤ MA50(n-1) → `up`; mirror → `down` | fixed 20/50 |
| `rsi_rebound` | RSI14(D closes + P) ≥ L and RSI14(D) < L | RSI14(n-1) < L ≤ RSI14(n) | L 30 (15-45) |
| `gap` | g = (O−B)/B×100: `up` g ≥ x, `down` g ≤ −x; value `filled` (P back through B) | — | x 2.0 (0.5-10), up/down on |
- **Liquidity**: val20 = mean(C×V) of D[n-19..n] ≥ `min_value_bn` (default **5**, the IDX80 thin end; 0-100; 0 = off) → else `skipped.illiquid`.
- **No signal (per code)**: bars not `barsFresh` or < 51 bars → nothing fires. While open, quote `stale`, Tm older than now − 25 min, or Tm not on the
  session date → no intraday setup. |previousClose − B| > 1 tick (split or data mismatch) → skipped (`codes_stale`).
- **Rank**: score = Σ bullish weights (breakout 3, volume_spike 2, ma_cross up 2, rsi_rebound 2, gap up 1) + 0.1 × min(vr, 5), 1 decimal. Sort score
  desc, then val20 desc, then code; `at_ara` (P ≥ ARA, nothing to buy) sorts last; cap 50. Candidate = ≥ 1 bullish setup. Bearish (gap down, MA down)
  = **flags** on watchlist/holding codes only, no liquidity filter (Q106, PM value).
- **Slice-3 hook**: the first fire of (date, code, setup) appends `hits` `{seq, date, code, setup, dir, at, data_as_of, watch, held, score}`. TBA1's
  `alerts` reads `seq > cursor` for watch/held → a setup alert + an auto-analysis when Q107 allows (≤ 5/day, Q87). The screener never calls Hermes.
## 4. Server contract — protocol §20.3 (style §20: device token, `no-store`, `{"error":code}`; additive, no version bump)
**`GET /v1/stocks/screener?mode=intraday|eod`** (default `intraday` if a scan ran on today's session date, else `eod`) → `200`
`{"at","mode","session_date","data_as_of","stale","market":{state…},"universe":{"count","source","valid_to","expired"},"scan":{"last_run","next_run",
"status":"ok|partial|failed|null","error","codes_ok","codes_failed","codes_stale"},"skipped":{"illiquid","no_bars"},"candidates":[{"code","name","in_watchlist",
"held","price","change_pct","val20","score","at_ara","setups":[{"id","dir":"up|down","first_at","values":{"high20":5050,"vol_ratio":2.31,"rsi":31.4,
"gap_pct":2.43,"ma20","ma50"}}]}],"flags":[{"code","setups":[…]}]}`. Before the first bars run (or no EOD yet): `candidates:[]`, `scan.error:"no_bars"`.
**`GET`/`PUT /v1/stocks/screener/settings`** `{"min_value_bn":5,"setups":{"breakout":{"on":true,"lookback":20,"vol_mult":1.5},"volume_spike":{"on":true,
"mult":2.0},"ma_cross":{"on":true},"rsi_rebound":{"on":true,"level":30},"gap":{"on":true,"min_pct":2.0,"up":true,"down":true}}}`. PUT = whole object
(≤ 4 KiB, unknown fields refused, §3 ranges) → `400 bad_request` / `500 state_error`; it re-ranks the kept inputs at once (no Yahoo call). The scan
interval is **not** here: only `PUT /v1/stocks/jobs/screener.scan {"every_min"}` (one source of truth; TSC0 had it in both).
**`POST /v1/stocks/screener/scan`** → `202 {"scan"}`; `409 scan_running`; `429 too_soon` + `retry_after_s` (< 2 min after the last); closed → EOD from the store.
**Universe (Q85)**: `GET/PUT/DELETE /v1/stocks/universe` already exist (§20.2: whole LQ45/IDX80 list + period); no new route. A stock outside the
indexes joins via the watchlist. The phone shows the universe read-only (count, period, `expired` banner).
## 5. Phone UI (stocks-notes language: Monitor surface, Q91 palette, id-ID numbers, no new colour roles)
**Stocks home**: "Screener" section under the watchlist (PM value; the board may move it): header + muted "as of 10:30" + `TextButton` "See all (N)",
top 3 candidate rows; closed → the "Last close" list with its date.
**Candidate row** (64 dp two-line, `groupedRowShape` like `WatchRow`): line 1 = code (Mono 600 16), name (`bodySmall`, ellipsis), price (`titleMedium`
tnum), `ChangeText`. Line 2 = setup **labels**, not buttons (`labelSmall` on `surfaceContainerHigh`, `shapes.small`, 24 dp): `Breakout 20d`,
`Vol ×2,3`, `MA20↑50 · Wed`, `RSI 31`, `Gap +2,4 %`, then a muted "score 5,2"; bearish flags in loss tokens with an arrow; `ARA` neutral.
Tap → stock page (`StocksBackStack.openStock`). **Analyze stays there (TA2)**: no Analyze on rows, so a stray tap cannot burn an Opus run.
**Screener page** (`PairedPage.StocksScreener`, in `StocksBackStack.PAGES`): `TopAppBar` + gear; subline "80 stocks · as of 10:30 · next scan 10:45";
segmented "Today | Last close"; filter chips All · Breakout · Volume · MA · RSI · Gap; pull-to-refresh = GET; ⋮ "Scan now" (disabled while cooling down).
**Settings** (Settings › Stocks › Screener, also from the gear): per setup `Switch` + number fields with ± (invalid → field error, Save disabled);
"Min value traded (Rp bn)"; "Scan every N min" (writes the job); Defaults; Save. No formula editor (Q86).
**States** (text only, values muted, arrows kept): `no_bars` "Building price history · first list after 16:20"; closed "Market closed · list from Wed
close · next scan Fri 09:15"; break "Session break · last scan 12:05"; empty "No setups fired · 80 stocks scanned at 10:30"; blocked "Yahoo refused at
10:50 · showing 10:30" (colours → `onSurfaceVariant`); partial "3 stocks could not be read"; expired banner "LQ45/IDX80 list ended 29 Jan · update it on the PC".
**Watchlist rows** get the same labels for setups that fired today (one, then "+2").
## 6. Tickets and risks
| Id | Side | Scope | Depends |
|---|---|---|---|
| TSC2a | server | setups + liquidity + score, pure (table tests per formula, incl. the BBCA gap numbers); `stocks-screener.json`; EOD screen at the end of `bars.daily`; `GET …/screener` (eod), `GET/PUT …/settings`; protocol §20.3 | TSC1 |
| TSC2b | server | `screener.scan` job (spark, daily-bar open once a day, data-time pacing, no-signal rules, `hits`, stale), intraday mode, `POST …/scan`; `tradingMinutes` takes the data time (also fixes the analyzer) | TSC2a |
| TD5 | design | board: home section, page, row labels, settings, 7 states (PM picks the recommendation; user may revise) | — (∥ TSC2a) |
| TSC3 | android | section, page, settings page, watchlist labels against the §20.3 doc + fake server; TM3a2 minors A/B | TSC2a doc, TD5 |
Order: TSC2a ∥ TD5 → TSC2b ∥ TSC3 → slice 3 (TBA1 reads `hits`). Open user rows for this slice: Q106 (flags only), Q111 (defaults); PM values used.
**Risks**: Yahoo's volume limit is unmeasured (≤ 296-428 calls/day; Q16 accepted). "Daily O = IDX open" (pre-opening auction) is ASSUMED: check
BBCA's open against Ajaib on day 1. Paced volume over-reads early (hence the 15/30-min floors). The 10-min delay means every result carries `data_as_of`.
The bundled universe ends 2027-01-29. A suspended laptop skips slots (no burst, Q31). A restart costs ≤ 80 gap-open calls.
