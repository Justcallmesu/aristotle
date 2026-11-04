# TD4 — design board: Stocks (slice 1)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read and follow: `docs/design.md` (binding), `docs/design/v2/design-notes.md`
(v2 component language: Soft corners, grouped rows, meters §5.7, Home C), open `docs/design/v2/mockups.html` to copy its frame,
fonts (Atkinson Hyperlegible Next / Mono), tokens, phone frame size and Tweaks panel pattern. Decisions: `docs/v2-stocks/spec.md` Q84-Q90.
Load the `claude-design` skill and the antislop-ui skill.

Make `docs/design/v2/stocks.html` (self-contained, no network) and `docs/design/v2/stocks-notes.md` (≤ 80 lines: per screen, the
components and the Compose mapping). Screens, light + dark, with realistic IDX data (BBCA, BBRI, TLKM, ADRO, GOTO; prices in Rupiah
`9.875`, change `+1,25 %`, lots):
1. Home with the **Chats | Stocks** switch (Q89) — both states; the switch must sit in Home C's top bar without crowding the profile avatar.
2. Stocks home: market status line (open/session break/closed, "data 10 min delayed, as of 10:42"), watchlist rows (code, name, last,
   change, a tiny sparkline), holdings summary (value, P/L after fees, today), news strip.
3. Stock detail: header (last, change, as of), chart with range chips (1D 5D 1M 6M 1Y) and type toggle (candles/line), volume, a
   crosshair readout, key numbers, holding block if held, related news, "Open in broker app".
4. Holdings: list with lots, average, value, P/L (after fees) and %; add/edit sheet (code search, lots, average price).
5. News feed: items with source, time, matched tickers as chips.
6. Empty states (no watchlist; PC offline "as of"; market closed).
Give 2-3 variants where taste matters (watchlist row density; P/L colouring: green/red vs IDX-style; chart look) in the Tweaks panel.
Colour for up/down must pass contrast in both themes and not be the only cue (arrow or sign). No commits; edit only those two files.
Report ≤ 12 lines in `docs/v2-stocks/runs/TD4/report.md`.
