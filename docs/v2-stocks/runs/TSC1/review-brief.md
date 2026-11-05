# Review brief — v2 TSC1 (commit HEAD: scheduler, bar store, universe override, jobs API)

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Review the COMMIT `git show <sha of 'v2 TSC1'>` (`git log --oneline -3`), not the
working tree (another worker edits android/**). Inputs: `runs/TSC0/design.md`, `runs/TSC1/build-brief.md` + `build-report.md`, spec Q20/Q31/Q85/P8,
protocol §20.2. PM gate GREEN (577); mutation: catch-up cutoff ignored -> TestSchedulerSuspendJumpCatchesUpLatestSlotOnce fails.
Read-only: only your report; `go test`/`-race` on a scratch copy of the commit is fine (`git worktree`-free: `git archive <sha> server | tar -x -C <scratch>`);
never call Yahoo/Hermes; no port 8760; no real state dir; no git state changes; no adb.
Check: scheduler correctness (WIB without host TZ, DST-free, suspend jumps, holiday/weekend, Friday, no double run, catch-up rules, interval windows,
persisted Handled date, shutdown); bar store (atomic write, caps, pruning, corrupt file, concurrent readers vs the daily rewrite, analyzer freshness
rule); bars job Yahoo politeness (pacing, stop on block, resume order) and total calls; universe override validation + APIs auth/limits; jobs PUT bounds;
no new modules; docs match. Only BLOCKER/MAJOR trigger a round. Report ≤ 25 lines at `docs/v2-stocks/runs/TSC1/review-report.md`, last line `VERDICT: ...`.
