# TR1d — review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`. Review the uncommitted SERVER diff on top of `c74df38`:
`git diff HEAD -- server docs/protocol.md` (TR1d). `android/**` is another ticket (TR2): out of scope. Inputs: `docs/track-record/spec.md`
P16, P16a, **P16b**, `runs/TR1c/review-report.md` (findings 1-3 + NIT this ticket fixes), `runs/TR1d/{build-brief,build-report}.md`, §20.5.

## Already checked by the PM
`runs/pm-gate-tr1d` GREEN (763 Go), `-race` ok. Mutations (`runs/TR1d/pm-check.log`): no 30-day hard limit -> 2 tests; failure runs not
saved -> TestTrackJobIsolatedFailuresNeverGiveUp.

## Attack list
1. Findings 1-3 + NIT closed in the running code; a daily-restart simulation (the TR1c reviewer's 79-run probe) now ends in `unscorable`
   within the hard limit, and two isolated failures weeks apart never give up.
2. **Scope widening:** `yahoo.go` `fetch` now reports a body-read error after a 200 as a network error for EVERY Yahoo caller (quotes,
   charts, bars job, analyzer, screener, news?). Trace each caller of `isNetwork`/`UpstreamError.Reason`: does any behaviour other than
   track.score change (a job stopping, a different `last_error`, backoff)? Is that acceptable or should it be scoped to track.score?
3. The additive `failures` field: an older binary reading the file (rollback), a corrupt entry, the version stays 1; growth bounded (entries
   removed when a code scores again or its records are given up).
4. The changed expectation in `TestTrackJobRecent422Waits` (a 52-day record now `unscorable`, status `partial`): correct per P16b?
5. Tests that guard nothing; code shape.

## Rules
Read-only: write ONLY `docs/track-record/runs/TR1d/review-report.md`. Probes on a scratch COPY of `server/`. Never the real Yahoo, Hermes,
port 8760 or state dir. Never Gradle (another job builds android). No git checkout/restore/stash/reset/commit. Avoid `bash -c`/heredocs.
Report: Verified, Findings (BLOCKER/MAJOR/MINOR/NIT + fix), `VERDICT: ...`. Under ~30 lines.
