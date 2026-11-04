# TR6 — v2 redesign build

route: std.

## Goal
Settings list + every sub-page (incl. Connection, saved addresses, connection history) as `GroupedRows` per design-notes §5.4 + §10 TR6; prompt sheets (approval, clarify, sudo/secret) in the v2 shapes keeping Q41's order, the 600 ms arm, FLAG_SECURE owners and the fresh-auth gates exactly as they are.

## Your files (only these)
`ui/settings/**`, `ui/chat/{PromptSheet,PromptSheetState,SecretField}.kt`, `ui/lock/LockScreen.kt` (shapes only), their tests

## Common
Repo `/home/justcallmesu/project/personal/Aristotle`. The contract is the v2 board `docs/design/v2/mockups.html` (open it in a browser
tool if you have one; it is self-contained) and `docs/design/v2/design-notes.md` — read the sections named below fully; the Compose
mapping there is binding. Also `docs/spec.md` Q67, Q69, Q74-Q76, `docs/design.md` (§3 rewritten by TR1), `docs/v1-tickets/runs/TR1/
build-report.md` (new shapes `FieldShape`, `ComposerShape`, `SquareIconButtonShape`, `ButtonShape`; `ui/components/GroupedRows.kt`,
`ui/components/AristotleSlider.kt` — use them, never edit `ui/theme/**` or `ui/components/**`; need a new token? private val in your file,
list it). Real device screenshots of today's look: `/home/justcallmesu/.hermes/profiles/pm/cache/scratch/ui/*.png`.

Four other workers build the other screens at the same time (TR2 home, TR3 chat transcript + chat top bar, TR4 composer, TR5 model sheet,
TR6 settings + prompt sheet): edit ONLY your files. strings.xml: append new keys at the END only, both values/ and values-in/. Keep
behaviour; existing tests stay green; add tests for new pure logic. Gradle is shared: if a gate run collides, wait and rerun. No commits;
never git checkout/restore/stash/reset. No adb.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TR6` exits 0 (if it fails only in another worker's files,
say so). Report `docs/v1-tickets/runs/TR6/build-report.md` ≤15 lines: what matches the board, what deviates and why, files, tests.
