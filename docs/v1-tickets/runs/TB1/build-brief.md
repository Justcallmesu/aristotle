# TB1 — chat status stack: running subagents + background processes (Q77), plus TR review minors

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` **Q77** (incl. the confirmation popup), Q17-Q18
(subagents, T17), Q75, `docs/design/v2/design-notes.md` + `mockups.html` (component language; there is no board frame for this panel —
follow the composer/steps-line styling), `docs/v1-tickets/runs/review-TR.md` (MINOR 1-5 + unused resources), then `ui/chat/{ChatScreen,
Composer,ComposerState,SubagentViews,ChatViewModel}.kt`, the T17 subagent/delegation data (`data/chat/**`, grep `Delegation`/`subagent`),
`data/RelayRpc*`, and `server/internal/relay/**` (does it forward any method the phone sends? find the code path and state it).

## Hermes facts (VERIFIED by the PM, read-only source at /home/justcallmesu/.hermes/hermes-agent)
- `process.list {session_id, profile?}` -> `{processes: [ProcessEntry]}`; ProcessEntry = `tui_gateway/contracts/tools_commands.py:93-109`
  (session_id, command, cwd, pid, started_at, uptime_seconds, status, output_preview, output_tail, …). Session-scoped. The Desktop polls
  it every 5 s plus on events (`apps/desktop/src/store/composer-status.ts:431-460`), stops polling a gone session (4001).
- `process.kill {session_id, process_id, profile?}` -> ProcessKillResult (`tools_commands.py:123-151`); only the caller session's
  process (4044 otherwise) (`tui_gateway/methods_tools.py:285-295`). Which field is the process id in ProcessEntry? Read
  `tools/process_registry.py::list_sessions` and say.

## Do
1. **Status stack above the composer** (Q77): collapsible "N Subagents" (this session's running subagents from the T17 data: goal, current
   step "[set k · i/n] tools…", tap -> the existing subagent page) and "N Background" (process.list rows: command one line, mono, middle-
   ellipsis; status; uptime). Each section collapses; the whole stack is hidden when both are empty; expanded state per session survives
   rotation. Max height ~40% of the screen, scrolls inside.
2. Poll `process.list` every 5 s only while the chat screen is visible, the session is live and the link is online; refresh on relevant
   events if any exist (check what the Desktop listens to); stop on 4001/4044 and on leaving the chat. No polling in the background service.
3. **Stop** on a background row opens the **confirmation dialog** (Q77): full command (scrollable, mono), "Running for <uptime>", red
   Stop + Cancel; only Stop sends `process.kill`; Cancel/back/outside send nothing; show the result (stopped / already exited / not
   found / error) as a short line; the row disappears on the next poll.
4. If the relay blocks these methods, add them to the server's forward path minimally (server/** allowed for exactly that) with a test.
5. **TR review minors:** (1) model sheet reachable when `chat.model` is null (fallback "Model" chip); (2) real fade for Queue/Steer/hint;
   (3) restore the busy `/command` Send guard while a prompt sheet covers the composer; (4) "Waiting for your answer" wording for clarify/
   sudo/secret vs "approval" for approvals; (5) `model_count` as a plural. Remove the unused resources listed in the review (both
   locales) — this is the one sanctioned exception to append-only strings.
6. EN + ID strings; CHANGELOG `[Unreleased]` one Phone line.

## Tests
Polling lifecycle (visible/live/online gating, stop on 4001/4044, leave), stack visibility rules, dialog: only Stop sends, cancel paths send
nothing, result mapping; minors 1, 3, 4, 5 as pure logic.

## Hard rules
Files: `android/**` (not `ui/theme/**`), `server/**` only if step 4 is needed, `CHANGELOG.md`, `docs/protocol.md` (a line on the two
methods), `docs/v1-tickets/runs/TB1/build-report.md`. No commits; never git checkout/restore/stash/reset. No adb; never connect to the
real Hermes or server.
Verify: `bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TB1` (full, server too if touched; else GATE_ONLY=android) exits 0.
Report ≤20 lines.
