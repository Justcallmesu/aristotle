# TV2 — CHANGELOG + in-app "What's new" (Q64), plus D48

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q63, Q64, `docs/v1-tickets/tickets.md` (the board
and append log = the history), `docs/design.md`, then `ui/settings/*` (About: "What's new" currently "Coming soon"), `data/AppSettings.kt`,
`data/Compatibility.kt` (`ServerVersions`), `data/PcRecords.kt`.

## Do
1. `CHANGELOG.md` at the repo root, Keep-a-Changelog format: `## [1.0.0] - 2026-11-04` with user-facing Added / Security sections
   written from the board (phone app and server; plain words, one line per feature, no ticket ids), and an `## [Unreleased]` heading.
2. Ship the app's part in the APK: `android/app/src/main/assets/changelog.md` generated from CHANGELOG.md by a Gradle task at build time
   (single source; never hand-copied), containing only the app-relevant sections.
3. In-app: About › "What's new" opens a page rendering it (reuse the chat Markdown renderer read-only, do not edit it). After an update
   (stored last-seen versionCode < current), show "What's new in 1.0.0" once as a dismissible card on the sessions screen; first install
   counts as seen (no card on a fresh install).
4. D48: clear `ServerVersions` on unpair and re-pair (PcRecords), with a test.

## Not yours
`ui/chat/**` except reading the Markdown renderer, `ui/quote/**`, `server/**`.

Hard rules: strings.xml append-only (both values/ and values-in/); another worker edits android at the same time (see 'Not yours').
No commits; never git checkout/restore/stash/reset. No adb. Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TV2`
exits 0 (if it fails only in the other worker's files, say so). Report `docs/v1-tickets/runs/TV2/build-report.md` ≤15 lines.
