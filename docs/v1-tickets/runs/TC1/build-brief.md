# TC1 build brief — render Hermes system rows like the Desktop (no giant "worker finished" bubbles)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. User report with screenshot: in the phone chat, a Hermes
`[ASYNC DELEGATION BATCH COMPLETE …]` notification shows as a huge user bubble. The Desktop shows it as a one-line card.

Read: `/home/justcallmesu/.hermes/hermes-agent/apps/desktop/src/types/hermes.ts:720-810` (`display_kind`, `display_metadata`),
`apps/desktop/src/components/assistant-ui/thread/process-notification.tsx` (+ its test), `.../thread/response-group.tsx` (+ test),
`apps/desktop/src/lib/chat-messages.ts` (how `hidden` rows are dropped and others mapped), and
`tools/process_registry_notifications.py:330-430` (the kinds and `display_text`). Then the phone's `data/chat/{HistoryMapper,ChatReducer,
ChatModel}.kt`, `ui/chat/ChatItems.kt`, `docs/design.md`. Cite file:line for every kind.

## Do
1. Map every `display_kind` the Desktop knows, from history rows AND live events (find where live frames carry it — cite): `hidden` ->
   not shown at all; `process_complete` and `async_delegation_complete` (and any other notification kind the Desktop renders as a card)
   -> a compact row: icon + `display_metadata.display_text` (fallback: first line of the text, max 1 line, ellipsized), tap to expand the
   full text in monospace (collapsed by default); `steer` and other kinds -> exactly as the Desktop does.
2. These rows are never right-aligned user bubbles and never count as "the user's last message" (D35 logic, unread, notifications).
3. design.md: `surfaceContainerLow`, `medium` radius, onSurfaceVariant text; no verdigris (not live work).
4. Tests: mapping per kind from history and live; hidden rows dropped; long text collapsed; D35 ignores them.

## Hard rules
Files: `android/app/src/main/java/io/github/justcallmesu/aristotle/{data/chat,ui/chat}/**`, their tests, append-only to the two
`strings.xml`, `docs/v1-tickets/runs/TC1/build-report.md`. A TS1 worker edits `ui/settings`, `data/AppSettings.kt`, `ui/theme` and
`ui/AristotleApp.kt` right now: do not touch those. No commits; never git checkout/restore/stash/reset. Do not install on the phone.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TC1` exits 0 (if it fails only in TS1's files, say so).

## Report
`docs/v1-tickets/runs/TC1/build-report.md`, ≤15 lines with the kinds table.
