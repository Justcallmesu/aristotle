# Aristotle v2 — IDX stocks: spec

Status: **slice 1 in design** (2026-11-05). Builds on the v2 outline and decisions in `docs/spec.md` §1 v2,
Q8, Q13-Q18, Q20-Q22, Q31 (still binding). Analysis only, no broker integration (Q17). Personal use.

## Slices (Q84)
1. **Watch** — watchlist, hand-entered holdings with P/L after fees, price charts, news feed.
2. **Screen** — built-in setups scanned over the universe (Q85, Q86), candidates list.
3. **Brief + alerts** — 08:30 WIB AI brief, intraday alerts, AI runs within the daily budget (Q87).
Each slice is usable on its own and gets its own ticket set after the previous one ships.

## Decisions
| Id | Decision | Notes |
|---|---|---|
| Q84 | Build slice by slice: (1) watchlist + holdings + charts + news, (2) screener, (3) 08:30 AI brief + alerts. | User 2026-11-05. |
| Q85 | Screener universe = LQ45 + IDX80 (~80 liquid stocks, editable list on the PC) + the watchlist. | User. Keeps Yahoo traffic ~80 calls/scan. |
| Q86 | Rules = built-in setups, each on/off with tunable thresholds: breakout above 20-day high with volume, volume spike x2, MA20/50 cross, RSI rebound, gap up/down. | User. No formula editor. |
| Q87 | AI runs: the 08:30 brief + on demand + at most 5 automatic runs a day when a rule fires on a watchlist stock. | User. Narrows Q21. Uses the Claude plan limits (TY7 shows them). |
| Q88 | Holdings P/L includes broker fees: default buy 0.15 %, sell 0.25 % (incl. tax), editable in Settings. | User. |
| Q89 | Stocks live behind a switch at the top of Home: Chats \| Stocks. | User. |
| Q90 | A mockup board for the stock screens first (TD4); Android build after the user picks. | User. |
| Q91 | Price/P&L colours: green/red as a separate stock palette (stocks-notes contrast table, ≥ 5.4:1 both themes), always with an arrow and a +/- sign; red keeps meaning "error" everywhere else. Amends `docs/design.md` (no-green rule) for stock values only. | User 2026-11-05. |
| Q92 | Home switch = **title words**: "Chats  Stocks" replace the Aristotle wordmark in Home C's head (no extra height). | User ("Title Words" Tweak), not the board's recommendation. |
| Q93 | Two-line watchlist rows; ink chart (hollow up candles, filled down). | User. |
| Q94 | "Open in broker app" opens **Ajaib** (package `ajaib.co.id`, VERIFIED installed on the phone 2026-11-05); a stock-specific deep link is ASSUMED unavailable, so it launches the app. Hidden when Ajaib is not installed. | User. |
| Q95 | The real aristotle-server runs as a systemd user service on the laptop (installed 2026-11-05 by the PM: `scripts/build-server.sh ~/.local/bin/aristotle-server`, `install --start`). | User. |
| Q96 | Emiten analyzer (on-demand AI, pulled forward from slice 3): horizon = **day trade** ("good to buy TODAY?"), entry zone / target / stop for today. | User 2026-11-05. Matches Q14. |
| Q97 | Input = numbers computed on the PC (trend, MA20/50, RSI, volume vs average, support/resistance, 52-week position) + recent news; the AI only judges and explains and must not invent prices. No web search. Target ~30-60 s. | User. |
| Q98 | Verdict: not held -> Buy today / Wait / Avoid; held -> Add / Hold / Sell; confidence low/med/high; entry zone, target, stop-loss; 3-5 reasons with sources; "data as of". | User. Refines Q14c. |
| Q99 | "Analyze" button on the stock page; the last result per stock is kept on the PC with its time and reopened without a new AI run. | User. |
| Q100 | Build order: analyzer right after TS3, before the screener (slice 2). | User. Changes Q84's slice order for this piece only. |
| Q101 | Analyzer reasons are written in the phone's language at the time of the tap (`lang` sent with the request). | User 2026-11-05. App language setting (= phone language unless chosen in Aristotle): confirmed by "All recommendations" 2026-11-05. |
| Q102 | The PM created the Hermes profile `aristotle-stocks` via the hermes CLI (2026-11-05 10:25): `profile create --no-alias --no-skills`; `platform_toolsets {cli:[no_mcp]}`; `agent.disabled_toolsets` [x_search, web, browser, terminal, file, code_execution, delegation, memory, skills, clarify]; `auxiliary.title_generation.enabled false`. Smoke run answered "OK / none" (no tools). | User-approved. The server itself still never writes Hermes files. |
| Q103 | Analyzer model = **Opus 5.5** (profile default `claude-opus-5-5`, anthropic). Uses limits fastest, incl. the Opus week. | User; PM recommended Sonnet. |
| P5 | Analyzer runs on a socket ticket scoped to the `aristotle-stocks` profile only (TA0); sessions `{source:"tool", hidden:true, toolsets:["no_mcp"], ignore_rules:true, reasoning:"low", max_turns:3, run_budget:90}`; never `unattended:true`; abort if `info.tool_count != 0`; 120 s timeout + `session.interrupt`. | PM technical (TA0 §1-§6). |
| P6 | Verdict checks: off-tick or out-of-ARA/ARB prices and wrong order (Buy: stop < entry ≤ target) are rejected, with ONE repair turn naming the errors; still bad -> job fails. No silent snapping. | PM technical: a snapped number is a number the AI did not say. |
| P7 | Session cleanup: delete the Hermes session after a saved verdict; failed ones are kept 7 days (debugging) then deleted by the server. One job per code, max 2 at once. | PM technical. |
| P8 | Slice 2 bar history = one JSON file `stocks-bars.json` in the state dir (1-2 MB, rewritten once a day), not SQLite: no new dependency and no query needs SQL (TSC0 §1). Universe gets an optional `stocks-universe.json` override (makes Q85 "editable" true). | PM technical. SQLite would need user approval (new module). |
| P9 | Brief no-prices rule (TSC0 §2): a year passes only after an unambiguous year marker (`tahun`/`year`, a month, `Q1-4`/`H1-2`/`FY`, `semester`/`kuartal`, `2026F/E`, full dates); every word that can also introduce a price (`pada`, `hingga`, `in`, `by`, `until`, `akhir`, `awal`, `sejak`, `since`, `outlook`, `di`) is NOT a year marker, and the prompt tells the model to write "tahun 2026". Non-ASCII digits are refused. Tuning stops here: after three rounds the residual risks are (a) a price slips through only if the model first breaks the prompt rule, (b) ordinary year text costs one extra Opus repair turn. (b) is measured, not guessed: each stored brief carries `repaired`; check the rate after ~2 weeks of real briefs (device check list) before changing the rule again. | PM technical 2026-11-06 (TBA1b/TBA1c reviews). |
| Q112 | Screener board (TD5) picks — the board's recommended options (PM reading, confirmed by the user's "All recommendations" 2026-11-05): two-line 64 dp candidate rows, tonal setup labels, section under the watchlist, universe "Edit list" button on the phone. | User 2026-11-05. |
| P1 | Slice 1 storage = the server's existing `statefile` JSON (watchlist, holdings, fee settings); prices and news cached in memory with short TTLs. No SQLite until slice 2 needs bar history. | PM technical: no new dependency for a few KB of state (YAGNI). |
| P2 | Phone <-> server for stocks = REST `/v1/stocks/*` with the device token, following `GET /v1/pc-status` (`internal/pcstatus`, protocol §16). | PM technical: reuse the proven path. |
| P3 | Charts drawn with Compose Canvas (candles + volume + line), no chart library. | PM technical: one more dependency vs ~300 lines of testable scaling code; revisit if gestures get complex. |
| P4 | Watchlist and holdings live on the PC (single source of truth; slice 3 alerts read them there). | PM technical. |

## Verified facts (2026-11-05, PM probe)
- Yahoo chart `BBCA.JK?interval=15m&range=5d`: HTTP 200, `exchangeTimezoneName` Asia/Jakarta, currency IDR.
- RSS 200: cnbcindonesia.com/market/rss, kontan.co.id/rss, idxchannel.com/rss.
- Earlier facts: `docs/spec.md` §6 "IDX data sources" (10 min delay, idx.co.id 403, hours).

## Research findings (TS0, `runs/TS0/report.md`, probed 2026-11-05 00:26-00:30 WIB, market closed)
- Yahoo answers 429 to curl/Go default User-Agents, 200 with a browser User-Agent: the provider sends one. VERIFIED (TS0).
- Quotes: `v7/finance/quote` needs a crumb (401); use `v7/finance/spark` (no crumb, max 20 symbols/call). VERIFIED (TS0).
- Chart limits: 5m/15m only 60 days (422 beyond), 1h 730 days, 1d 5 y. Lunch-break bars are all-null (drop); today's 1d bar
  close is null after the session (fill from the quote); `chartPreviousClose` only valid with `range=1d`. VERIFIED (TS0).
- Ticker search: Yahoo search misses BBCA for "bca" -> bundled ticker list first, Yahoo .JK second, exact code checked via chart
  (404 = unknown). VERIFIED (TS0).
- LQ45/IDX80 change quarterly and idx.co.id blocks scripts and browsers -> bundled lists edited by hand. IDX80 list and 2027
  holidays come from news/search, ASSUMED until checked by eye in TS1.
- RSS: **correction to `docs/spec.md` §6**: `kontan.co.id/rss` is an HTML page; the feed is `investasi.kontan.co.id/rss`.
  Kontan/IDX Channel keep only hours of items -> poll every 10 min. Ticker matching needs a stop-list (IHSG, MSCI, BUMI, EMAS...).
- IDX rules: lot 100; tick sizes 1/2/5/10/25 by price band; minimum price Rp1 since 2026-09-28; lower auto-reject 7 % since
  2026-10-19 (set only to 2026-12-31). Holidays: bundled list.
- Contract proposal for `/v1/stocks/*` in the TS0 report (basis for TS1).

## Known risks
- Yahoo ToS forbids automated collection; may block or change (Q16 accepted). Provider interface + backoff.
- PC off = no data (Q31). The server has not yet run as a systemd service on this laptop (v1 T22 owed).

## Open for the user (TSC0 design §4, asked 2026-11-05 while the user was away)
| Id | Question | PM recommendation |
|---|---|---|
| Q104 | Holdings get optional hand-set stop/target fields for price alerts. | User 2026-11-05 "All recommendations". |
| Q105 | 08:30 brief language: the app language last sent by the phone, else Indonesian. | User 2026-11-05 "All recommendations". |
| Q106 | Bearish signals: chips on watchlist/holding rows only (no avoid list). | User 2026-11-05 "All recommendations". |
| Q107 | Auto AI runs (≤ 5/day): only when a code scores ≥ 2 setups; watchlist + holdings. | User 2026-11-05 "All recommendations". |
| Q108 | News outside 08:00-16:30 waits for the brief; no brief on non-trading days. | User 2026-11-05 "All recommendations". |
| Q109 | With 'keep connected' off, alerts arrive only while the app is open; Settings says so. | User 2026-11-05 "All recommendations". |
| Q110 | Models: Sonnet for automatic runs, Opus for the 08:30 brief (on-demand Analyze stays Opus, Q103). | User 2026-11-05 "All recommendations". |
| Q111 | Default setup thresholds (TSC0 §1 as corrected by TS4) to start, tunable in the settings sheet. | User 2026-11-05 "All recommendations". |
| Q113 | A holding's target is not checked against the average price (only tick grid, stop < avg, stop < target), same as the server's `checkLevels`. | User 2026-11-06 "A" (kept the TBA2 builder choice). |
| Q114 | Alerts read more than 30 min after they fired (phone was away) go to the alerts list without a notification. | User 2026-11-06 "A" (kept the TBA2 builder choice). |
| Q115 | Brief card when the newest stored brief is from an earlier day: TBA2 shows "not a trading day" unless `/jobs` says the brief job is off. **PM reading — confirm**: the PM's 2026-11-06 summary described it wrongly as "shows its date"; the TBA2 review checks the trading-day-before-08:30 case (should read "pending"). | PM 2026-11-06; user to confirm. |
