# TR4 — v2 redesign build

route: std.

## Goal
Floating composer per design-notes §5.3 (composer) + §10 TR4: one `ComposerShape` field (surfaceContainerHigh) inset 12 dp holding the text, attach, the model chip (model · effort, opens the model sheet) and Send/Stop; Queue/Steer only when the field has text or an attachment (Q69, keep `showsBusyExtras`); prompt-waiting state; keep the slash menu and attachment previews working (restyle their containers only if they live in your files).

## Your files (only these)
`ui/chat/{Composer,ChatComposer,ComposerState,SlashMenuPanel,AttachmentViews}.kt`, their tests

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
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TR4` exits 0 (if it fails only in another worker's files,
say so). Report `docs/v1-tickets/runs/TR4/build-report.md` ≤15 lines: what matches the board, what deviates and why, files, tests.
