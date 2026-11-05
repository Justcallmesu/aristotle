# TM3a build report (android minors: TS3b + TA2b android part)

Gate `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TM3a` → PASS android, exit 0 (no flake rerun needed). No adb, no server contact, no commits.

- Stale 1M/6M/1Y: `ChartCache.due(..., stale)` reads it again on each show/pick; the stale line has Retry (`detail::retry`). VM test `aStaleRangeIsReadAgain…`.
- 60 s 1D refresh vs hold: `ChartHold.pick` keeps the shown model while the crosshair is held (one crosshair per range, derived `holding`); the swap lands on release. Pure test.
- `Modifier.systemGestureExclusion()` on the chart box only (source-guard test; needs a phone check for the real edge).
- 1.3x: `ChartFit` (pure): readout height and chart height grow with fontScale; grid/last/crosshair price labels shrink to the axis (drop below 70%, tags never dropped); time labels drop if they would pass the bottom. Tested at 1.3. Real-device look still unverified.
- 1D slots: 12:00 / Fri 11:30 / break / pre-09:00 clamp into the nearest session slot and share it, so later bars do not shift (`IdxSessions.isOffSchedule`; `DayEdgeSlotsTest`).
- Back from All news → stock: `newsFrom` + `PairedPage.backFrom(runningFrom, newsFrom)`; opening a stock from News resets it (`PairedPageTest`).
- Line mode, one bar: draws a dot (`ChartFit.lineIsDot`; drawing is not unit-testable, rule is).
- "Add holding" menu item disabled until holdings load (`DetailRules.holdingEnabled`); Ajaib tap when gone → snackbar + button hides (`DetailRules.brokerTap`); reuses the existing `stocks_broker_missing` string, so no new strings.
- "From <day>" now the verdict's `session_date` (analyzedAt only if unreadable) (`AnalysisTest`); a refused start's error clears when a read shows `done` (`StockDetailAnalysisTest`).
- Files: android main (Analysis, StockChart, StockDetail, AristotleApp, PriceChart, StockDetailScreen, StockDetailViewModel) + tests (AnalysisTest, StockChartTest, PairedPageTest, StockDetailAnalysisTest, StockDetailViewModelTest).
