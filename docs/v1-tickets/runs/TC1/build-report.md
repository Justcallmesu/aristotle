# TC1 build report

Gate `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TC1` → exit 0 (PASS android). New `DisplayRowsTest`: 8/8. Not installed, no commits.

| display_kind | Phone now | Source (Hermes `~/.hermes/hermes-agent`) |
|---|---|---|
| `hidden` | dropped (history and live); legacy `[Background process … heartbeat #n ` user rows dropped | `apps/desktop/src/lib/chat-messages/hydration.ts:31-35,162-171` |
| `process_complete` | `ChatItem.Card` (terminal icon) | `hydration.ts:178-190,333-335,662-665`; title `process_registry_notifications.py:356-367` |
| `async_delegation_complete` | `ChatItem.Card` (task icon) | `hydration.ts:322-331`; title `process_registry_notifications.py:330-344` |
| `model_switch` / `auto_continue` / `personality_switch` | info line "model changed" / "resumed interrupted turn" / "personality changed" | `hydration.ts:310-320` |
| `failed_turn` | turn-failed notice (`display_metadata.error`, else text) | `hydration.ts:216-229,638` |
| `steer` | unchanged: user row (Desktop has no case for it) | `types/hermes.ts:720-731` |

- Card = icon + `display_metadata.display_text` (object or raw-JSON text) → else first non-empty line → else Desktop words; one line, ellipsized; tap opens full text mono (cap 320 dp, scrolls); collapsed by default. `surfaceContainerLow`, `medium`, onSurfaceVariant, no verdigris.
- Live: only `message.start` carries `display_kind`/`display_text`/`text` (`apps/shared/src/gateway-contract.generated.ts:5649-5655`, `gateway/session_automation.py:437-446`); `pending`/`session.info` entries have none (`gateway/session_contract.py:64-72`). `ChatReducer.showAutomation` swaps the bubble `session.info` may have shown for the card (or removes it for `hidden`), either frame order. The phone does not read `inflight`.
- D35: cards are not `ChatItem.User`, so they never count as the last user row; `withRunningPrompt` also skips a pending prompt whose text a hidden/card row after the last typed row already holds (`HistoryMapper.machineTexts`), so no duplicate bubble. Cards add no unread/notification path (none reads `ChatItem`).
- Files: new `data/chat/DisplayRows.kt`; edited `ChatModel.kt`, `HistoryMapper.kt`, `ChatReducer.kt`, `ui/chat/ChatItems.kt`; new test `DisplayRowsTest.kt`. No strings.xml change (reused chat_expand/collapse; notice words are English as on the Desktop). design.md not edited (outside allowed files).
- Gap: a chat opened mid-turn for a hidden turn whose text was never stored yet shows the prompt bubble until the row lands.
