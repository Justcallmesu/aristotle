# T11 build report — chat view (history + live streaming + tool cards)

Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T11` → `PASS android`, exit 0 (210 unit tests, 0 failed; lint 0 errors; 12 warnings, all pre-existing or "newer version" notices). No commits.

**Events handled** (`data/chat/ChatReducer.kt`; F = in the captured frames, S = Hermes source only, synthetic test)
| Event | Effect |
|---|---|
| `message.start` (F) | new turn cursor, `running`; user bubble from the admission's `pending.text` (the stream never repeats the prompt) |
| `message.delta` (F) | append to the open text block (last item only); `turn_id/segment/offset` drop re-sent chunks; `text:null` closes the block |
| `message.interim` (S) | seals segment; adds text that did not stream |
| `message.complete` (F) | settles turn; adds final text only if none streamed (`response_transformed` replaces; previewed/reused skipped); `cancelled`/`interrupted` → "Turn stopped"; `status:error` → failed notice; `warning` → info |
| `reasoning.delta` / `reasoning.available` (S) | Reasoning item, collapsed, "Thought for N s" from receive times |
| `thinking.delta` (F) | **Hermes' spinner text** ("(¬_¬) reasoning..."), not reasoning: only sets `thinking` ("Thinking…" line) |
| `agent.step` (F) | step N for "Running · step N" |
| `tool.start` / `tool.progress` (S) / `tool.complete` (F) | card: name, `context` or best arg as summary, Running → Done/Failed (`is_error`), `duration_s`, output (Hermes `{"output","exit_code","error"}` unpacked, `inline_diff` appended) |
| `session.info` A + B (F), `session.title` (S) | merge present keys only; `system_prompt`/`credential_hint` never kept; `running`; started `pending` → user bubble |
| `approval/clarify.request/.settled` (F) | open prompt set → "Hermes is waiting for you" chip |
| `error` / `notice` (S) | notices |
| `session.replay_gap` | feed rebuilds from a full snapshot (same socket) |
| anything else, other sessions, `seq` ≤ last applied | state unchanged (same instance) |

**Subscription** (`data/ResumedSessions.kt`, `data/chat/ChatFeed.kt`): one registry per socket generation, shared by `SessionWatcher` (now claims before resuming, releases on refusal/gap) and the chat. Open → `session.resume` with transcript; frames queued during the call, deduped by `seq`. New socket → `session.events.since` if not `truncated`/`snapshot_required`, then `resume {omit_messages}` only if nobody resumed it on that socket; else full rebuild. Watcher leaves a gap of an open chat to the chat. Feed runs under `flowOn(Dispatchers.Default)`. `AppContainer.openSession` set while the chat route is composed.
**Decision for PM:** a chat opened on a session the watcher already resumed still calls `session.resume` once (only way to get the transcript over the relay). Hermes reuses the same subscription and fanout for a second attach of the same connection (`gateway/session_authority.py:359-371`; resume comment "a snapshot poll on this attachment"), so no duplicate events. The T10 report's "would subscribe twice" was not borne out by the source. Protocol §2 should say so, plus the `thinking.delta` meaning.

**UI** (`ui/chat/`): top bar (Literata 18/24 title, live "Running · step N" + profile · model, link chip only when not online), user bubbles 82 %, Markdown assistant text, reasoning and tool cards fold (300/200 ms design easing, instant when reduced motion; last failed card open), output first 40 lines + "Show all N lines", verdigris non-blinking cursor inline at text end (below inside a code fence/table), follow-at-bottom + "Jump to latest". Session rows open the chat. D8: launch themes paint palette surface bars with matching icon brightness; sessions + chat draw edge-to-edge (lists pad by the nav inset). 27 new EN/ID strings, 6 Material icons.
**Library:** `com.mikepenz:multiplatform-markdown-renderer-m3` **0.41.0** — 0.42.0+ AARs declare `minCompileSdk=37` (checked 0.42–0.46); code in Atkinson Mono, no quote rail, links ink+underline.

**Files** — new: `data/ResumedSessions.kt`, `data/chat/{ChatModel,ChatReducer,HistoryMapper,ToolText,ChatFeed}.kt`, `ui/chat/{ChatScreen,ChatItems,ChatMarkdown,ChatText,ChatViewModel}.kt`, 6 drawables; changed: `RpcFrames.kt` (event `seq`/`replay_epoch`/`admission_id`), `SessionWatcher.kt`, `AppContainer.kt`, `AristotleApp.kt`, `SessionsScreen.kt`, `MainActivity.kt`, `Type.kt`, `Spacing.kt`, themes, strings, `libs.versions.toml`, `app/build.gradle.kts`.

**Tests** (all pass; mutation: merge→replace + no seq dedupe fails 7): `ChatReducerTest` (21: c-client1 pong turn, f-busy 3 turns with tool cards + stopped turn, both f-busy viewers equal, streaming changes only last item, running tool, d/e prompts open/settled, d-client1 small chunks, info merge w/o secrets, **shapeAAfterShapeBBlanksNoField**, thinking, reasoning duration, re-sent chunk, seq dedupe, unknown/other session, malformed payloads, final text, transformed, failed/error notices, interim, progress, captured snapshot + continuation), `HistoryMapperTest` (5), `ToolTextTest` (4), `ChatFeedTest` (7: one resume, frames during resume, events.since + omit resume, watcher-held socket needs no resume, truncated → full, replay gap rebuild, refusal releases), `SessionWatcherTest` +3, `ResumedSessionsTest` (3), `ChatTextTest` (5), `RpcFramesTest` envelope.

**NOT VERIFIED** (phone not attached): no `messages` rows were ever captured — `HistoryMapper` follows Hermes source (`_rows_to_conversation`) only; that the running turn's user row is already in the resume history (ASSUMED, else the prompt is missing on mid-turn open); `reasoning.*`, `tool.progress`, `message.interim`, `error`, a non-empty `events.since` never captured. Device checks: DC-T11a Desktop turn streams on the phone side by side; b long transcript scroll/jank; c auto-follow vs scrolled-up + Jump; d Markdown (code, tables, lists) and cursor placement; e rotation keeps folds and the feed; f Wi-Fi→Tailscale switch mid-turn (catch-up); g dark/light launch + system bars (D8); h no notification for the open session; i 1.3× font. A resume refused after a reconnect leaves an already-loaded chat silently not live (no banner).
