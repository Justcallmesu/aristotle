# TR1 + TR1b + TR1c — final review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`. Review the WHOLE uncommitted server diff on top of `06a6723`
(TR1 + fix rounds TR1b, TR1c): `git status --short -- server docs/protocol.md`. This is after fix round 2 of 2: a CHANGES REQUESTED now goes
to the user, so separate real BLOCKER/MAJOR (a wrong score the user sees, frozen bad data, unbounded Yahoo use, a crash) from deferrable items.

Inputs: `docs/track-record/spec.md` (Q116-Q125, P10-P16a), `runs/TR1/review-report.md`, `runs/TR1b/review-report.md` (the findings TR1c
fixes), `runs/TR1c/{build-brief,build-report}.md`, `docs/protocol.md` §20.2 + §20.5.

## Already checked by the PM
`runs/pm-gate-tr1c` GREEN (757 Go), `-race` ok (stocks + httpapi). Mutations (`runs/TR1c/pm-check.log`): network error does not stop the
run -> 3 tests; give up on the first failing run -> 3 tests; recent-window 422 falls back to daily -> 2 tests. Earlier rounds: 6 more killed.

## Attack list
1. TR1b review MAJOR 1 and its MINORs closed in the code that runs; the reviewer's 3-weeks-offline probe now gives up nothing.
2. `failStreaks` lives in memory (restart only delays a give-up): can a restart pattern (PC sleeps nightly, server restarts) mean a code that
   always fails is never given up and asked forever? Bounded or not?
3. The 422 rule: an old-only window falls back, a recent one waits, a mixed one retries without old sessions — walk each.
4. §20.5 "decided" definition matches P16a and Q122; the phone can implement Q122-Q124 from the documented fields alone.
5. No regression in what the first two reviews verified (scorer truth, look-ahead, history integrity, endpoint, job registration).

## Rules
Read-only: write ONLY `docs/track-record/runs/TR1c/review-report.md`. Probes and mutations on a scratch COPY of `server/`. Never the real
Yahoo, Hermes, port 8760 or state dir. No git checkout/restore/stash/reset/commit. Avoid `bash -c`/heredocs: script files + plain `bash`.
Report: Verified (file:line), Findings (BLOCKER/MAJOR/MINOR/NIT + fix), `VERDICT: ...`. Under ~35 lines.
