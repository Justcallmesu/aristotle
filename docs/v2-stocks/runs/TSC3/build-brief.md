# TSC3 — phone: screener section, page and settings

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read fully: `docs/design.md`, `docs/design/v2/screener-notes.md` + the board
`docs/design/v2/screener.html` (**Q112: the recommended Tweaks** — two-line 64 dp rows, tonal labels, section under the watchlist, Edit list button),
`docs/v2-stocks/spec.md` (Q85, Q86, Q106, Q111, Q112), `docs/protocol.md` **§20.2 (jobs/universe) and §20.3 (screener contract; note `high`+`lookback`)**,
`server/internal/stocks/{screener,screener_settings,http}.go` (READ ONLY; uncommitted, under review), `docs/v2-stocks/tickets.md` row TSC3 (MINOR-A, MINOR-B
from the TM3a2 review). Code: `ui/stocks/**`, `data/stocks/**`.
## Do
Screener section on Stocks home (under the watchlist), Screener page (Today / Last close, candidate rows with setup labels + score, scan time,
⋮ Scan now with the server's too_soon cooldown), bearish flags on watchlist/holding rows (Q106), Settings › Stocks › Screener (each setup on/off +
thresholds with the server's ranges, liquidity floor, scan interval via the jobs endpoint, universe count + Edit list sheet using the universe
endpoints), all board states (before first scan, Yahoo blocked/stale, no candidates, PC offline, market closed). Tap a candidate -> stock page
(through `show(...)`, MINOR-B). Refresh: read on show; every 60 s while visible + reachable + a scan window is open. MINOR-A: the source check
that chartHeight uses the Canvas style/density. Pure tests: parsing (fixtures from the server tests), settings validation mirror, state mapping,
refresh rule, cooldown.
## Hard rules
Files: `android/**` EXCEPT `data/stocks/StockChart.kt`, `ui/stocks/PriceChart.kt`, `ui/stocks/StockDetailScreen.kt`, `ui/stocks/StockParts.kt` (uncommitted
PM delta TM4; if you must change one, write the change in the report instead), plus the report. strings append-only EN+ID. No new libraries. No
adb; never connect to a server. No commits; never git checkout/restore/stash/reset. Gate after your LAST edit: `GATE_ONLY=android bash
docs/v1-tickets/gates.sh docs/v2-stocks/runs/TSC3` exits 0. Report ≤ 15 lines with a "Doc text" CHANGELOG Phone line.
