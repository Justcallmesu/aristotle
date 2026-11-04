# TS0 report — slice 1 data sources + `/v1/stocks/*` contract

Probed 2026-11-05 00:26–00:30 WIB (IDX closed; last session Wed 04 Nov) from the laptop, one IP: 34 requests to data hosts (8 curl, 23 Go, 3 real
Chrome), ≥ 1.2 s apart, no login/key/cookie; bodies in `~/.hermes/profiles/pm/cache/scratch/ts0/`. Rules/calendars: web search (idx.co.id is 403).
## 0. Client fingerprint (must be handled in TS1)
`chart/BBCA.JK?interval=5m&range=1d`: curl + browser UA → `429 Too Many Requests` (the other 6 Yahoo URLs too); curl default UA and Go default UA
`Go-http-client/2.0` → `429 Edge: Too Many Requests`; **Go + UA `Mozilla/5.0 (X11; Linux x86_64) … Chrome/141.0.0.0 Safari/537.36` → 200** (20 more
calls fine); real Chrome → 200. Same IP throughout: Yahoo blocks by fingerprint (UA + TLS), not volume. The provider sends a browser UA; a 429
may come with no overuse: treat it as "blocked", back off, show it.
## 1. Ticker search
- `query1.finance.yahoo.com/v1/finance/search?q=<q>&quotesCount=10&newsCount=0` → 200, no crumb; `symbol`, `shortname`, `longname`, `exchange` (`JKT`
  = IDX), `quoteType`, `sector`, `industry`; all exchanges mixed, 7 results/query. `q=bca` → **no BBCA.JK** (BCA=F, BCAX, …, BCAP.JK); `q=BBCA` → 2nd;
  `q=bank central asia` → 1st; `q=telkom` → TLKM.JK 1st. Unknown code: `chart/ZZZQ.JK` → `404` `"description":"No data found, symbol may be
  delisted"`. No free full IDX list: idx.co.id `403` to Go (`GetConstituent?indexCode=LQ45`, PDFs) and to real Chrome (Cloudflare "Attention Required!").
- **Recommend:** search the bundled universe first (names + aliases, e.g. BBCA "BCA"), then Yahoo filtered to `exchange=="JKT"`; a 4-letter query is
  also tried as `CODE.JK` on chart. **Fallback:** bundled list + "add by exact code" checked by chart (404 → unknown).
## 2. LQ45 / IDX80
- No automatic free source (idx.co.id 403; Yahoo index components need a crumb). Reviews are **quarterly**, not Feb/Aug (Kontan: "evaluasi mayor yang
  digelar setiap tiga bulan"; periods from 3 Aug 2026 and 2 Nov 2026 – 29 Jan 2027).
- LQ45 now (Peng-00204 26 Oct + revision Peng-00205/BEI.POP/10-2026; in ELSA SMGR, out CUAN SCMA): AADI ADMR ADRO AKRA AMMN AMRT ANTM ASII BBCA BBNI
  BBRI BBTN BMRI BRPT BUMI CPIN DEWA ELSA EMTK ESSA EXCL GOTO HRTA ICBP INCO INDF INDY INKP ISAT ITMG JPFA KLBF MAPI MBMA MDKA MEDC NCKL PGAS PGEO PTBA
  SMGR TLKM UNTR UNVR WIFI (45). IDX80: in AALI SMDR TINS TKIM, out CUAN HEAL MIKA SMRA; transcribe all 80 in TS1 from
  pusatdata.kontan.co.id/news/daftar-lengkap-saham-idx80-mulai-2-november-2026-empat-saham-tergusur (not fetched).
- **Recommend:** bundled `universe.json` (code, name, aliases, tags, `valid_to`), hand-edited after each quarterly announcement; flagged once past.
## 3. Yahoo chart / quote (BBCA.JK, Go + browser UA)
| Request | Result |
|---|---|
| `chart ?interval=5m&range=1mo` / `15m&1mo` | 200: 2001 bars (513 all-null) 05 Oct 09:00 – 04 Nov 16:10 WIB / 667 bars (155 null) |
| `5m&6mo` / `1h&5y` | `422` "5m data … must be within the last 60 days." / "1h data … within the last 730 days." |
| `1h&1y` / `1d&5y` | 200: 1912 bars (09:00…16:00) / 1200 bars + `indicators.adjclose` |
| `v7/finance/quote?symbols=BBCA.JK,BBRI.JK` (query1 and query2) | `401` "User is unable to access this feature" (crumb needed) |
| `v7/finance/spark?symbols=<4>&range=1d&interval=5m` / 25 symbols | 200, per symbol `meta` + `close[]` / `400` "…less than or equal to 20" |
- Shape: `chart.result[0]` = `meta`, `timestamp[]` (unix s), `indicators.quote[0].{open,high,low,close,volume}[]`. Lunch-break bars (15m 12:00–13:15)
  have a timestamp and **all-null OHLCV → drop**. Daily bars are stamped 09:00 WIB; today's still had `close:null` (o 4840 h 4980 l 4830) after the
  session → fill from `meta.regularMarketPrice`. Prices are floats (`4930.0`) → integer IDR.
- `meta` (chart and spark): `regularMarketPrice` 4930, `previousClose` 4840, `regularMarketChangePercent` 1.86, `fulldayChange` 90,
  `regularMarketDayHigh/Low/Volume`, `regularMarketTime` (16:14:44 WIB), `longName`, `shortName`, `currentTradingPeriod`. `previousClose` is null for
  `1d/5y`; `chartPreviousClose` = close before the range (6100 for 1mo, 7375 for 5y), so use it only with `range=1d`. Spark answers in its own
  order (BBRI, ADRO, BBCA, TLKM for a BBCA-first request): key by `symbol`. Headers: `Cache-Control: public, max-age=10`; no `Set-Cookie`, no
  rate-limit headers. Delay not re-measured (closed); the 2026-11-03 probe saw the last bar ~603 s old → 10 min delayed.
- **Recommend:** quotes via spark (≤ 20/call), bars via chart, query1 only. Ranges 1D→5m/1d, 5D→15m/5d, 1M→1h/1mo, 6M→1d/6mo, 1Y→1d/1y, 5Y→1d/5y.
  TTL: quotes 60 s while open, else until next open; bars 60 s / 1 h daily; search 24 h. 429/5xx: back off 1 → … 15 min, serve cache as stale.
## 4. RSS news (probed 00:28 WIB)
| Feed | Items, span | Item fields |
|---|---|---|
| `www.cnbcindonesia.com/market/rss` 200 | 100, 03 Nov 11:03 – 04 Nov 23:23 | `title` CDATA, `link`, `guid`, `pubDate`, `description` CDATA (`<img src>` + summary), `enclosure` image/jpeg (100/100), `content:encoded` |
| `investasi.kontan.co.id/rss` 200 | 25, 15:09 – 21:54 | `title`, `link`, `description` (text), `pubDate`; no image |
| `www.idxchannel.com/rss` 200 | 10, ~4 h | `title`, `link`, `guid`, `idnews`, `pubDate`, `enclosure` image/jpg, `category` (ECONOMICS 5, ECOTAINMENT 2, MARKET NEWS 3), `description` (escaped `<img>` + CDATA) |
- `www.kontan.co.id/rss` (spec §6 "RSS 200") is an **HTML index page**, not a feed. `pubDate` is RFC 1123 + numeric zone everywhere (`Wed, 04 Nov 2026
  23:23:39 +0700`, Go `time.RFC1123Z`). Dedupe by `link`. Kontan and IDX Channel roll over in hours: poll every 10 min 07:00–22:00 WIB, else 30 min.
- 4-capital tokens in the 135 items: IHSG 40, PGEO 10, ANTM 5, … Non-tickers: IHSG, CNBC, MSCI, SRBI, BUMN, KSSK, APBN, UMKM, PLTS, KSEI; `(IHSG)` 3×
  in parentheses, so "(XXXX)" is no proof. Codes that are words: CASH (seen as "K-CASH"), MINE, KEEN, ACES, TOYS, ENVY, TELE, BUMI, EMAS, BANK, GOOD.
- **Recommend matching:** candidates = watchlist ∪ holdings codes (slice 2 adds the universe). Tokenise title + stripped description on
  non-alphanumerics, keeping `-` inside tokens (`K-CASH` ≠ `CASH`); **case-sensitive** exact match = hit, `strong` if `(CODE)` or after
  "saham"/"emiten", else `weak`. Plus whole-word, case-insensitive full name minus `PT`/`Tbk`/`(Persero)`; short aliases ("BCA", "Antam") only from
  the bundled list. Risks: all-caps headlines; group names ("Adaro" → AADI/ADRO/ADMR, "Astra" → ASII/AALI).
## 5. Holidays — bundled list, hand-edited each September
- 2026 (Peng-00171/BEI.POP/09-2025, 239 trading days): remaining closures **24, 25, 31 Dec 2026**.
- 2027 (Peng-00169/BEI.POP/09-2026 of 16 Sep 2026, 241 trading days; SKB 1205/3/2 of 2026): Jan 1, 5; Feb 5; Mar 8, 9, 10, 11, 12, 15, 25, 26; May 6,
  17, 18, 19, 20; Jun 1; Aug 17; Dec 24, 31 — from the SKB weekday dates + the usual 31 Dec; 261 weekdays − 20 = 241 = BEI's count (computed).
## 6. Display rules (Peraturan II-A, amended by Kep-00136/BEI/09-2026, effective 28 Sep 2026)
- Lot = 100 shares (VI.4). Minimum price **Rp1** (was Rp50). Tick (fraksi, VI.5.2, by previous price): < 200 → 1; 200–< 500 → 2; 500–< 2,000 → 5;
  2,000–< 5,000 → 10; ≥ 5,000 → 25 (Kep-00023/BEI/04-2016, idx.co.id press release 518; VI.5.2.1/2 unchanged in the indexed Kep-00136 PDF).
- ARA: Rp1–10 ±Rp1; > 10–200 35 %; > 200–5,000 25 %; > 5,000 20 %. ARB **7 %** above Rp10 since 19 Oct 2026 (Kep-00157/BEI/10-2026); Rp11–14 → Rp1
  since 20 Oct (idx.co.id press release 2734); valid to 31 Dec 2026, then re-evaluated (Kep-00136's symmetric ARB from 1 Jan 2027 was replaced).
  Acceleration / full-call-auction boards 10 %. Slice 1 shows integer IDR ("." thousands) and lots; tick/ARA/ARB go in a dated server table.
## 7. Proposed contract — protocol §20 "Stocks" (device token as §16, `Cache-Control: no-store`, errors `{"error":"<code>"}`)
| Method, path | Body / query | 200 response (abridged) |
|---|---|---|
| `GET /v1/stocks/watchlist` | — | `{"items":[{"code":"BBCA","name":"Bank Central Asia Tbk"}]}` in user order |
| `PUT` / `DELETE /v1/stocks/watchlist/{code}` | — | the list; PUT appends, idempotent; DELETE `404 not_in_watchlist` |
| `PUT /v1/stocks/watchlist` | `{"codes":[…]}` reorder, same set | the list; `400 bad_request` if the set differs |
| `GET /v1/stocks/holdings` | — | `{"at","holdings":[{"code","lots":10,"avg_price":4800,"price":4930,"cost":4807200,"value":4917675,"pl":110475,"pl_pct":2.3}],"total":{"cost","value","pl","pl_pct"}}` |
| `PUT` / `DELETE /v1/stocks/holdings/{code}` | `{"lots":10,"avg_price":4800}` (lots 1..10^7, price > 0) | the holdings; DELETE `404 not_held` |
| `GET` / `PUT /v1/stocks/settings` | `{"buy_fee_pct":0.15,"sell_fee_pct":0.25}` (each 0..5) | the settings |
| `GET /v1/stocks/quotes` | `?codes=BBCA,BBRI` (≤ 50; default watchlist ∪ holdings) | `{"at","market":{"state":"open\|break\|closed","next_open"},"quotes":[{"code","name","price","prev_close","change","change_pct","high","low","volume","market_time","stale"}],"errors":[{"code","error"}]}` |
| `GET /v1/stocks/chart/{code}` | `?range=1d\|5d\|1mo\|6mo\|1y\|5y` | `{"code","range","interval":"5m","tz":"Asia/Jakarta","prev_close","stale","bars":[{"t","o","h","l","c","v"}]}` |
| `GET /v1/stocks/news` | `?code=BBCA&limit=50` (no code: everything) | `{"items":[{"id","source":"cnbc\|kontan\|idxchannel","title","link","published_at","summary","image_url","codes":["BBCA"],"match":"strong\|weak"}],"sources":[{"source","ok","fetched_at","error"}]}` |
| `GET /v1/stocks/search` | `?q=bca` (1..40 chars) | `{"results":[{"code","name","in_universe":true}]}` |
- `code` = `^[A-Z]{4}$` after uppercasing, no `.JK`, else `400 bad_code`; unknown at Yahoo `422 unknown_symbol`; > 50 entries `409 limit_reached`;
  Yahoo blocked/429 `503 upstream_blocked` + `retry_after_s`; other upstream failure `502 upstream_error` (quotes/chart answer from cache with
  `stale:true` when they can). P/L: shares = lots × 100; cost = avg × shares × (1 + buy fee); value = price × shares × (1 − sell fee); integer IDR.
- Layout (as `pcstatus` + `statefile`): `internal/stocks/` — `stocks.go` (Service: state dir, Provider, News, Now, Log; owns a sub-mux), `state.go`
  (`stocks.json` `{"version":1,…}` via `statefile.WriteAtomic`/`Read`), `pl.go` (pure math), `provider.go` + `yahoo.go` (`Quotes/Bars/Search`;
  browser UA, 20-symbol batches, TTL cache, backoff), `news.go` (fetch, `encoding/xml`, matcher, ring of 300), `idxcal.go` (hours, holidays, lot,
  tick, AR table), `universe.go` + embedded `universe.json`. `httpapi.Deps` gains `Stocks http.Handler` (nil → no route) at `/v1/stocks/` behind `requireDevice`.
## Open risks
- Yahoo fingerprint checks may tighten (yfinance moved to curl_cffi for this); then only a TLS-impersonating client or a paid feed helps.
- IDX Channel `/rss` mixes non-market news (filter `category == "MARKET NEWS"`); a market-only feed path was not probed.
- IDX80 list and 2027 holidays were read second-hand (news/search; IDX PDFs blocked): re-check by eye in TS1. ARB after 31 Dec 2026 is unknown.
