# T13 build brief — composer: send, and Steer / Queue / Stop while busy

route: deep (writes into the user's live sessions)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` §1 v1.4, Q10 (explicit Steer/Queue/Stop, never
change `busy_input_mode`), Q24 (no voice, no share-to), Q50; `docs/design.md` (composer, busy bar) and `docs/design/mockups.html` chat
screens; `docs/protocol.md` §5 (send / busy: `prompt.submit` or equivalent, steer, queue, interrupt/stop — exact methods, params and
results), §4, §6, §12; the captured frames `docs/spikes/frames/c-send.jsonl`, `f-busy-*.jsonl`; board rows D35, D36 in
`docs/v1-tickets/tickets.md`; then `ui/chat/{ChatScreen,ChatViewModel,PromptSheet*}.kt`, `data/chat/*`, `data/{LinkManager,RelaySocket}.kt`.
Verify every method/param against the Hermes source (`/home/justcallmesu/.hermes/hermes-agent/`, read-only) and cite file:line.

## Goal
The user types in a session's chat and sends it; Hermes runs the turn and the Desktop shows the same message. While the agent is busy,
the user explicitly chooses Steer (inject into the running turn), Queue (run after it) or Stop (interrupt), the same actions the Desktop
offers — the profile's `busy_input_mode` is never read-modify-written.

## Requirements
1. Composer at the bottom (design.md): multi-line text field (max ~6 lines then scroll), Send button. Idle session: Send submits a new turn.
   Disabled while the link is not online (text kept). The draft per session survives navigation and process death (small, local only;
   never sync drafts to the PC).
2. Busy session: the Send button becomes a choice — Steer / Queue — and a separate Stop control is always visible while running. Queued
   messages show as pending rows (from Hermes' queued-controls state if the protocol exposes it, so the Desktop's queue and the phone's
   agree; cite) with cancel if Hermes supports it.
3. Optimistic echo: the user's message shows at once as "sending" and is reconciled with Hermes' own user message event (no duplicate),
   or marked failed with Retry if Hermes rejects it / the link drops before the ack. Never auto-resend (no double submit after reconnect).
4. Tap-through: reuse the T12b arm idea — a Send/Steer/Queue/Stop tap within 600 ms of the control appearing or changing meaning (idle ->
   busy swap, a prompt sheet closing) is ignored. Stop asks for no confirmation but needs the armed control.
5. D35: show the running turn's user prompt when opening a chat mid-turn if Hermes provides it (cite); otherwise leave as is and say so.
6. D36: close code 4409 (superseded) on the live socket -> do not reconnect that socket (the link already has or will open its newer one);
   test in LinkManagerTest.
7. A pending approval/clarify/sudo prompt blocks sending (the sheet takes priority), matching Desktop behaviour (verify).
8. EN/ID strings.

## Tests
Frame builders per action (shape vs source); ViewModel: idle send, busy steer/queue/stop, echo reconciliation, failure + retry without
duplicate, no auto-resend after reconnect, tap-through ignored within 600 ms; D36.

## Hard rules
Files: `android/**`, `docs/protocol.md` §5 only to correct a shape you prove wrong from the source (cite),
`docs/v1-tickets/runs/T13/build-report.md`. No commits; never git checkout/restore/stash/reset. Do not install on the phone. Never
connect to the real Hermes. No SDK installs.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T13` exits 0.

## Report
`docs/v1-tickets/runs/T13/build-report.md`, ≤35 lines, with the action table (UI action -> frame, source cite).
