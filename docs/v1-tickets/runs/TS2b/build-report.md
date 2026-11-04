# TS2b build report — app lock review minors

**Status:** built; `GATE_ONLY=android gates.sh runs/TS2b` PASS (exit 0). Not checked on a device (no adb).
- **Recents on API 29-32:** new `ui/lock/WindowSecure.kt`: `SecureOwners` (pure, reference-counted per owner) and `WindowSecure.acquire/release(window, owner)`. `MainActivity.onPause` takes a "paused" hold when the lock is on and SDK < 33; `onResume` releases it. Lock screen, prompt sheet and Terminal now use the shared `SecureWhileShown()`, so none clears another's flag (their private copies are removed from `PromptSheet.kt`, `TerminalScreen.kt`, `LockScreen.kt`).
- **Bounded auth exemption:** `BiometricHost.authenticate` returns `Failed` unless the activity is RESUMED and `!supportFragmentManager.isStateSaved`. `AppLock.onStopped` always records the stop time; `authEnded(passed)` clears it only when the check passed. A return during a check is judged when the check ends (a cancel/abandon locks if the time from the stop reached the timeout). `AuthBroker` passes `passed`.
- **Multi-window:** `MainActivity` attaches the host in `onResume` (not `onCreate`) and detaches in `onDestroy`; `AuthBroker` keeps a list of hosts (newest asks), so a closed second window removes only itself and the first still unlocks.
- **Tests:** `AppLockTest` +4 (passed check before/after return, cancelled check counts from the stop, never-ending check, second window), 2 existing updated; new `SecureOwnersTest` (3).
- **Not done:** HyperOS recents behaviour on 33+ (device check); the lock screen layout decision (PM's).
- **Files:** `MainActivity.kt`, `data/AppLock.kt`, `data/FreshAuth.kt`, `ui/lock/{BiometricHost,LockScreen,WindowSecure}.kt`, `ui/chat/PromptSheet.kt`, `ui/terminal/TerminalScreen.kt`, `AppLockTest.kt`, `SecureOwnersTest.kt`.
