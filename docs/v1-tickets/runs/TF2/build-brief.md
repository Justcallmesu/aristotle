# TF2 — flaky tests D50 (RepairTest) and D53 (PcStatusPollerTest)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Load and follow the `flaky-test-triage` skill. Board rows D50, D53 in
`docs/v1-tickets/tickets.md`. Flakes seen: `RepairTest.successReplacesTheOldPairingAndItsSettingsButKeepsDrafts` (4+ times, passes alone and on rerun);
`PcStatusPollerTest.readsEveryTenSecondsWhileVisibleAndOnline` (under load; TA2 builder: asserts the wait before it is guaranteed to start).
## Do
For each: reproduce (loop the single test, and under CPU load e.g. parallel `yes > /dev/null` workers you stop afterwards), PROVE the cause with
evidence (stack/timing, which await/dispatcher), fix the TEST (or the code, if the code has a real race — then say so as a finding) with a
deterministic wait (test dispatcher, advanceUntilIdle, a signal), never a bigger sleep. Show N≥50 loop runs green after the fix under load.
## Hard rules
Only those test files (+ production code only if a real race is proven) and `docs/v1-tickets/runs/TF2/build-report.md`. Stop any load processes
you start. No adb. No commits; never git checkout/restore/stash/reset. Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh
docs/v1-tickets/runs/TF2` exits 0. Report ≤ 12 lines with the evidence for each cause.
