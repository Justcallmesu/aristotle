# TS2 build report — app lock + hide-terminal switch (Q61, Q62), with TS1b

**Status:** built; android gate GREEN (707 tests, 0 failures, no new lint warnings).
**App lock (Q61)**
- Dependencies: `androidx.biometric:biometric:1.1.0` (newest stable) and `androidx.fragment:fragment:1.9.1`, because biometric's own fragment 1.2.5 is older than activity 1.13. No AppCompat theme or activity is needed. `MainActivity` is now a `FragmentActivity` and builds `BiometricHost` in `onCreate`. The prompt allows `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`; API 29 uses `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`, because androidx can't pair STRONG with a credential below API 30.
- `data/AppLock.kt` (pure): timeout Immediately/1/5/15 min, default 1. The app starts locked whenever the lock is on, so process death counts as locked. Coming back after at least the timeout in the background locks it. These don't count as time away: time on screen, a rotation, another activity of the app, or the PIN screen during the app's own check.
- `data/FreshAuth.kt`: `AuthBroker` runs one check at a time, answers no when no activity is attached, and a pass also unlocks.
- `ui/lock/LockScreen.kt`: palette surface, the app icon tile, and an Unlock button (the check runs only when tapped); FLAG_SECURE while shown. Nothing else is composed underneath. Page and chat state are kept in a `SaveableStateHolder`, and the chat/cron ViewModels are kept while locked. On Android 13+ the recents thumbnail is also turned off while the lock is on.
- Settings › Security has the **App lock** switch, a **Lock after** sheet and a **Show Terminal on this phone** switch. App lock moves only after a passed check, both on and off; off without a check would bypass the gates below. With no screen lock set, it explains why and stays off.
- Fresh check before opening the Terminal (`TerminalEntry.open`), and before the "Always allow" confirm and sudo/secret Send (`ChatViewModel.afterCheck`). The 600 ms arm is kept. After the check, the prompt must still be on screen and answerable. A failure or cancel sends nothing and leaves the typed value in the field. Buttons are off during the check; Decline needs no check. With the lock on, leaving the app from the Terminal page also closes that page.
**Hide Terminal (Q62).** `TerminalEntry.shown(serverAllows, showOnPhone)` drives the ⋮ menu. A Terminal page restored while the switch is off falls back to Sessions.
**TS1b**
- Back or the arrow on re-pair calls `PairViewModel.cancel()`. `CredentialStore.save(commitIf=)` re-checks the cancel under the store lock, so a late success writes nothing.
- `KeyValueStore.edit`: save, replace and wipe are each one commit. Unpair commits profile, pins and drafts to disk before wiping credentials.
- Unpair, and revoke, reset `AlertRouter` and remove the old PC's session notifications from the tray.
**Tests (38 new):** `AppLockTest` 16, `ChatViewModelLockTest` 6 (fake authenticator), `TerminalEntryTest` 4, `SecuritySettingsTest` 6, `PcRecordsOrderTest` 6, `RepairTest` +2.
**Notes**
- Not checked on a device (no adb). If the phone's screen lock is removed, the app lock turns itself off at the next check instead of locking the user out; removing a screen lock needs the PIN.
- The lock screen is a centred icon stack; design.md §7 only allows that for the quote screen. "Immediately" also locks on return from the QR scanner or a file picker; picked results are kept.
- `ui/chat/ChatText.kt` was changed by another worker, not TS2.
