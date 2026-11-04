# TS1 review — Settings (re-pair, unpair, appearance, language, notifications)

Scope: `git diff HEAD -- android` + untracked android files, PROJECT.md, spec Q25/Q49/Q60, design.md §1 (PM edit), build brief/report. Static read only (no gradle, no device). PM gate GREEN (639) taken as given.

**Checked OK**
- Re-pair is atomic: `CredentialStore.kt:70-76` seals first (a Keystore throw writes nothing), then new entry → `active_pc` → old entry removed, each a `commit`. `PairViewModel.kt:247` catches the throw and the old pairing stays (`PcRecords.kt:27-30`). Process death at any step leaves either the old or the new pairing active, never none. `pcId` is the per-pairing device id (`PcCredentials.kt:11-12`), so `rememberSaveable(pc.pcId)` drops the Repair page once the save lands. The link ignores `remember()` for the removed id (`CredentialStore.kt:93`).
- Unpair (`AppContainer.kt:183-187` → `PcRecords.kt:16-20`) clears the entry and active pointer (token, pin, addresses), the profile, every pin for the PC and all drafts. Unpaired then stops the link (`LinkManager.kt:124-127`) and the service (`BackgroundRules.kt:14`). No terminal can be open at that point: it is cleared on leaving its page (`AristotleApp.kt:313`), and Settings is only reachable from Sessions.
- Revoke command `aristotle-server devices revoke <deviceId>` (`SettingsModel.kt:33`) matches `server/internal/app/cli.go:138`. The token is never shown.
- Language cannot loop or crash. 33+: `LocaleManager` is set once and the platform recreates the activity; setting the same value is a no-op. Below 33: the in-memory StateFlow is set before `recreate()`, and `attachBaseContext` reads that value (`AppLocales.kt:33-42`, `MainActivity.kt:25-27`). `in`/`id` are both mapped (`AppSettings.kt`, `ofLanguage`). Manifest has no `configChanges`. `locales_config.xml` is present.
- Notification channel ids are unchanged (`link`/`turn_finished`/`needs_you`), with the same importances and badge rule (`NotifyChannels.kt:12-18` vs HEAD `Notifier.kt:35-37,165-173`).
- EN/ID string sets match (0 missing, 0 extra). No new colours: the destructive outline uses `error`, as on Scheduled.

**Findings**
- MINOR: the 11 sp floor (design.md §1 "no text renders below 11 sp (clamp)") is not enforced. `TextSize.Small` is 0.9 (`AppSettings.kt:27`), which puts `labelSmall`'s 11 sp at 9.9 sp. The comment at `AppSettings.kt:25` also contradicts design.md. Fix: clamp the scaled size to at least 11 sp, e.g. scale the Typography/`AristotleTextStyles` with `max(11.sp)` instead of `fontScale`.
- MINOR (needs a device check): text size is applied by overriding `LocalDensity` (`Theme.kt:25-27`). Dialog, ModalBottomSheet and DropdownMenu windows build their own ComposeView, which provides `LocalDensity` again from the window, so the unpair dialog, choice sheets, profile/prompt/model sheets and menus probably ignore Small/Large. Scaling the typography (finding above) fixes both.
- MINOR: Back or the arrow during a busy re-pair does not cancel it. `PairScreen.kt:101` allows back while busy, and the repair ViewModel stays in `routes` (Settings has no `RouteScope`, `AristotleApp.kt:237`), so a late success still swaps the pairing. That contradicts "Back cancels and changes nothing" (`AristotleApp.kt:88-91`). Fix: disable back/arrow while `busy`, or clear `routes` on cancel.
- MINOR: process death mid-`save(replacing=)` can orphan an entry. After `active_pc` is written, the old sealed token's `pc.<old>` entry and its profile/pins keys can be left behind; a later Unpair (`wipeActive`) never removes them. Use one `edit{}.commit()` for put/put/remove (`CredentialStore.kt:72-74`).
- MINOR: Unpair wipes credentials first and only then clears drafts/pins/profile through `apply()` stores (`PcRecords.kt:17-19`, `AppSettings.kt:121-122`). A kill in between keeps drafts, which return if the same PC is re-paired with the same session ids. Clear them before `wipeActive`.
- NOTE: below 33, the Application context isn't wrapped, so notification texts and channel names stay in the phone's language (the build report only mentions channel names). Notifications already posted for the old PC stay in the tray after Unpair (same as the existing forget path). The launch window still flashes with a forced theme (as reported).

No BLOCKER/MAJOR.

VERDICT: PASS-WITH-FINDINGS
