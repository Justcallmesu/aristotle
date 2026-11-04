# TU4 build brief — UI refinement

route: std.

## Goal
Chat transcript: rhythm with the TU2 chat tokens (Q67), **tool-call folding (Q68)**: 3+ consecutive tool calls (no assistant text between) render as one card "N tool calls · last: <tool name>" that expands to the individual cards; a running call inside a folded group keeps the group's spinner (verdigris only while live); a failed call shows in the folded header; tool-summary path shortening (strip the common prefix / home dir to `~`, keep the differing tail, middle-ellipsis) as a pure tested function; markdown paragraph/list/table spacing and table cell wrapping; the chat top bar subtitle separators and a stable top-bar height (no jump after load); reasoning row height — every audit finding assigned to TU4.

## Your files (only these)
`ui/chat/ChatScreen.kt`, `ui/chat/ChatItems.kt`, `ui/chat/ChatMarkdown.kt`, `ui/chat/ChatText.kt`, `ui/chat/SubagentViews.kt`, a new `ui/chat/ToolFolding.kt` (pure), their tests

## Common
Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/TU1/audit.md` (the findings assigned to YOUR ticket, by id),
`docs/v1-tickets/runs/TU2/build-report.md` (new tokens: Spacing chatWorkGap 4 / chatMessageGap 12 / chatTurnGap 20 / xxs 2; bundled fonts;
Material-sized controls; icons), `docs/design.md`, `docs/design/mockups.html`, `docs/spec.md` Q66-Q70. Device screenshots of the current
state: `/home/justcallmesu/.hermes/profiles/pm/cache/scratch/ui/*.png` (+ .xml dumps with bounds).

Hard rules: edit ONLY your files below; two other workers edit the other UI files at the same time (`ui/theme/**` is frozen — use its
tokens, never edit it; if you need a new token, define a private val in your file and list it in the report). strings.xml: append new keys
at the END only (both values/ and values-in/), never edit or reorder existing ones. Keep behaviour and tests green; add tests for any new
logic (pure functions). No commits; never git checkout/restore/stash/reset. Do not install on the phone or use adb.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TU4` exits 0 (if it fails only in another worker's files,
say so). Report: `docs/v1-tickets/runs/TU4/build-report.md`, ≤20 lines: each audit finding id -> fixed/how, files, tests.
