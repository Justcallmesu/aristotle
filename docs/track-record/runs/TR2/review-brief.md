# TR2 — review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`. Review the uncommitted PHONE diff (`git status --short -- android`) on
top of `c74df38`. Contract: `docs/protocol.md` §20.5 as committed (`git show HEAD:docs/protocol.md`; an uncommitted server ticket TR1d is out
of scope). Inputs: `docs/track-record/spec.md` (Q117-Q125, P11, P14, P15, P16a "decided"), `runs/TR2/{build-brief,build-report}.md`, the design
board `docs/design/v3/track-record.html` + notes (recommended variants only, Q121).

## Already checked by the PM
`runs/pm-gate-tr2` GREEN (lint, 1406 unit tests, assembleDebug). Mutations (`runs/pm-gate-tr2/pm-check.log`): not triggered inside the buy
rate -> 4 TrackRecordTest cases; threshold 1 instead of 10 -> 4 cases.

## Attack list
1. Rates vs spec: every verdict type's "decided", numerator and threshold (Hold = win + right over win + loss + right; Avoid/Sell right over
   right + wrong; Wait no rate); filters sum groups correctly; averages weighted by `result_n`/`info_3d_n`; `unscorable`/`no_session` never in
   a rate. Is the screen wired to these pure functions, or does a composable recompute anything?
2. Builder deviations: "N verdicts scored · through <date>" subline; pending notice wording; Wait average also gated at 10; no saved copy (P14).
   Judge each against the spec/board.
3. Card line (Q124) on every verdict type, below threshold, and when the server has no track-record yet (older server: 404) — the verdict
   card must still render; the stock page must not break if the endpoint fails.
4. Past calls: `?code=` request lifecycle (no request storm on recomposition, cancelled on leave), collapse/expand, the daily marker.
5. Navigation: Stocks ⋮ and card line into the page via the back stack; Back unwinds with no loop (`PairedPageTest` count change 8 -> 9:
   justified?).
6. Strings EN + ID, no hardcoded text, text that fits at 1.3x (the builder could not run a device: judge from the layout code and say what
   must be a device check).
7. Code shape per `PROJECT.md`; tests that guard nothing.

## Rules
Read-only: write ONLY `docs/track-record/runs/TR2/review-report.md`. Gradle only on a copy in your scratch dir. No adb, no server. Never touch
`server/**`. No git checkout/restore/stash/reset/commit. Avoid `bash -c`/heredocs: script files + plain `bash`.
Report: Verified (file:line), Findings (BLOCKER/MAJOR/MINOR/NIT + fix), `VERDICT: ...`. Under ~35 lines.
