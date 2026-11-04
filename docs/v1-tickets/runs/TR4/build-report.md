# TR4 build report — floating composer

**Matches the board (§5.2, §10 TR4):** `surface` bar, padding 12/6/12/8; one `ComposerShape` field (`surfaceContainerHigh`) holding attachment chips, `BasicTextField` (1–6 lines), and an action row: attach (+, `ic_add`), model chip, spacer, trailing action. Chip = `AssistChip`, `small`, `surfaceContainerHighest`, no border, mono 13 (`toolSummary`), expand icon, max 250 dp. Send = `FilledIconButton` 40 dp `SquareIconButtonShape`; Stop = square `errorContainer`/`onErrorContainer`. Busy+empty → Stop only; busy+text/attachment (Q69, `showsBusyExtras` kept) → chip hides, row Stop · Queue (tonal) · Steer, fading hint under the field. Waiting chip (`errorContainer`, hand icon) above the field while a prompt waits; queued rows are `Surface(medium, surfaceContainerLow)`. Offline: field disabled, hint kept. Slash menu (`medium`, `surfaceContainerHigh`) and attachment chips (`surfaceContainerHighest`) unchanged in behaviour.

**Deviations:**
- Busy + `/command`: row is Stop · Send (command runs beside the turn, as before); no Queue/Steer.
- Chip / waiting chip need data from `ChatScreen.kt` (TR3's file). `Composer` got defaulted params `modelChip: String? = null`, `onOpenModels`, `onOpenPrompt`; **until ChatScreen passes them the chip is hidden and the waiting chip does nothing.** Wire: `modelChip = ComposerLogic.chipLabel(chat.model, effortLabel)`, `onOpenModels = models::open`, `onOpenPrompt = viewModel::openSheet`.
- Private `ComposerWaitingChip` (a `WaitingChip` already exists in ChatItems). No new theme tokens. Existing attach dropdown keeps its border/shadow (§5.2 silent on it).

**Files:** `ui/chat/Composer.kt`, `ComposerState.kt` (`ComposerTrailing`, `ComposerLogic.trailing/showsModelChip/chipLabel`), `AttachmentViews.kt` (attach icon → +); strings appended `composer_waiting_chip`, `composer_model_chip` (en + in). `SlashMenuPanel.kt`, `ChatComposer.kt`: untouched.

**Tests:** new `ComposerTr4Test` (trailing by state, chip visibility, chip label); `ComposerTu5Test` untouched.

**Gate:** full android gate: compiles, 895 tests, 3 failed. `TouchTargetTest` (my `.size(40.dp)` caps on the icon buttons) fixed, re-run green. 2 left are `ModelFilterTest` (TR5's `ModelSheet` work), not my files. Full gate not re-run after the fix (shared Gradle, ~15 min); lint/assemble not confirmed.
