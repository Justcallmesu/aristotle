# UP2c focused re-review (fresh reviewer, deep)

## Verified
- **MAJOR (stale-session Aborted) is closed.** `acceptsStatus` (AppUpdates.kt:282) requires the status's id to equal the in-memory `session` and the row to be Installing/Confirming. `installFinished` checks it again, and the receiver (AppInstaller.kt:161) drops a stale status before it stores `PendingConfirm`. `run` clears `session` before `install` (:369), and `onSession` sets it before `commit` (AppInstaller.kt:79). So an Aborted from the `mySessions` cleanup (AppInstaller.kt:72) is dropped whether it arrives before or after the new id is known. The cleanup cannot abandon the current session, because it runs before `createSession`. `giveUp` nulls `session` and then abandons it (:350-353). AOSP fills `EXTRA_SESSION_ID` on these statuses, and a missing id (-1) is dropped. The only caller of the changed `UpdateDevice` API is AppContainer.kt:273, plus the test fake.
- **UP2b probe re-run on the new API** (scratch copy, `Up2cProbeTest`): give-up, then Try again, with the stale Aborted sent before and after `onSession`. Result: `step=Confirming confirms=1 abandoned=[old]` in both cases (it was `Failed(InstallAborted) confirms=0`).
- **Process death:** the new instance drops the old session's PendingUserAction. On retry, the stale Aborted is dropped and the new confirmation is reached. All 65 `data.update` tests pass on the copy (AppUpdatesTest 30/30).
- **MINOR is closed.** `lastCheck` is stamped only when the result is not Failed (:218). `AppUpdateRules.check` gives Available or UpToDate only for a valid 200 or a 204, and a cancelled check never reaches the stamp. Two tests cover it, plus the existing reconnect-loop test.
- **NITs are closed.** The KDoc now says "from the first" (AppInstaller.kt:28). A late PendingUserAction after the give-up is not stored, because the receiver returns before :168.
- **Mutation guard:** the PM's A1 mutant (any session accepted) is killed by the 2 stale-status tests (pm-gate-up2c/pm-check.log).

## Findings
- NIT AppUpdates.kt:192/:316/:348: the stored `update_install_session_id` is written but never read. `start()` deletes it, and after a process death the null in-memory `session` is what drops old statuses (correct, but the store key is dead state). Either read it in `start()` to abandon that session, or drop the key.
- NIT AppUpdates.kt:284-291 vs :344-355: check-then-act race. The receiver runs on main and the timeout `giveUp` on `linkScope` (Default). In a forced-simultaneous probe, 5 of 300 runs ended in Confirming on the session `giveUp` had just abandoned. It recovers through the re-armed 2-min deadline, and the same shape was already in UP2b. Fix with one lock around the accept-and-transition, if wanted.
- NIT AppInstaller.kt:161-169: nothing guards the receiver's order (check before `PendingConfirm.screen = …`). AppUpdatesTest:514 passes even under the A1 mutant, because the Failed step alone rejects the status. This is the test admitted to pass before the fix, and no other test guards that NIT. The code is correct today.
- NIT AppUpdates.kt:276-280: `installFinished`'s KDoc now sits orphaned above `acceptsStatus`'s KDoc. Also, AppInstaller.kt:149 still says "Every status goes to the About row".
- Open, out of scope: the install.sh version-code NIT (UP2b), and the HyperOS UP0 re-run with a "time out, then Try again" step.

VERDICT: APPROVED — 0 BLOCKER, 0 MAJOR, 0 MINOR, 4 NIT. The UP2b MAJOR, MINOR and both android NITs are closed; no regressions found.
