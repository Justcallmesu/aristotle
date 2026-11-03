# T11b build brief — T11 fix round + D33

route: deep (sequence/replay seam). Repo `/home/justcallmesu/project/personal/Aristotle`. Read
`docs/v1-tickets/runs/T11/review-report.md` (all findings), `docs/protocol.md` §4 + §12, board row D33 in `docs/v1-tickets/tickets.md`,
then `android/.../data/chat/{ChatReducer,ChatFeed}.kt`, `ui/chat/ChatViewModel.kt`, `data/{LinkManager,RelaySocket}.kt` and their tests
(uncommitted T11 working tree — build on it).

## Fix (each with a test that fails before the fix)
1. **MAJOR-1**: on the `session.events.since` + `session.resume {omit_messages}` path keep `lastSeq`/`replayEpoch` from the caught-up state
   (do not jump to the snapshot's `last_sequence`); frames N+1..M that Hermes writes before the resume reply must be applied exactly once.
   Fix the misleading `aNewSocketCatchesUp…` test; add one where the fake sends seq 12 before answering the resume.
2. **D33 (device-observed)**: when the link switches endpoint (network change, LAN<->Tailscale, retry), the previous relay socket is closed
   (`close(1000)` if open, `cancel()` if still connecting) BEFORE or right as the new one opens — never left for the server to reap after
   ~42 s. Test with the fake: after a switch, the old socket received close/cancel and at most one socket is open per endpoint.
3. Minors in the same code (review rule: fold into the ticket touching the area):
   - Opening a chat mid-turn: when the turn completes, replace the partial assistant block with the final text.
   - Refused resume after reconnect: show a banner "Couldn't reattach to this session" with Retry (EN/ID); stop showing Running/cursor.
   - Offline: hide the streaming cursor and tool spinners while the link is not online (design.md offline rule).
   - Markdown links: open only `http`/`https` (ACTION_VIEW, browsable); ignore other schemes.

## Hard rules
Files: `android/**`, `docs/v1-tickets/runs/T11b/build-report.md`. No commits; never git checkout/restore/stash/reset. Phone may be
attached: do NOT install or run on it (the PM does device tests). No SDK installs.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T11b` exits 0.

## Report
`docs/v1-tickets/runs/T11b/build-report.md`, ≤25 lines.
