# TM2 — message actions + copy code (Q80a)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q50, Q69, Q75, **Q80**, `docs/design/v2/
design-notes.md` (sheets, shapes), then `ui/chat/{ChatItems,ChatMarkdown,ChatText,ChatScreen,Composer,ComposerState,ChatViewModel}.kt`.

## Do
1. Long-press a user message or an assistant reply (not steps/cards) -> a `ModalBottomSheet` of actions: **Copy** (plain text, Markdown
   source for replies), **Share** (Android share sheet, text), **Select text** (a full-screen selectable view of that message —
   `SelectionContainer`), **Quote** (inserts `> ` quoted text into the composer draft at the cursor, keeps the existing draft),
   **Steer with this** (only while a turn is running and steering is allowed per the existing composer rules; puts the text in the draft
   and arms Steer — it must NOT send by itself). Haptic on long-press. TalkBack: a custom accessibility action per item.
2. Every fenced code block gets a small Copy icon button (top-right, ≥48 dp touch, "Copied" feedback via the system clipboard toast on
   Android 13+ or a snackbar below 13). Copy puts the code only (no fences/language tag).
3. Copied text is marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`) when it came from a sudo/secret context? — those never appear
   as messages; just make sure no prompt-sheet value is reachable from here.
Pure tests: quote formatting (multi-line, existing draft, cursor), code-block extraction, which actions show when (running/not, user vs
assistant).

## Files
`ui/chat/{ChatItems,ChatMarkdown,ChatText,Composer,ComposerState,ChatScreen}.kt`, a new `ui/chat/MessageActions.kt`, tests,
`res/drawable/ic_*` (new only).
Hard rules: no commits; never git checkout/restore/stash/reset. No adb. Another worker edits other android files now (TR7:
`ui/sessions/PcStatusCard.kt`, `SessionsScreen.kt`, `data/PcStatus.kt`; and the other ticket of this pair): edit only your files.
strings append-only (EN + ID, translate properly). Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TM2`
exits 0 (if it fails only in another worker's files, say so). Report `docs/v1-tickets/runs/TM2/build-report.md` ≤12 lines.
