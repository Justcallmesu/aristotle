# TS3 review — stock detail + Canvas chart (static review; no gradle/adb; PM gate pm-gate-ts3 PASS android)

**Checked OK:** JSON `ChartBody`/`ChartBar` match `provider.go:60-80` (`prev_close` nullable, `v` defaulted; quote `week52_*`/`tick`/`ara`/`arb` nullable, StocksModels.kt:36-55,153-171).
Range→query follows the server (`ChartRange` StockChart.kt:24-29; the server's `ChartRanges` provider.go:83). Scale: flat or single bar padded ≥1 tick, so no /0 (StockChart.kt:80-90).
Empty → no model and the empty text (StockChart.kt:345-347, Screen:270-275). Bars are Long, so no NaN can arise. `maxVolume` 0 is guarded and `slots`/`plotWidth` ≥ 1 (PriceChart.kt:258,169).
Grid steps are multiples of the IDX tick (StockChart.kt:93-99). Sessions Mon–Thu and Fri (StockChart.kt:193-196) agree with §20. Every time goes through `StockTimes.WIB`, never the host TZ (StockChart.kt:173).
Snapping is a binary search with no allocation (StockChart.kt:126-137). The crosshair layer reads state only in draw, and its tags are measured once per bar (PriceChart.kt:139-161).
Paths are built in `drawWithCache`. The lambdas capture model/candles/ink, and with Kotlin 2.4.21 strong skipping a new range or series rebuilds them (Screen:255, PriceChart.kt:134-138).
`detectDragGesturesAfterLongPress` consumes moves, so the vertical scroll wins until the long press (PriceChart.kt:124-132). `QuoteRefresher` is reused (VM:78-80).
The VM is scoped per route+code and cleared on leave (AristotleApp.kt:566-582). On 503, the VM re-reads once after `retry_after_s`, which is ≥ 1 s and is the server's remaining backoff; this happens only while active and on the same range (VM:156-163, http.go:40). There is no tight loop.
Ajaib: `<queries>` is in the manifest, the check re-runs on resume, and `open` returns false instead of crashing when the app is gone (StocksText.kt:85-95). Holding: `HoldingMath.preview`, the TS2 helper (StockDetail.kt:12-18).
Q91 colour appears only in `ChangeText`; candles and volume use ink/outline alpha (Q93, PriceChart.kt:66-82,214-223). TalkBack has one summary (Screen:391-406). Strings: 41 EN + 41 ID, appended.
Builder notes accepted: bar sizes follow the server, and 1D is read by the refresher on every show (VM:124-127).

**Findings (all MINOR / NIT — none BLOCKER/MAJOR):**
1. MINOR — A `stale:true` body for 1M/6M/1Y counts as cached for the page's whole life (`ChartCache.due` StockDetail.kt:27-28). The stale line also has no Retry (Screen:303), so the user must leave the page to get fresh bars. Fix: treat stale as due, or show Retry.
2. MINOR — The 60 s 1D refresh builds a new `model`. `remember(model)` then resets the crosshair and `pointerInput(model)` restarts mid-hold (Screen:255-256, PriceChart.kt:115). A hold can be cut off once a minute.
3. MINOR — There is no `systemGestureExclusion` on the chart, which sits only `Spacing.l` from the edge (Screen:259). Bars at the left edge sit inside the back-gesture inset. Needs a phone check.
4. MINOR (1.3×, unverified) — The readout is a fixed 44 dp (Screen:325,561), but two lines plus 4 dp come to ~46 dp. Time labels end at 234 dp + 18 dp > `CHART_HEIGHT` 250 (PriceChart.kt:350-354). A 6-digit price label is wider than the 46 dp axis.
5. MINOR — `BarAxis.day`: a bar stamped exactly at session-1 end (12:00 / Fri 11:30) or before 09:00 takes a slot already in use. `max(previous+1)` then shifts every later bar by one (StockChart.kt:162,186-190). The real 15 m fixture has no such bar, so this is theoretical.
6. MINOR (documented deviation) — All news → Back goes to Home and drops the detail page (AristotleApp.kt:376,387). Restoring it needs a `newsFrom` the way `stockFrom` works.
7. NIT — In line mode a single bar only gets `moveTo`, so no line is drawn (PriceChart.kt:285-291). It shows only the dashed last-price line.
8. NIT — The ⋮ "Add holding" shows before the holdings have loaded (`held` is null, Screen:123-128). The PUT replaces, so in a narrow race it could overwrite an existing holding.
9. NIT — If Ajaib is uninstalled between resume and the tap, the tap does nothing silently, and the button stays until the next resume (StocksText.kt:88).

VERDICT: PASS-WITH-FINDINGS
