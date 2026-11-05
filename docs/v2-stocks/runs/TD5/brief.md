# TD5 — design board: screener (slice 2)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/runs/TS4/report.md` §5 (phone UI proposal) + TSC0, `docs/design.md`,
`docs/design/v2/stocks-notes.md`, and copy the frame/tokens/fonts/Tweaks pattern from `docs/design/v2/stocks.html` (source in `docs/design/v2/stocks-src/`).
Load `claude-design` and `antislop-ui`. Make `docs/design/v2/screener.html` (self-contained) + `docs/design/v2/screener-notes.md` (≤ 60 lines, Compose
mapping): Stocks home with a Screener section; Screener page (last scan time, candidates as two-line rows with setup chips + score, bearish chips
on watchlist/holding rows per Q106); settings page (each setup on/off + thresholds, liquidity floor, universe count + "edit list"); states
(market closed, before first scan, stale/Yahoo blocked, no candidates, PC offline). Light + dark, realistic IDX samples labelled as samples.
2 variants in Tweaks where taste matters (row density, chip style), mark one recommended. Edit only those two files (+ a `screener-src/` copy of any
build source) and `docs/v2-stocks/runs/TD5/report.md` (≤ 10 lines). No commits.
