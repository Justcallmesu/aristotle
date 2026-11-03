# T17b build report — T17 fix round

**Gate.** `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T17b` → exit 0 (lint, 554 unit tests, 0 failures, assemble).

1. **MAJOR-1** `Delegation.callOf`: only empty/missing/`spawn` (trim, case-insensitive) fans out; `list`/`steer`/`stop` (`_CONTROL_ACTIONS`, `tools/delegate_tool_registry.py:198`) and any unknown value (error answer, `delegate_tool.py:565-568`) give no rows.
2. **MAJOR-2** `ChatSubagents.onStop()/onStart()` (via `ChatViewModel`, called from `ChatScreen` ON_STOP/ON_START). Both loops (`subagent.list` 5 s, `subagent.tail` 2 s) sleep while stopped; ON_START gives one immediate read, then the normal pace. `SubagentPacing` makes the intervals testable.
3. **Backoff** `SubagentLogic.tailDelay`: after a failed tail read the wait doubles 2→4→8→16→30 s (cap), resets on success.
4. **Interrupted** is no longer red: `DelegateStatus.red` is `Failed` only; Interrupted gets the neutral done icon and grey meta line, label "Stopped".
5. **Cites** `delegate-model.ts` → real lines (`24-30, 35-46, 55-61, 81-103, 129-156`) in `Delegation.kt`, `SubagentsTest.kt`, `docs/protocol.md` §2.2, `runs/T17/build-report.md`.

**Tests that fail against the old behaviour** (old logic put back temporarily in the new API shape; 6 of 33 in the two classes failed, then restored): `anExplicitSpawnActionIsAFanOutLikeNoAction`, `theRosterPollSleepsWhileTheAppIsStoppedAndReadsOnceWhenItIsBack`, `theTailPollSleepsWhileTheAppIsStoppedAndReadsOnceWhenItIsBack`, `aTailLoopWithTheLinkDownBacksOffInsteadOfRetryingAtThePace`, `theTailWaitDoublesFromTwoSecondsToAThirtySecondCap`, `onlyAFailedChildIsRedAStoppedOneIsNot`.

**Not verified.** The "fake lifecycle" is the VM's `onStop()/onStart()`; the `ChatScreen` wiring is not exercised on a device (nothing installed). Both use the screen's lifecycle owner, not `ProcessLifecycleOwner` (no new dependency). The icon change has no UI test.
