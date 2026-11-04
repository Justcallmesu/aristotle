# TS3 build report: nested Settings (Q72)

- Settings now opens on a category list (icon, title, one-line summary, chevron, no headers). Each category opens its own sub-page with the back arrow. The sections are the existing composables, moved by dispatch only, so their behaviour is unchanged.
- Summaries: Connection "Connected · Wi-Fi|Tailscale" (the network shows only while the link is online; otherwise the state alone: Connecting… / Offline / …), Notifications On / Off / Some off, Appearance "<theme> · <size>", Language = chosen language, Security "App lock on · <timeout>" / "App lock off", About "Aristotle <versionName>". The pure rules are in `SettingsSummaries`.
- Navigation: `PairedPage` gained one page per category (saved across process death). Back goes to the list, the list goes to Sessions. Battery guide goes back to Notifications, What's new to About, and Pair again (Repair) to Connection. System back uses the same mapping.
- New files: `ui/settings/SettingsPage.kt`, `SettingsList.kt`, icons `ic_devices`, `ic_notifications`, `ic_palette`, `ic_language`, `ic_info` (Material Symbols Outlined 400). Strings were appended in `values` and `values-in`.
- Edited: `SettingsScreen.kt` (`page` and `onOpen` params, list), `AristotleApp.kt` (enum made `internal`, routes and `SettingsRoute` only).
- Tests: `SettingsSummariesTest` and `PairedPageTest` (back mapping).
- Gate `GATE_ONLY=android`: **not green, and I have no passing run**. The first run failed in `compileDebugUnitTestKotlin` with 2 errors, both in TL1e's `data/LanDiscoveryTest.kt:276-277` ("No value passed for parameter 'bind'"). Main compile passed. Lint then crashed inside the lint tool. My own files had no compile errors, but my tests have not run because test compilation stops on that file. A rerun timed out while TL1e's gate was using Gradle at the same time. Please rerun the gate once TL1e's tests compile.
- Left as is: each sub-page still shows its old section header, which repeats the top-bar title. `ConnectionSection`'s header is in TL1e's file, so I did not remove any for consistency.
- Needed from the ViewModel: nothing. The summaries use state it already exposes.
- Not checked on a device (no adb).
