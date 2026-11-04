# TR3 — v2 redesign build

route: deep.

## Goal
Chat transcript per design-notes §5.2 + §6 + §10 TR3: assistant text WITHOUT a bubble, user bubble with `shapes.large`; **every run of consecutive work items (reasoning + tool calls, even ONE) folds into one quiet 36 dp "N steps" line** (Q75 replaces Q68's 3+ rule; update `ToolFolding.kt` and its tests incl. the real-turn fixtures — keep a running step's spinner live-only, a failed step shown in the line); opened, compact 44 dp step rows in one container; delegate and notification cards; markdown/code corners (`CODE_CORNER` vs `small`); **chat top bar** subtitle per §5.6 ("Running · step N · [mark] pm", idle "[mark] pm") with a 16 dp profile mark (not a button).

## Your files (only these)
`ui/chat/{ChatScreen,ChatItems,ChatMarkdown,ChatText,ToolFolding,SubagentViews}.kt` + any new transcript file in `ui/chat/`, their tests (NOT Composer/ChatComposer/ModelSheet/PromptSheet/SlashMenuPanel/AttachmentViews)

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
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TR3` exits 0 (if it fails only in another worker's files,
say so). Report `docs/v1-tickets/runs/TR3/build-report.md` ≤15 lines: what matches the board, what deviates and why, files, tests.
