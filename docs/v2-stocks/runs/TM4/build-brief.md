route: deep.

# TM4 — android minors (TS3b + TA2b android parts) + flaky tests D50, D53

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/tickets.md` rows TS3b, TA2b; `runs/TS3/review-report.md`,
`runs/TA2/review-report.md`; `docs/v1-tickets/tickets.md` rows D50 (RepairTest.successReplacesTheOldPairingAndItsSettingsButKeepsDrafts, failed 4x
under load, passes alone) and D53 (PcStatusPollerTest.readsEveryTenSecondsWhileVisibleAndOnline). Load the `flaky-test-triage` skill for D50/D53:
prove the cause (run the test in a loop under CPU load, e.g. 30x with `stress`-like parallel gradle or `--rerun`), fix the TEST's race (or the code
if the code is wrong — say which), show N consecutive passes after the fix.
TS3b: stale long-range bars get Retry + refetch; the 60 s refresh must not cancel an active crosshair hold (defer the swap until release);
exclude the chart from the system back-gesture edge (`Modifier.systemGestureExclusion` on the chart's left/right edge strip); 1.3x font: make the
readout/labels fit (measure with Compose tests at fontScale 1.3); 1D slot for bars stamped at 12:00 / Fri 11:30 / before 09:00 (clamp, test);
Back from All news returns to the stock; single bar in line mode draws a dot; disable "Add holding" until holdings loaded; Ajaib missing at tap
-> a short message. TA2b: "From <day>" names the session date (WIB) it was for; a refused-start error clears when a later read shows done.
Files: `android/**`, report. strings append-only EN+ID. Another worker edits `server/**` in parallel. No adb; never connect to a server. No commits;
never git checkout/restore/stash/reset. Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TM4` exits 0.
Report ≤ 15 lines at `runs/TM4/build-report.md` (include the flaky-test evidence before/after).
