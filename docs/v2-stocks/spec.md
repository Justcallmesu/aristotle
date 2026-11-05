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
