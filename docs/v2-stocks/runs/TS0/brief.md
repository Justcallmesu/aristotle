# TS0 — research: slice 1 data sources (read-only)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/spec.md`, `docs/spec.md` §6 IDX data sources,
`docs/protocol.md` §11 and §16, `server/internal/pcstatus/**`, `server/internal/statefile/**`, `server/internal/httpapi/**`.

Answer with evidence (exact URL, HTTP status, a trimmed sample, date/time of the probe). Polite probing only: ≤ 60 requests in total,
≥ 1 s apart, a normal browser User-Agent; never log in anywhere; never use a paid API key.
1. Ticker search/list: Yahoo search (`query1.finance.yahoo.com/v1/finance/search?q=bca`) for .JK, or another free list of all IDX
   tickers with names. Recommend one, plus a fallback (a bundled list refreshed by hand).
2. LQ45 / IDX80 constituents: a free source that can be read automatically, or else "bundled list, edited by hand each Feb/Aug review".
3. Yahoo chart: allowed interval/range pairs (5m, 15m, 1h, 1d; 1d/5d/1mo/6mo/1y/5y), the real delay, the shape (OHLCV arrays, nulls),
   quote fields for last price / previous close / day change, and any rate-limit or crumb/cookie needs (query1 vs query2). One batch
   endpoint for many symbols (`v7/finance/quote`?) — does it work without a crumb?
4. RSS: item fields of cnbcindonesia market, kontan, idxchannel (title, link, pubDate format, description, images); how to match an item
   to a ticker (code in title like "BBCA", company names); a recommended matching rule with false-positive risks (e.g. "ADRO" vs words).
5. IDX holiday calendar 2026/2027: a source, or "bundled list".
6. Display rules: lot = 100 shares; IDX tick-size (fraksi) table; auto-rejection limits — cite a source.
7. A proposed `/v1/stocks/*` REST contract for slice 1 (watchlist CRUD, holdings CRUD, fee settings, quotes, chart bars, news, ticker
   search) in the style of protocol §16, and the server package layout (`internal/stocks/...`) following `pcstatus`.

Write `docs/v2-stocks/runs/TS0/report.md` (≤ 90 lines): findings with evidence, recommendations, open risks, and the contract. No code,
no commits, no edits outside that report.
