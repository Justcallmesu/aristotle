# T18b build report — cron minors

Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T18b` → exit 0 (PASS android, 566 tests).

- MINOR-1: `CronViewModel.onStop/onStart`; `CronScreen` wires ON_STOP/ON_START. Stopped = `cron.changed` ignored, an open window does not fire; ON_START lists at once (only if it was stopped).
- MINOR-2: `CronList.scope` (Hermes' `scoped` is the profile *name*, `methods_tools.py:1314`; `true` also accepted) → `CronUiState.unscoped` when profile ≠ null and no matching proof. Banner "This PC's Hermes can't manage jobs for this profile from the phone"; no arm keys, toggle/delete/create/newJob refuse, FAB hidden. List stays visible read-only.
- MINOR-3: `CronJob.legacyDelegated` = Desktop's `isLegacyDelegatedRoutine` (`[bot:x]` name + `You are running the scheduled routine "` prompt, `cron.tsx:75-103`). Switch off + disabled, never sent; page shows the Desktop's text "Paused for security: delete and recreate this legacy job…" (Delete still works).
- NITs: tz note "Hermes' timezone" (+ `(zone)` when the list carries top-level `timezone`; Hermes' list does not today); `CronSchedule.monthDay` keeps month-day to 1-31; `CronArm.createKey` includes the refusal text so Create re-arms when the refusal block moves it.
- Files: `data/cron/{CronFrames,CronSchedule}.kt`, `ui/cron/{CronViewModel,CronScreen,CronFormPage}.kt`, `CronViewModelTest`, `CronFramesTest`, `CronScheduleTest`, both `strings.xml` (4 keys appended each; old `cron_tz_note` now unused, kept because append-only).
- Tests vs old code: not run against it (scratch copy in `$TMPDIR/t18b-old`). The new tests call APIs the old code lacks (`onStop/onStart`, `unscoped`, `legacyDelegated`, `timezone`, `unscopedFor`, `monthDay`), so they fail to compile there; `createArmsAgainWhenARefusalMovesTheButton` and the new assertion in `createSendsOneAddAndShowsHermesOwnRefusal` would fail on old behaviour (second add sent at 750 ms).
- Existing `createSends…` test was changed: after a refusal the edit clears the error, so the button re-arms (timeline moved to 1249/1250).
- Not touched: T20 files. No commits, no install.
