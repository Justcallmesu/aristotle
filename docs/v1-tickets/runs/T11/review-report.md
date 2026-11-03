# T11 review (+ T10b re-check) — fresh reviewer, read-only, no gradle run (PM gate-16 GREEN taken as given)

**MAJOR-1 — items go missing at the reconnect seam (`events.since` → `resume {omit_messages}`).** On a new socket where nobody has resumed the session, `ChatFeed.subscribe` calls `session.events.since` (up to seq N), then `session.resume {omit_messages}`. Hermes keeps the cursor (`session_controls.py:739-752`). The resume pops it, and `attach` writes every frame after N to this transport *before* the reply, under the stream lock (`session_authority.py:369-370`, `session_events.py:87-98`; the docstring describes this window). Those frames (N, M] reach the inbox, but `fromSnapshot` sets `lastSeq = last_sequence` (M) even when `messages_omitted` keeps the old items (`ChatReducer.kt:49,55`), so `applyUntilGap` drops them as `alreadyApplied` (`:90-95`).
- Effect: one RTT of deltas silently missing mid-reply; the next delta's `offset >= applied` hides the hole (`:164`). A lost `tool.complete` leaves the card Done with no output; a lost `message.complete` leaves the final text missing when nothing streamed before. This is the DC-T11f Wi-Fi→Tailscale case.
- The test `aNewSocketCatchesUp…` asserts `lastSeq == 12` although seq 12 was never applied, so it hides the bug.
- Fix: on the omit path keep `lastSeq`/`replayEpoch` from `caughtUp` (equal to Hermes' cursor), so the catch-up frames apply and duplicates still de-dup. Add a test where the fake emits seq 12 before it answers the omit resume.

**1. Second `session.resume` on one socket — confirmed safe.**
- `actor` is per connection (`session_controls.py:154-156`), so `attach` reuses the subscription key (`session_authority.py:359-361`).
- `EventFanout.attach` returns False for an already-attached peer and adds no second peer (`tui_gateway/transport.py:168-180`): no event is delivered twice.
- `catch_up` runs only when `not fanout.contains(transport)` (`:369`), so it is skipped.
- What re-runs is harmless: `cancel_persisted_close`, `reask_on_attach`, and `refresh_resumed_policy(attached=True)` (a snapshot poll).
- NIT: in the watcher-held path, `events.since` leaves a Hermes `replay_cursors` entry that nothing pops. A later resume after a replay_gap could replay the 512-frame ring from it, and the watcher has no seq de-dup, so "turn finished" could repeat. Unlikely, since the ring has normally rotated by then. Fix: follow `events.since` with `resume {omit}`, which is idempotent per the above.

**2. Reducer on the captured frames — correct apart from MAJOR-1.**
- Deltas touch only the last item; re-sent chunks drop by turn_id/segment/offset.
- Tool cards pair by `tool_id`; in every frame it equals `tool_call_id`. `args` is a Python-repr string, so the summary falls back to `context` (correct).
- A cancelled turn shows "Turn stopped". `session.info` merges only the keys present (`shapeAAfterShapeBBlanksNoField` checks every shape-B key).
- Other sessions and unknown event types return the same instance; a new epoch resets `lastSeq`.
- The full-rebuild seam (first open, replay_gap) is correct: Hermes snapshots under the same lock it publishes under.

**MINOR-2 — opening a chat mid-turn loses the start of the reply.** The snapshot has no partial assistant text, so later deltas (`offset > 0`) are appended as if they began the reply. `message.complete` then adds nothing because `hadText` is set, so this view stays wrong until reopened. This is the core flow. Fix: if a turn's first delta has `offset > applied`, mark the turn joined-late and replace that block with `message.complete.text`.

**3. HistoryMapper vs Hermes.** The fields match `_rows_to_conversation` (`hermes_state_messages.py:1595-1640`); `row_id` comes from `session_local_recovery.py:357`. Is the running turn's user prompt in `messages`? **Mostly.** `_persist_turn_start` writes it before the first LLM call (`agent/turn_context.py:1076-1090`, called at `:1285`), but after the system-prompt build and preflight compaction.
- MINOR-3: in that window `fromSnapshot` marks the started admission as shown (`ChatReducer.kt:59`), so the prompt is missing. Show the started `pending` text unless the last history user row equals it.
- NIT: rows with a `display_kind` (failed-turn boundary, compression handoff, nudges stored as `user`) render as plain bubbles. None were captured, so not verified.

**4. Secrets and Markdown.**
- `system_prompt` and `credential_hint` are filtered (`:19,108`); only title, model and profile render; nothing is logged.
- **Images: no fetch.** The default `NoOpImageTransformerImpl.transform` returns null (0.41.0 bytecode checked) and there is no coil dependency.
- **Links: MINOR-4.** A tap calls `LocalUriHandler.openUri` with no scheme filter (try/catch, so no crash): ACTION_VIEW on any scheme (tel:, sms:, market:, other apps' deep links). It needs a user tap, the link text can disguise the target, and `intent:` is not parsed. Fix: provide a `LocalUriHandler` that only opens http/https.

**5. Performance — OK.** Stable keys plus `contentType`; deltas replace only the last element; the feed reduces under `flowOn(Default)`; the library parses Markdown in `withContext(Default)`; frame JSON is parsed in the Default link scope.

**6. Resume refused after a reconnect — MINOR-6.** Once `loaded` is true, `problem` is ignored (`ChatScreen.kt:92-94`) and the feed stops until the next socket. The chat stays stale while still showing the verdigris "Running · step N", spinner and cursor, and the link chip says online.
- Causes: a deleted session, or the 15 s RPC timeout on a large transcript (that timeout may even have subscribed on Hermes).
- Not MAJOR: Back and reopen recovers. Fix: when loaded, show `problem` as a banner with Retry and drop the live styling.

**7. design.md, EN/ID, D8.**
- OK: 82 % bubbles; verdigris only on the cursor, spinner and running subtitle; "Failed" only on surfaceContainerLow; output and code use onSurface on Highest; the cursor goes below an open fence; folds use the §4 easings with reduced motion; all 27 keys in EN and ID; launch themes paint the bars and both screens are edge-to-edge.
- MINOR-7: offline, tool spinners and the streaming cursor stay, breaking "no spinners while offline" and "verdigris only for live work". Settle or gate them on `live`.
- NITs: the chat column pads only `Top`, so the top bar has no horizontal insets in landscape. design.md §5 still says ~12 output lines while the brief says 40.

**8. T10b re-check — correct.**
- Claim-before-call replaces `resuming`; a refusal releases, a timeout keeps the claim.
- On replay_gap the watcher leaves an open chat to rebuild itself and otherwise resumes once. `ResumedSessions` is `@Synchronized` and keyed by generation.
- Re-posts are silent (`setSilent(repeat)` + `setOnlyAlertOnce`); dismiss uses a non-exported receiver via `deleteIntent`; all router calls are on the main thread.
- NIT race: if a chat closes after the watcher's `chatOpen` check but before its own feed handles the gap, the session stays claimed and unresumed until the socket ends.
- NIT: a notification removed by a tap (autoCancel, which sends no deleteIntent) can come back silently on reconnect.

VERDICT: CHANGES-REQUIRED
