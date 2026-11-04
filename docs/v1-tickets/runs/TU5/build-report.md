# TU5 build report — composer, slash menu, model sheet, attach menu, prompt sheet

Paths under `android/app/src/main/java/io/github/justcallmesu/aristotle/ui/chat/`.
- **G2** (40 dp buttons / 32 dp chips): already done by TU2 in `Composer`, `ModelSheet`, `PromptSheet`; nothing left in my files. `TouchTargetTest` covers it.
- **S1 / Q69** busy composer: `Composer.kt` `BusyActions`: Stop always; Queue, Steer and the hint sit in `AnimatedVisibility` (150 ms fade, hint also expands/shrinks) shown only while `ComposerLogic.showsBusyExtras(state)` (new, `ComposerState.kt`: draft non-blank **or** an attachment picked — Queue can carry attachments, so attachment-only counts; say if you want text only). Compact: field min 44 dp, field padding 8 dp, composer column gap 4 dp, busy row/hint top gap 8→4 dp.
- **S2** slash rows: `SlashMenuPanel.kt` padding 8 dp vertical, `Arrangement.Top`, description `maxLines = 1`, trailing "(usage: …)" removed for display (`SlashDescription.display`), max height = 4.5 × 52 dp.
- **S3** panel `surfaceContainerHigh`, shape `medium` (already).
- **S4** `ModelSheet.kt` effort `FlowRow` gets `verticalArrangement = spacedBy(8 dp)`.
- **S5** `ModelSheet.kt`: `rememberLazyListState` + `scrollToItem` on the current row when the catalog arrives (`ModelSheetRows.currentRow`, pure).
- **S6** `AttachmentViews.kt` attach `DropdownMenu`: `surfaceContainerHigh`, `medium` shape, 1 dp `outlineVariant` border, tonal 3 / shadow 6 dp, offset 8 dp up.
- **S7** / PromptSheet: no finding beyond G2 (done by TU2); Q41 layout untouched, so `PromptSheet.kt` has no edits.
- `ChatComposer.kt`: no change needed. No new tokens (private `FIELD_MIN_HEIGHT` 44 dp, `COMMAND_ROW_HEIGHT` 52 dp, `EXTRAS_FADE_MS` 150 in my files). strings.xml untouched.
- Tests: new `ui/chat/ComposerTu5Test` (busy extras, usage-tail stripping, model row index).
- Gate: see bottom line.
- Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TU5` → PASS android, exit 0 (earlier runs failed only from concurrent builds of TU3/TU4 sharing the build dir). Not installed on the phone; no visual check done.
