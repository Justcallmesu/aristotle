# T13b build report
Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T13b` exits 0 (lint, 317 unit tests, assemble). Was 307 at T13. Android files only; nothing installed, no commits.
Not done: a red-before-fix run (no revert allowed), so the new tests were only seen passing.

1. **4409 backstop** (`LinkManager.standDown`): after the immediate triggers it now sleeps `LinkTimings.supersededRetryMillis` (60 s) and runs the next round. A repeated 4409 waits 60 s again. Test: `aSupersededSocketIsRetriedByTheSlowBackstop`.
2. **D35 steer rows**: `ChatItem.User.steer` is set from `display_kind == "steer"` (`HistoryMapper`). `withRunningPrompt` skips those rows when finding the last user row. Test: `aSteerRowAfterTheStoredPromptDoesNotShowThePromptTwice`.
3. **Steer retry**: `SendFailure.Refused` now carries the RPC code. A steer is retryable only on 4001 (`Outbox.REFUSED`), or when NotSent/TurnEnded. Test: `aSteerIsRetryableOnlyAfterHermesRefusal4001` (4001 yes, -32603 and 5000 no).
4. **NITs**
   - A dismissed "No answer" submit keeps its `submission_id` in `ComposerState.keptIds`. The same text sent again reuses it. Test: `aDismissedNoAnswerSubmitKeepsItsSubmissionIdForTheSameText`.
   - Draft pruning: `ChatDrafts.prune`, `KeyValueStore.keys()`, called from `SessionsViewModel` on each `session.list`. Test: `ChatDraftsTest`.
   - EN `offline_cause_superseded` now says "PC".
   - Stop with unknown generation shows the "Can't stop yet" snackbar (EN and ID strings). Test: `aStopWithoutAKnownGenerationSaysSo`.
5. **D38**
   - Confirm step: Deny / Allow once are not rendered while confirming (`PromptSheet.ApprovalBody`). UI only, no test.
   - Re-arm on resume: `ChatViewModel.onResume()`, called from `ChatScreen` on `ON_RESUME`. It restarts both the sheet and the composer arm. Tests: `resumingTheActivityStartsThePromptArmingAgain`, `resumingTheActivityStartsTheComposerArmingAgain`.
   - `OpenSession.read(flags=…)` ignores `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`. `MainActivity` passes `intent.flags`. Test: `anIntentRedeliveredFromRecentsIsIgnored`.
   - Post-reconnect sudo/secret: `ChatFeedState.confirmedGeneration` is set when a snapshot or catch-up lands. `ChatUiState.answerable()` requires it to equal the live link generation. `ChatViewModel.armed` and the sheet buttons use it. Test: `aSudoPromptIsNotAnswerableUntilTheNewSnapshotConfirmsIt`.

Follow-up: draft pruning
- Prune only by age: drafts untouched for 30 days are removed, regardless of session list membership (injectable clock).
- Tests added: recent draft missing from list survives; drafts older than 30 days are pruned.
- SessionsViewModel.onFeed now calls `prune()` without arguments on each `session.list` event.

Open:
- `onResume` also fires on the first resume after the screen opens. That only adds the normal 600 ms arm.
- Phone QA is still owed for the resume re-arm and the recents relaunch.
