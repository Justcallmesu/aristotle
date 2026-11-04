# TD4 report — Stocks design board
- Done: `docs/design/v2/stocks.html` (318 KB, self-contained: Atkinson Next/Mono subsets + Literata embedded, Material Symbols as inline SVG, 0 URLs) and `docs/design/v2/stocks-notes.md` (69 lines).
- 13 screens × light + dark: Home Chats / Stocks, Stocks home, add-to-watchlist sheet, detail ×3 (1M candles held, 1D crosshair, 6M line not held), holdings + add (tick error) + edit sheets, news, empty / offline "as of" / closed. Plus switch and row/colour specimens and a contrast table computed from the hex values.
- Tweaks: home switch (3), watchlist rows (2), P/L colour (3), chart look (3), market (open / break / closed), clickable-path view.
- Recommended: segmented switch under the wordmark (+26 dp head; on the wordmark's line it needs 404 of 393 dp), two-line rows, green/red text, Ink chart.
- Spec decision needed: green/red or IDX-style breaks design.md "red = errors only, no green"; proposed `StockColors`, all text ≥ 5.4:1 in both themes. Arrow + sign on every change, so colour is never the only cue.
- Data honesty: values are labelled samples on real IDX rules; only fields Yahoo chart / RSS carry are drawn (no P/E, market cap, foreign flow).
- Verified in Chromium: renders, no JS errors, range/type/crosshair/filter/tweaks/proto navigation work; screenshots checked light and dark.
- Open for TS0/TS1: code search source, ticker matching rules, holiday calendar, which broker app to open.
- No commits.
