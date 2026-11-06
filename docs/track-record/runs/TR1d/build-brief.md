# TR1d — server minors from the TR1c final review

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `c74df38`. Read `PROJECT.md`, `docs/track-record/runs/TR1c/review-report.md`
(findings 1-3 + NIT), spec `docs/track-record/spec.md` P16, P16a, and the code `server/internal/stocks/track.go` (`failStreaks` ~:296,
`giveUp` ~:277, `tooOld` ~:132).

## Do
1. **Bounded give-up across restarts.** Persist each failing code's first-failure date AND last-failure date in the history state
   (`stocks-verdicts.json`, additive field; an older file without it reads as "no failures"), so a daily restart neither resets nor bypasses the
   P16a rule. A failure run restarts when the last failure is older than the previous trading day (two isolated failures weeks apart never
   give up). Hard limit: any record still unscored 30 trading days after its session is `unscorable` regardless (logged).
2. **Test gap:** a 403 on a 52-day-old window waits (no daily fallback); it must FAIL if `tooOld` accepts any status ≥ 400.
3. NIT: a body-read error after a 200 counts as a network error (stops the run), not a code failure; test.
4. Update `docs/protocol.md` §20.5 for 1 and 3; add the P16b line text to your report for the PM to put in the spec.

## Hard rules
Files: `server/**`, `docs/protocol.md`, `docs/track-record/runs/TR1d/build-report.md`. Not yours: `android/**` (TR2 builds the phone in parallel:
never touch it, never run Gradle), `CHANGELOG.md`, other docs. No new Go modules. Fakes only: never the real Yahoo, Hermes, port 8760 or state
dir. No commits; never `git checkout/restore/stash/reset`. RED first on a scratch copy. Avoid `bash -c`/heredocs/chained `$(...)`: script files
+ plain `bash <file>`. Gate after your LAST edit: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/track-record/runs/TR1d` exits 0 and
`cd server && go test -race -count=3 ./internal/stocks/...` ok.

## Report
`docs/track-record/runs/TR1d/build-report.md`, ≤ 10 lines.
