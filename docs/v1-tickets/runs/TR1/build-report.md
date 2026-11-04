# TR1 build report

Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TR1` -> PASS (exit 0; server SKIP).
- `ui/theme/Shape.kt`: Soft scale (4/12/16/20/28), `ButtonShape` 12, new `FieldShape` 16, `ComposerShape` 24 (all corners), `SquareIconButtonShape` 12.
- `ui/components/GroupedRows.kt`: `GroupedRows(count) { index -> }` (surfaceContainerLow `Surface` rows, 2 dp gap), pure `groupedRowPosition`, `groupedCornerDp`, `groupedRowShape` (outer 20 / inner 4).
- `ui/components/AristotleSlider.kt`: generic `AristotleSlider(levels, current, label, onPick, ...)`: notes' colours, level name above, stateDescription, haptic tick, `onPick` once per gesture end and only on change; pure `sliderIndex`, `sliderSteps`, `shouldCommit`.
- Tests: `ui/components/GroupedRowsTest.kt` (positions, corners, slider logic). The composable itself is not UI-tested.
- `docs/design.md` §3 rewritten (Soft table, new shapes, grouped rows, slider); §4 elevation and §7 pill line adjusted.
- Visual effects of new radii, left for later tickets: `ChatItems.kt:260` user bubble hard-codes 14 dp corners (-> `shapes.large`, TR3); `Composer.kt:131` hard-codes 24 dp top corners (-> `ComposerShape`, TR4); `ChatMarkdown.kt` `CODE_CORNER` constant (check vs `small` 12, TR3). Tokens and `ButtonShape` users pick up new radii automatically. No screens migrated; no commits.
