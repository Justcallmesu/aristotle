# TU5 build brief — UI refinement

route: std.

## Goal
Composer: Q69 (busy: Stop always; Queue/Steer only when the field has text; hint line only then), compact heights; slash menu row rhythm + no clipped last row; model sheet effort chips spacing; attach menu surface/elevation so it separates from the transcript; prompt sheet polish — every audit finding assigned to TU5.

## Your files (only these)
`ui/chat/Composer.kt`, `ui/chat/ChatComposer.kt`, `ui/chat/ComposerState.kt` (only if Q69 needs it), `ui/chat/SlashMenuPanel.kt`, `ui/chat/ModelSheet.kt`, `ui/chat/AttachmentViews.kt`, `ui/chat/PromptSheet.kt`, their tests

## Common
Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/TU1/audit.md` (the findings assigned to YOUR ticket, by id),
`docs/v1-tickets/runs/TU2/build-report.md` (new tokens: Spacing chatWorkGap 4 / chatMessageGap 12 / chatTurnGap 20 / xxs 2; bundled fonts;
Material-sized controls; icons), `docs/design.md`, `docs/design/mockups.html`, `docs/spec.md` Q66-Q70. Device screenshots of the current
state: `/home/justcallmesu/.hermes/profiles/pm/cache/scratch/ui/*.png` (+ .xml dumps with bounds).

Hard rules: edit ONLY your files below; two other workers edit the other UI files at the same time (`ui/theme/**` is frozen — use its
tokens, never edit it; if you need a new token, define a private val in your file and list it in the report). strings.xml: append new keys
at the END only (both values/ and values-in/), never edit or reorder existing ones. Keep behaviour and tests green; add tests for any new
logic (pure functions). No commits; never git checkout/restore/stash/reset. Do not install on the phone or use adb.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TU5` exits 0 (if it fails only in another worker's files,
say so). Report: `docs/v1-tickets/runs/TU5/build-report.md`, ≤20 lines: each audit finding id -> fixed/how, files, tests.
