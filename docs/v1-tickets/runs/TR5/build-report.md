# TR5 build report — model sheet

Gate: `GATE_ONLY=android gates.sh runs/TR5` last full run FAIL: 4 tests, 2 of mine (test-data bugs, since fixed) + `TouchTargetTest` (Composer.kt:273/281 `Modifier.size(SQUARE_BUTTON)`, TR4's file) + `RepairTest:137` (not mine). Earlier runs also hit other workers' compile errors and shared-Gradle collisions. After the fix, `--tests '*ModelFilterTest' '*ComposerTu5Test'` passes (exit 0); full gate not re-run (15 min).

Matches the board / notes §5.3:
- Head ("Model" + "Now: model · provider"), search field (`FieldShape`, "Search N models" from the catalog, clear button), `LazyColumn(weight 1f)`, `EffortFooter`.
- Local case-insensitive search across providers; hit providers open and show only hits, count "3 of 13", match underlined; no hit -> "No model matches “x”." The footer hides while the query is non-empty.
- Providers in Hermes' order: 56 dp header (name, "· 44 models", rotating chevron, open/closed `stateDescription`), only the session's provider open; user toggles flip that default; the provider `warning` shows when open.
- Model rows 48 dp mono; the current is a `secondaryContainer` `shapes.medium` row inset 12 dp with a trailing check; locked ones show "Needs a paid plan" and can't be picked; sending disables rows and slider.
- On open it scrolls by the least amount that reveals the current row (`ModelFilter.revealIndex`).
- Footer: divider, 24/12/16 padding, `AristotleSlider` with only the model's levels, ends labelled by the first/last level ("Off"/"Ultra" for a full set), note line ("This route sends X" or the running note). Hidden when there are no levels. `pickReasoning` is wired straight to the slider's `onPick`, which fires once per gesture end and only on change.

Deviations:
- "Reasoning effort" sits on its own line above the slider, not on one line with the level name, because the slider component draws the name itself and `ui/components/**` is off limits.
- Row text is `AristotleTextStyles.codeInline` (13 sp), not mono 14, to keep the existing token. Row height is `Sizes.minTouchTarget` (48 dp).
- The running note is in the head only when the footer isn't shown, so it never appears twice.
- `ModelSheetRows` (old index helper) is no longer used by the sheet; it stays because `ComposerTu5Test` tests it.
- Whether the search field matches home's is unchecked: TR2 builds that one at the same time.

Files: `ui/chat/ModelSheet.kt` (rewritten), `ui/chat/ModelFilter.kt` (new, pure), `test/.../ui/chat/ModelFilterTest.kt` (new). strings appended to values/ and values-in/: `model_search_hint`, `model_search_none`, `model_count`, `model_count_of`, `model_group_open`, `model_group_closed`. No new tokens.

Tests: `ModelFilterTest` covers default open provider, toggles, case-insensitive cross-provider search, no-hit, match range and count, current-row index, least-scroll reveal, and one commit per drag (via `sliderIndex`/`shouldCommit`). The composables are not UI-tested.
