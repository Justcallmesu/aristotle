# TV2 build report

Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TV2` exits 0 (PASS android; the APK holds `assets/changelog.md`). No mutation run, no device check.

- `CHANGELOG.md` (root): Keep a Changelog, `[Unreleased]` + `[1.0.0] - 2026-11-04`, Added/Security, one plain line each. Convention: a bullet starts `Phone:` (app) or `Server:`.
- `android/app/build.gradle.kts`: task `generateChangelog` (before `preBuild`) cuts CHANGELOG.md to `Phone:` bullets (tag stripped), drops Server bullets, `[Unreleased]` and empty sections, and fails the build if nothing is left. Output goes to `build/generated/changelog-assets/changelog.md` (same pattern as quotes), packed in the APK as `assets/changelog.md`; no copy lives in `src/`.
- `data/WhatsNew.kt` `WhatsNewCard` + `AppSettings.lastSeenVersionCode`: stored < current shows the card; nothing stored (fresh install) records the current code and shows nothing; dismiss/open records it.
- UI: `ui/settings/WhatsNewScreen.kt` (reads the asset, renders with `ChatMarkdown(streaming=false)`; chat code not edited), `ui/sessions/WhatsNewCard.kt` ("What's new in 1.0.0", Dismiss / See what's new), About row now opens the page. Wired in `AristotleApp.kt`, `SessionsScreen.kt` (new optional `whatsNew` slot), `AppContainer.kt`, `SettingsScreen.kt`.
- D48: `PcRecords.unpair` and a stored `replace` call `ServerVersions.clear()`; `AppContainer.forgetPc` does too. A cancelled re-pair keeps it.
- Tests: `WhatsNewCardTest` (fresh install, update, dismiss, damaged value, CHANGELOG has Unreleased + current version + a Phone bullet), `PcRecordsTest` +2 (clear on unpair/re-pair; kept on cancelled re-pair).
- strings.xml: 5 appended to values/ and values-in/ (Indonesian text is mine; worth a native read).
- Limits: a stored version above the current build (downgrade) shows no card; an existing pre-TV2 install has nothing stored, so it counts as fresh and sees no card for 1.0.0.
