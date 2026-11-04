# TR1 — v2 theme foundation: Soft shapes, grouped rows, slider (Q74, Q75)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/design/v2/design-notes.md` (§3 shapes — the **Soft**
column; §2 grouped rows; slider colours; everything marked TR1 in §10), `docs/spec.md` Q74, Q75, `docs/design.md`, then
`android/app/src/main/java/io/github/justcallmesu/aristotle/ui/theme/**` and `ui/components/**`.

## Do
1. `Shape.kt` -> the Soft scale (every token in the Soft column) + the three custom shapes the notes define (names as in the notes).
2. A reusable grouped-row helper in `ui/components/` (a section = column of `Surface` rows 2 dp apart; outer corners `large`, inner
   `extraSmall`; first/middle/last/single shapes as a pure function, tested).
3. Slider colours/sizes per the notes (a small wrapper `AristotleSlider` in `ui/components/` with stepped values, level-name label and
   a callback fired once per gesture end — tested as pure logic where possible).
4. `docs/design.md`: replace the Q39 radii with the Soft scale; add grouped rows and the slider; reference `docs/design/v2/`.
Screens are NOT migrated here (TR2-TR6 do that), except anything that breaks visually only because of the new radii — list those.

## Hard rules
Files: `ui/theme/**`, `ui/components/**` (new files + `SubPageTopBar.kt` untouched unless a radius), `docs/design.md`, tests,
`docs/v1-tickets/runs/TR1/build-report.md`. A design worker writes `docs/design/v2/**` now: read it, don't edit it. No commits; never git
checkout/restore/stash/reset. No adb.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TR1` exits 0. Report ≤12 lines.
