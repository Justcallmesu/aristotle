# TU3 build brief — UI refinement

route: std.

## Goal
Session list top bar (Q70: wordmark · profile chip · search · ⋮ menu with Scheduled, Terminal (only when shown), Settings), the profile chip style, Scheduled page, sub-page top bars, terminal top bar — every audit finding assigned to TU3.

## Your files (only these)
`ui/sessions/SessionsScreen.kt`, `ui/sessions/SessionSheets.kt`, `ui/sessions/SessionRows.kt`, `ui/cron/CronScreen.kt`, `ui/cron/CronFormPage.kt`, `ui/terminal/TerminalScreen.kt` (top bar only), `ui/components/SubPageTopBar.kt`, their tests

## Common
Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/TU1/audit.md` (the findings assigned to YOUR ticket, by id),
`docs/v1-tickets/runs/TU2/build-report.md` (new tokens: Spacing chatWorkGap 4 / chatMessageGap 12 / chatTurnGap 20 / xxs 2; bundled fonts;
Material-sized controls; icons), `docs/design.md`, `docs/design/mockups.html`, `docs/spec.md` Q66-Q70. Device screenshots of the current
state: `/home/justcallmesu/.hermes/profiles/pm/cache/scratch/ui/*.png` (+ .xml dumps with bounds).

Hard rules: edit ONLY your files below; two other workers edit the other UI files at the same time (`ui/theme/**` is frozen — use its
tokens, never edit it; if you need a new token, define a private val in your file and list it in the report). strings.xml: append new keys
at the END only (both values/ and values-in/), never edit or reorder existing ones. Keep behaviour and tests green; add tests for any new
logic (pure functions). No commits; never git checkout/restore/stash/reset. Do not install on the phone or use adb.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TU3` exits 0 (if it fails only in another worker's files,
say so). Report: `docs/v1-tickets/runs/TU3/build-report.md`, ≤20 lines: each audit finding id -> fixed/how, files, tests.
