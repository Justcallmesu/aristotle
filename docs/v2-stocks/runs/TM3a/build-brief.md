# TM3a — android minors (TS3b + TA2b android part)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read rows TS3b and TA2b in `docs/v2-stocks/tickets.md`, then
`docs/v2-stocks/runs/TS3/review-report.md` and `runs/TA2/review-report.md` (findings with file:line).
Do: stale 1M/6M/1Y bars get a Retry + refetch on show; the 60 s 1D refresh must not cancel an active crosshair hold (defer the swap until
release); exclude the chart area from the system back-gesture edge (`Modifier.systemGestureExclusion` on the chart only); 1.3x font: keep
readout/time/price labels inside the chart (measure text, shrink or drop labels, test at fontScale 1.3 in a pure layout function); 1D slotting for
bars at exactly 12:00 / 11:30 Fri / before 09:00 (clamp into the nearest session slot, test); Back from "All news" opened on a stock returns to the
stock; line mode with one bar draws a dot; "Add holding" menu disabled until holdings loaded; Ajaib tap when uninstalled shows a short snackbar;
"From <day>" names the session date the verdict was for; a refused start's error clears when a later read shows the job done. Tests for each.
Hard rules: only `android/**`, the report. strings append-only EN+ID. No adb; never connect to a server. Another worker edits `server/**`. No
commits; never git checkout/restore/stash/reset. Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TM3a`
exits 0 (known flakes D50 RepairTest / D53 PcStatusPollerTest: rerun once, report it; do not fix them here). Report ≤ 12 lines.
