# TR1c — server fix round 2 of 2 for TR1 (re-review CHANGES REQUESTED)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. TR1 + TR1b are uncommitted and are your base. This is the LAST fix
round before the PM stops and asks the user. Read `PROJECT.md`, your input `docs/track-record/runs/TR1b/review-report.md` fully (probes,
file:line), `runs/TR1b/{build-brief,build-report}.md`, and spec `docs/track-record/spec.md` row **P16a** (new) plus P15, P15a, P16, Q122.

## Do
1. **MAJOR 1 / P16a.** A network error (no HTTP status: DNS, connect, timeout, reset) stops the run like a block: nothing scored after it,
   nothing given up. `giveUp` only when the code ALSO failed on an earlier trading day's run (persist the code's first-failure date in the
   history state or derive it; one run after an offline gap never gives up). Tests: the reviewer's 3-weeks-offline probe (first run one
   network error per code -> nothing `unscorable`; next day good data scores them); network down at boot for every code; a code failing on
   two separate trading days, 10+ trading days after its session -> `unscorable`.
2. **P16a 422.** A 422 means "too old" only for sessions older than ~50 days; a 422 on a window of only recent sessions marks the code failed
   (records wait). Test: yesterday's call + a 422 -> unscored, not a daily loss.
3. **§20.5.** Define "decided" exactly as P16a (for Q122's "n of 10"), and say `n` counts every record of the group.
4. **Surviving mutants** (review list): reverse-split detection, level order and `price > 0` in the record check, the guard that keeps bad
   records out of the history, stale bar-store reuse for info-only runs, strict version-1 decoding: one direct test each that FAILS when the
   guard is removed (show it on a scratch copy).

## Hard rules
Files: `server/**`, `docs/protocol.md`, `docs/track-record/runs/TR1c/build-report.md`. Not yours: `android/**`, `CHANGELOG.md`, other docs.
No new Go modules. Fakes only: never the real Yahoo, Hermes, port 8760 or state dir. No commits; never `git checkout/restore/stash/reset`.
RED first on a scratch copy of the current files for items 1, 2, 4. Avoid `bash -c`/heredocs/chained `$(...)`: script files + plain
`bash <file>`. Gate after your LAST edit: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/track-record/runs/TR1c` exits 0 and
`cd server && go test -race -count=3 ./internal/stocks/...` ok.

## Report
`docs/track-record/runs/TR1c/build-report.md`, ≤ 12 lines: per item file:line, tests + RED-first, gate line + count, files, not done.
