# UP2b re-review (fresh reviewer, deep): UP1 + UP2 + UP2b updater diff

## Verified
- Read the full uncommitted diff, the UP2 review, the UP2b brief/report, spec Q132-Q134/P17-P20 and UP0. Took the gate from pm-check.log and did not re-run it. One probe ran in a scratch copy of android/ (below). The server diff in UP2b is one test rename, so I did not re-run Go.
- **MAJOR-1 is closed.** The permission is declared (AndroidManifest.xml:22) and tested. On AOSP `android14-release`, `PackageInstallerSession.computeUserActionRequirement` (USER_ACTION_NOT_REQUIRED && UPDATE_PACKAGES_WITHOUT_USER_ACTION && (installer of record / update owner || `isSelfUpdate`)) and `isTargetSdkConditionSatisfied` (SILENT_INSTALL_ALLOWED, target ≥ 31; ours is 36) are all met for a self-update, **including the first one** after an adb/USB install. Update ownership does not get in the way: no update owner is set, because nothing calls `setRequestUpdateOwnership`. Remaining limits: the `SilentUpdatePolicy` throttle (a repeat within the throttle window asks again) and HyperOS, which is still unobserved (UP0 re-run needed).
- **Attack 3 is OK.** The FGS docs ("Restrictions on starting a foreground service from the background", exemptions) list "receives … `ACTION_MY_PACKAGE_REPLACED` … in a broadcast receiver". The Android 14+/15 BOOT_COMPLETED type limits do not include `specialUse`. If the start is refused anyway, `ForegroundServiceStartNotAllowedException` (an IllegalStateException) is caught at BackgroundController.kt:77 and LinkService.kt:47, so there is no crash. `keepRunning` is true while credentials are Loading and the link is Idle. The receiver is not exported. The string is now true (EN+ID).
- **Earlier MINORs/NITs are closed:**
  - Double tap: Update claims the step atomically before launch.
  - `checking` is reset in `finally`.
  - A 10-min on-connect floor exists (see the MINOR below).
  - HashMismatch drops the offer and re-checks.
  - install.sh requires the APK in SHA256SUMS and an x.y.z name inside `verify_bundle`, before anything is touched.
  - The APK's versionCode must equal the announced one (WrongVersion).
  - The Go test is renamed.
- **Attack 4 (install.sh) is OK.** The app pair now goes first. If `install_binary` then fails, the old server serves the new pair: the phone ends up newer than the server, which P20 already covers, and the phone checks keep it safe. Odd names (`-rc1`, `v1.2.3`, an empty version) are refused before anything changes. The `${name//./\\.}` escape is correct. A second, unlisted APK is never copied.
- **Attack 5: no regressions.** AppUpdateHttp.kt was not changed in UP2b (pinned client, no redirects, NO_PROXY). The sha256 is taken over the same file that `inspect` and the session read. ApkCheck order: hash, package, signer, newer, then exact code.
- Attack 2, otherwise OK:
  - A late status after the give-up is ignored.
  - "Confirm install" with a stale intent is bounded: the 2-min deadline is re-armed on every show.
  - After process death, `PendingConfirm` is lost and the row shows Update again, so it does not stick.

## Findings
- **MAJOR** AppInstaller.kt:68 + AppInstaller.kt:146-155 + AppUpdates.kt:269-273: after a give-up or a process death, every retry fails.
  - **What happens:** the timeout / process-death path leaves the old committed session pending; giveUp (AppUpdates.kt:324) does not abandon it. The next attempt's `commit()` abandons it. AOSP `PackageInstallerSession.abandon()` (not in a terminal state) then calls `dispatchSessionFinished(INSTALL_FAILED_ABORTED)` and `sendUpdateToRemoteStatusReceiver`, so the OLD session's PendingIntent delivers STATUS_FAILURE_ABORTED to UpdateInstallReceiver. The receiver ignores `EXTRA_SESSION_ID`, and the new attempt is already `Installing` (set at :339 before `install`). The result is `giveUp(InstallAborted)`. The new session's PENDING_USER_ACTION then arrives in `Failed` and is ignored: no confirm screen, and another stale session for the next retry.
  - **Proven in a scratch probe:** a fake `install()` delivers the stale Aborted, then the new PendingUserAction arrives. Result: `step=Failed(InstallAborted) confirms=0`, where Confirming / 1 was expected.
  - **Impact:** on HyperOS, where a tap is likely, the updater is stuck on "Install cancelled" after its first 2-min timeout. This is the MAJOR-2 scenario, so MAJOR-2 is only partly closed.
  - **Fix:** hold the current session id (memory + store) and drop statuses whose `EXTRA_SESSION_ID` differs. Optionally also abandon the session in `giveUp`. Add a test for "stale Aborted during Installing".
- MINOR AppUpdates.kt:205 (with :187-191): `lastCheck` is stamped before the answer. A connect check that fails or is cancelled by a flap therefore suppresses the next connect check for 10 min, and that `collectLatest` branch then waits the full 6 h. One flap can hide an update for 6 h unless the user taps Check now. Fix: stamp only on Available/UpToDate, or pause only for the rest of the gap.
- NIT AppInstaller.kt:27-28: the KDoc says tap-less works "from the second self-update on (the app is then the installer of record)". AOSP's `isSelfUpdate` qualifies from the first one. The comment contradicts the build report and Q133's wording.
- NIT AppInstaller.kt:152: a PendingUserAction arriving after the give-up is still stored in `PendingConfirm` (harmless, stale). Folds into the session-id fix.
- NIT scripts/install.sh:99-104: `app_version_code` lets minor/patch ≥ 100 collide (1.100.0 = 2.0.0) and does not bound the digits. Same formula as Gradle; the phone's WrongVersion check makes it fail safe.
- Open (not code): Q133 on HyperOS is unobserved. Re-run the UP0 spike, and include a "let it time out, then Try again" step.

VERDICT: CHANGES REQUESTED — 0 BLOCKER, 1 MAJOR (AppInstaller.kt:68/146-155 + AppUpdates.kt:269: a stale-session Aborted status kills every retry after a give-up), 1 MINOR, 3 NIT. MAJOR-1 closed; MAJOR-2 partly closed; trust chain OK.
