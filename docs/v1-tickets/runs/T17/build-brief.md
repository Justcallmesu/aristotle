# T17 build brief — subagents: see them live, open their output, stop them

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` §1 v1.4; `docs/design.md`;
`docs/protocol.md` §2 (events), §4, §5, §7; the captured frames in `docs/spikes/frames/` (any delegation/subagent events); then
`data/chat/{ChatReducer,ChatModel,HistoryMapper}.kt`, `ui/chat/{ChatItems,ChatScreen,ChatViewModel}.kt`. Find exactly how the Hermes
Desktop shows subagents (delegations): which events carry their start/progress/output/finish, how the Desktop lists them, how it opens
one's live output (a child session? a separate stream?), and how it stops one (method + params). Hermes source and `apps/desktop/` under
`/home/justcallmesu/.hermes/hermes-agent/`, read-only. Cite file:line for every event and method.

## Goal
While Hermes runs subagents in a session, the phone shows them in the chat like the Desktop does, lets the user open one to watch its live
output, and stop one (or all) the same way the Desktop does.

## Requirements
1. Reducer: subagent/delegation events become chat items (name/goal, model, status running/done/failed/interrupted, elapsed, last line)
   grouped under the tool call that spawned them. Unknown fields ignored; history rebuild produces the same items.
2. Open a subagent: its live output in a sub-page (reuse the chat transcript rendering; read-only), following exactly the Desktop's
   mechanism (subscribe to the child session, or filter the parent stream — whichever the source shows). No second resume of the parent
   on the same socket beyond what ResumedSessions allows.
3. Stop: per subagent and "stop all" where the Desktop has it, behind the 600 ms arm, with the Desktop's method. Confirmation only if the
   Desktop asks for one.
4. Background: a subagent finishing does not notify on its own unless the Desktop treats it as a turn end (cite); the parent turn's
   "Turn finished" stays the main signal.
5. EN/ID strings; design.md (verdigris only while running).

## Tests
Reducer on captured/handmade frames from the source shapes; history parity; stop frame builder vs source; arm guard; open-subagent
subscription rules.

## Hard rules
Files: `android/**`, `docs/protocol.md` §2/§5 only to add shapes proven from the source, `docs/v1-tickets/runs/T17/build-report.md`.
Do NOT touch `android/app/src/main/java/io/github/justcallmesu/aristotle/data/chat/AttachmentRules.kt` or its test (another worker edits
them right now). No commits; never git checkout/restore/stash/reset. Do not install on the phone. Never connect to the real Hermes.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T17` exits 0. (If the gate fails only in AttachmentRules*, report it;
another worker owns those files.)

## Report
`docs/v1-tickets/runs/T17/build-report.md`, ≤30 lines with an events/methods table (source cites).
