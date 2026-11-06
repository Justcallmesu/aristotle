# TR2b — review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `256b80e` (TR2 committed). Review the uncommitted PHONE diff (`git status --short --
android`): TR2b. Server changes in the tree are another ticket: out of scope. Inputs: `docs/track-record/runs/TR2/review-report.md` (MINOR 1-5 +
NITs this ticket fixes), `runs/TR2b/{build-brief,build-report}.md`, spec Q121-Q124, P14, protocol §20.5 (`git show HEAD:docs/protocol.md`).

## Already checked by the PM
`runs/pm-gate-tr2b` GREEN (lint, 1414 unit tests, assembleDebug). Mutations (`runs/pm-gate-tr2b/pm-check.log`): connecting shows offline ->
TrackRecordViewModelTest; model filter by raw id -> TrackRecordTest family test.

## Attack list
1. MINOR 1-5 closed in the code the screens run (follow composable -> pure function). `TrackSublineFitTest`: does it measure through the same
   path the screen uses (font file, app text-size setting, real width minus padding), or a simplified formula?
2. Connecting vs offline: same rule as the other stock pages (cite them); no flicker between Loading and the body on a reconnect.
3. Family filter: Haiku/unknown under Other; a filter chip for a family with zero records; Q124 card line unaffected by filters.
4. Card-line entry resets filters, Stocks ⋮ keeps them; the `AristotleApp.kt` source test is not a test that guards nothing.
5. Skipped NITs: agree or not. No regression of the TR2 review's verified list.

## Rules
Read-only: write ONLY `docs/track-record/runs/TR2b/review-report.md`. Gradle only on a copy in your scratch dir. No adb, no server; never touch
`server/**`. No git checkout/restore/stash/reset/commit. Avoid `bash -c`/heredocs. Report: Verified, Findings, `VERDICT: ...`. Under ~30 lines.
