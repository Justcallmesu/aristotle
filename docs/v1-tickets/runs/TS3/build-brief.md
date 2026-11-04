# TS3 — nested Settings (category list -> sub-pages) (Q72)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q60-Q62, **Q72**, `docs/design.md` (§4 rows,
§5), then `ui/settings/SettingsScreen.kt` (sections at :156-:283, rows `SettingRow`/`SwitchRow`/`SectionHeader`), `SettingsSheets.kt`,
`ui/components/SubPageTopBar.kt`, and in `ui/AristotleApp.kt` the `PairedPage` enum (:172-:174) and the Settings routes (:231, :270-:280).

## Q72 (user 2026-11-04)
Settings becomes a list of categories; each opens its own sub-page with the back arrow (Android settings style):
1. **Connection & PCs** — summary line: "<state> · <Wi-Fi|Tailscale>" (e.g. "Connected · Wi-Fi")
2. **Notifications** — "On" / "Off" / "Some off"
3. **Appearance** — "<theme> · <text size>"
4. **Language** — the chosen language
5. **Security** — "App lock on · 1 minute" / "App lock off"
6. **About** — "Aristotle 1.0.0"
Rows: 24dp outlined icon (existing `ic_*` — add none unless one is missing, then Material Symbols Outlined 400 like TU2), title
(bodyLarge), summary (bodyMedium, onSurfaceVariant), chevron; ≥ 56dp rows; no section headers on the top list.
Each sub-page shows exactly the content its section shows today (move the section composables, do not change their behaviour).
Back from a sub-page returns to the list (and system back too); Battery guide and What's new go back to their sub-page, not the list.
Restoring after process death keeps the sub-page (the page enum is saved already — extend it).

## Not yours (TL1e is editing them now)
`ui/settings/SettingsConnection.kt`, `ui/settings/SettingsViewModel.kt` — call their composables/state as they are; if you need a
new value from the ViewModel (e.g. the connection summary), derive it in `SettingsScreen.kt` from state it already exposes, or list
what you need in the report. Also not: `data/**`, `server/**`, `ui/theme/**`.

## Files
`ui/settings/SettingsScreen.kt` (split as you see fit into new files in `ui/settings/`), `ui/AristotleApp.kt` (page enum + routes only),
strings (append-only), tests (pure summary-line functions, back mapping), `docs/v1-tickets/runs/TS3/build-report.md`.

## Hard rules
No commits; never git checkout/restore/stash/reset. No adb.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TS3` exits 0 (if it fails only in TL1e's files, say so).
Report ≤15 lines.
