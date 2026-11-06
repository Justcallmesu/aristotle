# TR1 + TR1b — re-review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`. Review the WHOLE uncommitted server diff on top of `06a6723`
(TR1 + fix round TR1b): `git status --short -- server docs/protocol.md`. Inputs: `docs/track-record/spec.md` (Q116-Q125, P10-P16),
`runs/TR1/{build-report,review-report}.md` (the first review; what it verified stands unless TR1b touched it), `runs/TR1b/{build-brief,
build-report}.md`, `docs/protocol.md` §20.2 + §20.5.

## Already checked by the PM
`runs/pm-gate-tr1b` GREEN (737 Go), `-race` ok (stocks + httpapi). Mutations (`runs/TR1b/pm-check.log`): any 4xx falls back to daily ->
TestTrackJobFallsBackOnlyWhenTooOld; fill only inside the zone (Q125) -> 3 table rows; never give up -> TestTrackJobGivesUpAfterTenTradingDays.
Earlier (TR1): look-ahead, target-before-stop, stop in the fill bar -> killed.

## Attack list
1. First-review MAJOR 1-3 and every MINOR closed in the code that runs (not only in a helper).
2. Q125 fill: walk gap-down cases by hand (below zone above stop; below stop; gap down then rally through target; daily fallback) and
   check the result % base stays `entry_high`.
3. Builder decisions: a 404 for a code Yahoo has daily bars of now waits like other failures (an extra daily request for a truly unknown
   code); split log repeats every run until the 30-day cap; `unscorable` counted in group `n` (phone must exclude it from rates — is §20.5
   unambiguous for the phone?).
4. The new `After` ordering in the scheduler (`scheduler.go` ~:434): can it deadlock, starve `track.score` forever (bars.daily failing every
   day), or change any existing job's timing? Existing scheduler tests unchanged and meaningful?
5. `versioned.go` extraction: analyses behaviour byte-identical (newer version untouched, unusable kept in memory); history seed waits.
6. Yahoo budget across a bad week (blocks, 403s, 422s): bounded, no loop.
7. Tests that guard nothing; code shape per `PROJECT.md`.

## Rules
Read-only: write ONLY `docs/track-record/runs/TR1b/review-report.md`. Probes and mutations on a scratch COPY of `server/`. Never the real
Yahoo, Hermes, port 8760 or state dir. No git checkout/restore/stash/reset/commit. Avoid `bash -c`/heredocs: script files + plain `bash`.
Report: Verified (file:line), Findings (BLOCKER/MAJOR/MINOR/NIT + fix), `VERDICT: ...`. Under ~40 lines.
