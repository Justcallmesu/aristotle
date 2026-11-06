# TR2b — phone minors from the TR2 review

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `256b80e` (TR2 committed). Read `PROJECT.md`, your input
`docs/track-record/runs/TR2/review-report.md` (MINOR 1-5 + NITs, file:line), `runs/TR2/build-report.md`, spec `docs/track-record/spec.md` Q121-Q124,
P14, and the design board notes `docs/design/v3/track-record-notes.md`. Read the real code before editing.

## Do
1. **Subline at 1.3x** (`StocksPages.kt` ~:86 one-line top bar): the "N verdicts scored · through <date>" line must not be cut: move it out of
   the top bar into the page body (or allow two lines there only for this page); a test that measures through the real text path at 1.3x
   (pattern: the chart label tests) proves it fits at 393 dp.
2. **False offline** (`TrackRecordViewModel.kt` ~:67): while the link is connecting show the loading state, "PC out of reach" only when the
   link is really down (same rule the other stock pages use: copy it, do not invent one). Test.
3. **Model filter by family:** one chip per family (Opus / Sonnet / other), summing every model id of that family; pure function + test.
4. **`rateWords`:** the screen uses the pure card-line rule (no second copy of the rule in a composable); test.
5. **Filters from a card line:** opening the page from a verdict card resets the filters (so the numbers match the line); from Stocks ⋮ the
   last filters stay. Test.
6. NITs from the report that are one-liners; list any you skip and why.

## Hard rules
Files: `android/**`, `docs/track-record/runs/TR2b/build-report.md`. Not yours: `server/**` (TR1e runs in parallel: never touch it, never run Go),
docs, `CHANGELOG.md`. No new libraries. Strings append-only EN + ID. No adb, no server. No commits; never `git checkout/restore/stash/reset`.
Gradle `--tests` filters match CLASS names. RED first where the API exists. Avoid `bash -c`/heredocs/chained `$(...)`: script files + plain `bash`.
Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/track-record/runs/TR2b` exits 0.

## Report
`docs/track-record/runs/TR2b/build-report.md`, ≤ 12 lines.
