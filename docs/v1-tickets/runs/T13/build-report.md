# T13 build report — composer: Send, Steer / Queue / Stop, optimistic echo, tap-through guard, D35, D36

**Gate:** `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T13` → `SKIP server`, `PASS android` (exit 0). 307 unit tests, 0 failed (T12b: 281). Lint: 0 errors; the 12 warnings are all older (SDK/deps/TrustManager). Mutation check: I broke 5 things one at a time (Stop arming, 4409 handling, D35, echo reconcile, Retry id), and each made its tests fail. Nothing committed, nothing installed, Hermes not contacted.

| UI action | Frame (exact keys) | Source / evidence |
|---|---|---|
| Send (idle) | `prompt.submit {session_id, text, submission_id}` | `gateway/session_controls.py:813-833` (unknown keys refused, intent always `queue`); `c-send.jsonl:9-13` |
| Queue (busy) | same `prompt.submit`. Hermes queues it in the FIFO and never reads `busy_input_mode` | `session_controls.py:833`; `f-busy-client2.jsonl:21-22,42` |
| Steer (busy) | `session.steer {session_id, execution_generation, text}` | `gateway/session_busy_controls.py:175-217`; `canonical_sessions.py:269-287`; `f-busy-client2.jsonl:25-26` |
| Stop (busy) | `session.interrupt {session_id, execution_generation}`; `stale_generation` = already over, no message | `session_controls.py:880-886,903-907`; `f-busy-client1.jsonl:70-71` |
| Queued row ✕ | `prompt.cancel {session_id, admission_id}` | `tui_gateway/contracts/canonical.py:59-65`; `session_controls.py:872-895`; Desktop `discard-lost-prompt.ts:23-33` |
| Retry (submit) | the same `prompt.submit`, same `submission_id`. Same principal + session + id replays the admission, so it never runs twice | `hermes_state_runtime.py:154-161`; principal = ticket subject, `gateway/run_api.py:483` |

**Composer** (`ui/chat/Composer.kt`, design.md §5 and mockup 3). Field grows to 6 lines, then scrolls. Idle: Send. Busy: Stop (outlined, error), Queue (tonal), Steer (filled), plus a hint line. Offline or not subscribed: field disabled, text kept, "You can send once reconnected". Queued rows come from Hermes' `session.info.pending` (`status:queued`, `session_queued_controls.py:64-74`), so the phone and Desktop show the same queue; each has ✕. Drafts: `data/chat/ChatDrafts` uses its own prefs file, excluded from backups, never synced. Empty drafts are deleted. `draftEpoch` stops a late state from undoing typing.

**Echo** (`ui/chat/Outbox.kt`, pure). Hermes has no user-message event. Its record of a message is the `pending` admission (`input_id` = our `submission_id`), which becomes the turn's user row at `message.start` (`c-send.jsonl:11-12`). The reducer now tracks `inputs` (input → admission), `generation` and `snapshots`. A Send bubble stays until its admission is shown, so there is no flicker through the queued state. A Queue echo yields to Hermes' queue row. A failed echo that Hermes did receive becomes "sent". An accepted steer stays as a user row after the item it followed; the Desktop does the same (`use-prompt-actions/index.ts:885-947`). Failure kinds: `NotSent` (no socket took it; new `RpcException.written`), `NoAnswer`, `Refused`, `TurnEnded` (`rejected` + `delivered:false`), `NotTaken`. Retry is offered when it cannot deliver twice: always for submits, and for steers only for `NotSent`, `Refused` and `TurnEnded`. A steer has no idempotency key. ✕ removes a failed echo and puts its text back in an empty field. Nothing is resent automatically; every send is a tap.

**Tap-through guard** (`ComposerLogic`, 600 ms = `PromptSheetLogic.ARM_MS`). The key is (mode Offline/Idle/Busy/Covered, blocked, queued rows). The timer restarts whenever the key changes: idle↔busy, the prompt sheet opening or closing, a queued row moving. Send, Steer, Queue, Stop and ✕ are ignored until the key has held for 600 ms; the buttons show disabled until then. Retry and dismiss count only 600 ms after the failure appeared. Stop has no confirmation.

**Pending prompt (req 7), checked against the Desktop.** The Desktop does not block everything. It turns Steer off (`busy-submit-state.ts:37-42`) and queues typed text (`use-composer-submit.ts:379-384`). The phone matches: while a prompt waits for this phone, Send and Steer are off, Queue still works, Stop stays, and the sheet covers the composer. One difference: on the Desktop, typed text can answer a parked clarify card (`:397-436`). The phone has no such path.

**D35, done.** On a full snapshot, the started `pending` text (not `steering` entries) is added unless the history's last user row equals it. Reason: the prompt row is persisted only at `_persist_turn_start` (`agent/turn_context.py:1076-1090`, called `:1285`), and text prompts are stored as typed (`agent/session_persistence.py:122-127`). Known edge: two identical prompts in a row would show the second one only after its turn starts. The T11 NIT about `display_kind` rows is not done.
**D36, done.** `4409` → `RoundEnd.Superseded` → `Offline(cause=Superseded, waiting=true)`. No timer round runs. The next round waits for a network change, foreground, or Retry now. The banner says so (new EN/ID strings). Test: `LinkManagerTest.aSupersededSocketIsNotReconnectedByItself`, plus `LinkEndsTest`.

**Protocol §5:** steer row corrected from source: allowed keys; `delivered`/`voice_stopped`; never re-send a plain `rejected`; no idempotency key.
**Files:** new `ui/chat/{Composer,ComposerState,ChatComposer,Outbox}.kt`, `data/chat/{ComposerFrames,ChatDrafts}.kt`, `drawable/ic_{send,stop,close}.xml`. Changed `ui/chat/{ChatViewModel,ChatScreen,ChatItems}.kt`, `data/chat/{ChatModel,ChatReducer}.kt`, `data/{RpcFrames,RelayRpc,LinkManager,LinkState}.kt`, `ui/sessions/OfflineBanner.kt`, `AppContainer.kt`, `ui/AristotleApp.kt`, `values{,-in}/strings.xml` (25 keys each), `docs/protocol.md` §5. Tests: new `ComposerFramesTest` (5: captured frames and exact key sets), `ComposerReducerTest` (4), `ChatViewModelComposerTest` (16); `LinkManagerTest` +1, `LinkEndsTest`, `RpcFramesTest` updated.
**Not verified:** nothing tested on a device. Needs phone QA:
- keyboard insets
- 6-line scroll
- the Desktop showing the phone's message and queue
- a Desktop ✕ removing the phone's row
- 4409 with both listeners reachable

Still open: the launch profile only (no `profile` param, as in T11/T12), and `prompt.steer_queued`/`replace_queued` (not offered).
