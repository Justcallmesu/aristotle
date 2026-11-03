# T11 build brief — chat view: history + live streaming + tool cards

route: deep (core of the app; protocol reducer)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read in order:
1. `PROJECT.md`; `docs/spec.md` §1 v1.3–v1.4, Q4, Q10, Q24, Q34.
2. `docs/design.md` (binding) and `docs/design/mockups.html` screen 3 "Chat, live turn" (palette A only).
3. `docs/protocol.md` §2 + §2.1 (events, session.info two shapes — merge only present keys), §4 (`session.resume` snapshot,
   `messages`, `events.since`), §5 (to recognise busy state; sending is T13), §7, §10, §12 (client obligations), and the captured
   frames `docs/spikes/frames/c-*.jsonl`, `f-busy-*.jsonl` (real event shapes; use them as test fixtures).
4. The app code: `data/{LinkManager,SessionRepository,SessionWatcher,AlertRouter}.kt`, `AppContainer.kt`, `ui/sessions/*`.

## Goal
Tapping a session opens its chat: the stored transcript, then the live turn streaming token by token at the same time as the Desktop,
with tool calls shown as cards. Read-only in this ticket (composer is T13, approvals UI is T12 — show a placeholder chip
"Hermes is waiting for you" when a prompt is open).

## Requirements
1. **Pure reducer** (`data/chat/`): `ChatState` + `reduce(state, frame)` for every event the frames contain (message start/delta/complete,
   reasoning, tool start/progress/complete with name, args summary and output, status/busy, session.info shapes A and B merged by present
   keys, errors, cancelled turns). Unknown events are ignored, never crash. A `HistoryMapper` turns `session.resume` `messages` into the same
   item model. Unit tests replay the captured frames and assert the item list; plus a shape-A-after-B test (no field blanked).
2. **Subscription**: opening a chat resumes the session on the shared link exactly once — coordinate with `SessionWatcher` (expose its
   resumed set; reuse, never resume twice on one socket). Set `AppContainer.openSession` while the chat is visible (suppresses T10 alerts).
   On reconnect: resume again and rebuild from the snapshot (or `session.events.since` when `last_sequence` is known and not truncated).
3. **UI** per design.md: top bar (session title, profile, link state chip), message list (user, assistant with Markdown rendering, reasoning
   collapsed by default, tool cards collapsed/expanded with monospace output, max ~40 lines then "Show all"), streaming cursor in verdigris
   only while streaming, auto-scroll only when the user is at the bottom, "jump to latest" button otherwise. Large transcripts: LazyColumn,
   stable keys, no recomposition storms (deltas append to the last item only).
4. **Markdown**: use a maintained Compose Markdown renderer with Material 3 support (PM technical decision: `com.mikepenz:multiplatform-
   markdown-renderer-m3`, latest version compatible with compileSdk 36; justify if you pick another). Code blocks in Atkinson Mono.
5. Hand frames off the OkHttp thread immediately (§12 client obligations): parse + reduce on a background dispatcher.
6. D8: night theme window colours with edge-to-edge (system bars) in this screen and the sessions screen.
7. EN/ID strings for every new label.

## Hard rules
Files: `android/**`, `docs/v1-tickets/runs/T11/build-report.md`. No commits; NEVER run git checkout/restore/stash/reset. Phone not
attached: no device run; list device checks in the report. No SDK installs. Never connect to the real Hermes from tests.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T11` exits 0.

## Report
`docs/v1-tickets/runs/T11/build-report.md`, ≤40 lines: event types handled (table), files, library + version, tests by name, NOT-VERIFIED.

## Note
T10/T10b (SessionWatcher, AlertRouter) are committed; build on them, do not rework them beyond what requirement 2 needs.
