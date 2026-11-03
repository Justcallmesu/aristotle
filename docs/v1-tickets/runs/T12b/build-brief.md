# T12b build brief — T12 fix round (tap-through approval) + same-area minors

route: deep (approves commands). Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/T12/review-report.md`
(all findings), `docs/spec.md` Q50/Q51, then `android/.../ui/chat/{ChatViewModel,PromptSheet,PromptSheetState}.kt`, the manifest,
`MainActivity.kt`, `background/Notifier.kt` and their tests (uncommitted T12 working tree — build on it).

## Fix (each with a test that fails before the fix)
1. **MAJOR-1 (tap-through)**: an answer can only be sent for a prompt the user has had on screen for at least 600 ms. Enforce it in the
   ViewModel (not only by disabling buttons): each prompt gets a shownAt timestamp when it becomes the sheet's current prompt (injectable
   clock); answers for a different or too-new prompt are dropped. When the sheet swaps to the next queued prompt, buttons show disabled for
   that window. Tests: two prompts queued, Once then immediate Once -> exactly one frame; a prompt that appears under the finger ignores a
   tap within 600 ms; after 600 ms it works.
2. **MINOR-1**: after Hermes refuses an answer, or the socket drops after a sudo/secret answer was sent, the "waiting" chip must reopen the
   sheet (prompt back to answerable if Hermes still has it open; otherwise "Answered elsewhere"/closed). No dead chip.
3. **MINOR-2**: `OPEN_SESSION` intents from other apps: make the notification target a non-exported component/alias (or require a
   per-process nonce extra) so only Aristotle's own PendingIntents can raise a sheet; the launcher entry stays exported.
4. NITs: sudo/secret requests matched by profile + session id (not session id alone); with "show" on, disable copy/cut on the secret field.
   (`clarify.lock` in the relay allow-list is a server change — leave it; the PM puts it on the board.)

## Hard rules
Files: `android/**`, `docs/v1-tickets/runs/T12b/build-report.md`. No commits; never git checkout/restore/stash/reset. Do not install on
the phone. `docs/protocol.md` is already edited by the PM: do not touch.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T12b` exits 0.

## Report
`docs/v1-tickets/runs/T12b/build-report.md`, ≤20 lines.
