# TP1c — flaky pcstatus CPU test (fix the cause)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. `server/internal/pcstatus/pcstatus_test.go:156`
`TestStatusAssemblesEveryField` failed in the PM's full gate (`docs/v1-tickets/runs/pm-gate-57/gate-server-test.log`: "cpu_percent never
appeared" after 3 s) and passes 2 of 3 runs alone.

PM's diagnosis (ASSUMED — confirm or refute from the code): the sampler runs every 5 ms; if it takes two samples of the NEW
`/proc/stat` before `Status` reads, the latest pair has zero total delta and the collector reports `null`, and every later pair is also
(new,new) -> null forever. So it is both a test race and a product bug: an unchanged counter pair must not erase the last known CPU value.

## Do
1. Product: the reported CPU % is computed from the last pair with a non-zero total delta; a zero-delta pair keeps the previous value
   (still null only before the first real pair). Test.
2. Test: make sampling deterministic (inject a tick/sample trigger or call the sample step directly) — no sleeps-and-poll loops with
   real timers for the value assertions. Run the package 50× with `-count=50 -race` and report.
Files: `server/internal/pcstatus/**`, `docs/v1-tickets/runs/TP1c/build-report.md`. No commits; never git checkout/restore/stash/reset;
never touch port 8760.
Verify: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TP1c` exits 0. Report ≤8 lines.
