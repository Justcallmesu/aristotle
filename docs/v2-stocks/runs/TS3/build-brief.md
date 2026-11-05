# TS3 — phone: stock detail page + chart (slice 1 last ticket)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read fully: `docs/design.md`, `docs/design/v2/stocks-notes.md` (detail + chart
sections) and the board `docs/design/v2/stocks.html` (screens "Stock detail" x3: held 1M candles, 1D crosshair, not-held 6M line),
`docs/v2-stocks/spec.md` (Q91, Q93, Q94, P3), `docs/protocol.md` §20 (`/v1/stocks/chart/{code}`, quotes extra fields `week52_*`, `tick`, `ara`,
`arb`; news `?code=`), `server/internal/stocks/http.go` + `yahoo.go` for exact JSON and the range→interval map. Code to build on (TS2/TS2b,
committed e5c16e9): `ui/stocks/**` (the placeholder `StockDetailScreen.kt` at route `stock/{code}`), `data/stocks/**`, `LinkState.serverEndpoint`.

## Do
1. **Detail page** per the board: header (code, name, last, change with arrow + sign in the Q91 palette, "as of"), range chips 1D 5D 1M 6M 1Y
   (5Y only if the board has it), chart type toggle candles/line, the chart, volume bars, key numbers (prev close, open/high/low, volume, 52-week
   high/low, tick, ARA/ARB with "n/a" when null), holding block when held (lots, avg, value, P/L after fees — reuse TS2 helpers), related news
   (`?code=`, 5 + "All news"), watchlist add/remove action, "Open in Ajaib" (Q94; hidden when not installed; launch intent only).
2. **Chart (P3, Compose Canvas, no library):** pure scaling module (price→y, time/index→x, nice price ticks on the IDX tick grid where sensible,
   time labels in WIB per range) fully unit-tested; candles ink-only per Q93 (hollow up, filled down) — colour only on the header/change values;
   line mode; volume sub-panel; session gaps (lunch break, overnight, weekends) collapsed by plotting by bar index, not wall time; prev-close
   dashed line on 1D; **crosshair**: long-press/drag shows a readout (time, O/H/L/C or price, volume), snaps to bars, haptic tick on snap,
   ends on release; TalkBack: the chart has a content description summarising range, last, change, high/low.
3. Data: refetch chart on range change; cache per (code, range) while the page lives; 1D refreshes every 60 s while visible + reachable + market
   open (reuse `QuoteRefresh` rules); stale/blocked (`stale:true`, 503 retry_after) states per board; empty chart state.
4. Performance: draw ≤ ~1300 bars (5D at 5m, 1Y daily) without jank — precompute paths outside draw; no allocation per frame in the crosshair.

Tests: scaling (incl. flat series, one bar, nulls removed by the server), tick labels, gap collapsing, crosshair snapping/readout, range→request
mapping, refresh rule, JSON fixtures (copy from `server/internal/stocks/testdata` / http tests), holding block math reuse.

## Hard rules
Files: `android/**`, `CHANGELOG.md` [Unreleased] one Phone line, `docs/v2-stocks/runs/TS3/build-report.md`. strings append-only EN+ID. No new
libraries. No commits; never git checkout/restore/stash/reset. No adb; never connect to a server. Gate after your LAST edit.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TS3` exits 0. Report ≤ 12 lines.
