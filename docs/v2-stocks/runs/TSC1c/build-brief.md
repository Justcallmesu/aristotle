# TSC1c — make the jobs-PUT race test actually RED on the old code

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Context: `docs/v2-stocks/runs/TSC1/review-report.md` (MAJOR: `*j = n` in `update`
vs unlocked reads of `j.spec.ID` / `j.spec.Run` in the run goroutine, commit d2e1255 `scheduler.go:612, :382/:385`), `runs/TSC1b/build-report.md`.
PM finding (VERIFIED): `TestSchedulerPutDuringRunIsRaceFree` (TSC1b) PASSES under `-race` on d2e1255 — it does not detect the race. Probably the
channel that releases the blocked job body orders the PUT before the goroutine's reads (happens-before), so the detector sees nothing.
## Do
Rewrite the test so the PUTs run concurrently with the goroutine's unlocked reads with NO synchronisation between them (e.g. a job body that
returns at once, the scheduler ticked in a loop while another goroutine PUTs in a loop for ~200 ms; or PUT after release without waiting).
Prove it RED on the old code: `git archive d2e1255 server | tar -x -C <your scratch>`, append the test, run `go test -race -count=3 -run <name>`
there → must report DATA RACE; then GREEN on the working tree (`-count=20`). Paste both outputs' key lines in the report. Change no production
code unless the test finds a remaining race in TSC1b's fix (then fix it and say so).
## Hard rules
Only `server/internal/stocks/scheduler_test.go` (+ production fix if needed) and the report. Scratch outside the repo. Another worker edits
`android/**`. No commits; never git checkout/restore/stash/reset. Gate after your last edit: `GATE_ONLY=server bash docs/v1-tickets/gates.sh
docs/v2-stocks/runs/TSC1c` exits 0. Report ≤ 8 lines.
