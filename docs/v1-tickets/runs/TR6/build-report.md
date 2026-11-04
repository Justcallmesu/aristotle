# TR6 build report — Settings + prompt sheets

Matches the board (design-notes §5.4/§5.5):
- Settings list: `LargeTopAppBar` (exit-until-collapsed, title in the theme's headlineMedium/titleLarge slots), three `GroupedRows` blocks (`settingsBlocks`), 64 dp rows, transparent `ListItem` in each row `Surface`.
- Every sub-page (Notifications, Appearance, Language, Security, About, Connection) is `RowGroup` = `GroupedRows`; Connection: PC (titleMedium) + fingerprint block, "Saved addresses" rows (mono 13 via `codeInline`, delete `IconButton`, only-address stays undeletable), Find PC `FilledTonalButton` + live-region line, history / keep-connected / pair again / Unpair (`error` title only). Connection history list = grouped rows (LazyColumn, `groupedRowShape`).
- Prompt sheet: clarify choice rows (and the Settings pickers) are grouped rows; text fields `FieldShape`; sudo/secret field in a `FieldShape` container; unpair command block `shapes.medium`. Q41 order, 600 ms arm, `SecureOn` / `SecureWhileShown`, fresh-auth gates untouched (logic not edited). `LockScreen.kt`: already `ButtonShape`, no change.

Deviations:
- Sub-pages keep `SubPageTopBar` (components/ is off-limits), not a new `TopAppBar`.
- Sheet rows use `surfaceContainerHigh` (private choice in `SheetChoiceGroup`), since the sheet is already `surfaceContainerLow`; `GroupedRows` would be invisible there.
- Secret `EditText` background removed (container supplies the field look); still a platform view.
- Battery guide / What's new are prose pages, left as is. No strings, no new tokens.

Files: `ui/settings/{SettingsScreen,SettingsList,SettingsConnection,SettingsPage,SettingsSheets,ConnectionHistoryScreen}.kt`, `ui/chat/PromptSheet.kt`; test `ui/settings/SettingsBlocksTest.kt` (blocks cover each page once). Composables not UI-tested.
Gate: FAIL, none in my files. Kotlin compiles; 895 tests, 3 failed: `ModelFilterTest` x2 (TR5) and `TouchTargetTest` (TR4 `Composer.kt:273/281` `FilledIconButton` capped by `Modifier.size`). `lintAnalyzeDebug` crashed inside lint while analysing `Composer.kt` (lint bug). Rerun once TR4/TR5 settle.
