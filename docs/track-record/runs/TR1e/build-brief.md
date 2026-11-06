# TR1e — server fix round 1 of 2 for TR1d (review CHANGES REQUESTED)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `256b80e`. TR1d is uncommitted (`server/internal/stocks/{stocks,track,
track_test,verdicts,yahoo}.go`, `docs/protocol.md`) and is your base. Read `PROJECT.md`, your input `docs/track-record/runs/TR1d/review-report.md`
fully, `runs/TR1d/{build-brief,build-report}.md`, and spec `docs/track-record/spec.md` row **P16b (amended)**. Pattern for a separate small state
file: `server/internal/stocks/levels.go` (`stocks-levels.json`, kept apart so `stocks.json` stays byte-compatible).

## Do
1. **MAJOR (rollback).** Move the failure dates out of `stocks-verdicts.json` into their own `stocks-track-failures.json` (versioned, written
   atomically, an unusable file logged and treated as "no failures", never overwritten while unusable). `stocks-verdicts.json` must be exactly
   the c74df38 version-1 shape again: test that a file written by the new code decodes with the c74df38 strict decoder (copy that decoder's
   rules into the test, or decode with `DisallowUnknownFields` against the v1 struct). A `failures` field left by the TR1d build in an existing
   verdicts file is read once, moved to the new file, and dropped on the next verdicts write.
2. **Entry removal.** Remove a code's entry when it scores again OR when its records are given up.
3. **Hard limit (P16b amended).** Applies only when the code also failed on an earlier trading day; test the reviewer's 7-weeks-away probe
   (one 403 on the first run gives up nothing).
4. **Surviving mutants:** last failure date never advancing; hard limit 29/31 (exact boundary test at 30); reset on success removed — one
   test each that fails on the mutant (show it on a scratch copy).
5. NITs: rename so `giveUp`'s recording of the failure is visible (or split it), a test for reading a newer-version failures file (left
   untouched), reword §20.5 "a bad body". Update §20.5 for items 1-3.

## Hard rules
Files: `server/**`, `docs/protocol.md`, `docs/track-record/runs/TR1e/build-report.md`. Not yours: `android/**` (TR2b runs in parallel: never touch
it, never run Gradle), `CHANGELOG.md`, other docs. No new Go modules. Fakes only: never the real Yahoo, Hermes, port 8760 or state dir. No commits;
never `git checkout/restore/stash/reset`. RED first on a scratch copy. Avoid `bash -c`/heredocs/chained `$(...)`: script files + plain `bash`.
Gate after your LAST edit: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/track-record/runs/TR1e` exits 0 and
`cd server && go test -race -count=3 ./internal/stocks/...` ok.

## Report
`docs/track-record/runs/TR1e/build-report.md`, ≤ 12 lines.
