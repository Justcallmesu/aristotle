# TM3a2 — fix round for TM3a (review MAJOR-1, MAJOR-2, MINOR)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/runs/review-TM3.md` (file:line), `runs/TM3a/build-brief.md` +
`build-report.md`, then `ui/AristotleApp.kt` (back/News origin), `ui/stocks/PriceChart.kt` (ChartFit), the 1D slotting code in `data/stocks/StockChart.kt`.
## Do
1. MAJOR-1: Home → News → stock → All news → Back must never loop: model the origin as a proper back stack (or reset the remembered origin on every
   back), so Back unwinds each screen once and always reaches Home. Tests for: Home→News→stock→All news→Back×3 = Home; stock→All news→Back = stock;
   top-bar arrow and system back behave the same.
2. MAJOR-2: size the chart from the MEASURED label height (TextMeasurer with the real style, incl. Compose's non-linear font scaling and the app's
   own text-size setting) + 1 px slack, not 14 × fontScale. Never drop all time labels when they fit after growing the box. Tests at app scale 1.0/1.15
   × font scale 1.0/1.3 (the test must use the same measuring path as the screen, or a fake TextMeasurer fed with real metric ratios — explain).
3. MINOR: a bar before 09:00 must clamp into the first slot without shifting later bars; fix the test that expects the shift.
## Hard rules
Only `android/**` + the report. Another worker may edit `server/**`. No adb; never connect to a server. No commits; never git checkout/restore/stash/reset.
Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TM3a2` exits 0 (flakes D50/D53: rerun once, report). Report ≤ 10 lines.
