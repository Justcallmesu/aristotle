# T12 build brief — answer Hermes prompts from the phone (approval, clarify, sudo/secret) + notification actions

route: deep (security-relevant: approving commands)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` Q10, Q41 and the approval/clarify requirements;
`docs/design.md` (approval sheet, Q41 button hierarchy) and `docs/design/mockups.html` approval screen; `docs/protocol.md` §3 (server
requests / prompts: approval, clarify, sudo, secret, batch clarify — exact request + response shapes, withdrawal when answered elsewhere),
§2, §6, §12; captured frames in `docs/spikes/frames/` that contain prompts; board rows D32, D35 in `docs/v1-tickets/tickets.md`; then the
app code `data/{SessionWatcher,AlertRouter,ResumedSessions}.kt`, `data/chat/*`, `ui/chat/*`, `background/Notifier.kt`. Verify every
method/field name against the Hermes source (`/home/justcallmesu/.hermes/hermes-agent/`, read-only) and cite file:line in the report.

## Goal
When Hermes asks for something in a session, the user can answer it from the chat on the phone, and the Desktop sees the same answer;
an answer given on the Desktop withdraws the phone's sheet.

## Requirements
1. **Approval sheet** (bottom sheet per Q41): full command / action text in Atkinson Mono (scrollable), the reason/description, buttons
   Deny and Allow once (primary, equal weight), "For this session" and "Always allow" smaller below. "Always allow" asks for a second
   confirmation. Exactly the choices Hermes offers for that request (do not invent choices the request does not carry).
2. **Clarify**: question + choices as rows, free-text answer when Hermes allows it; batch clarify (several questions) as one sheet.
3. **Sudo / secret** requests: phone v1 does NOT collect passwords or secrets. Show "Hermes needs a password/secret — answer on the PC"
   with no input field; never send a value. (Security decision by PM; record it in the report.) This closes D32's "neither notified".
4. **Withdrawal**: when the prompt is settled elsewhere (event from Hermes) the sheet closes with a short "Answered on another screen" note;
   a stale answer that Hermes rejects shows the error and closes.
5. **Notification**: the "Hermes needs you" notification opens the chat with the sheet up. Optional action buttons on the notification only
   for clarify? NO — v1 opens the app; never approve a command from the lock screen.
6. Pending prompts are part of the chat state (reducer), shown as the T11 "Hermes is waiting for you" chip, which now opens the sheet.
7. D35 NITs from `runs/T11/review-report.md` if they are in the files you touch anyway; the missing running-turn prompt (D35) only if cheap.
8. EN/ID strings.

## Tests
Reducer: prompt open/settled/withdrawn for every type; request -> response frame builder per type (exact shape vs protocol §3 and the
source); sudo/secret never produce a response frame with a value; Always-allow requires the confirm step (ViewModel test).

## Hard rules
Files: `android/**`, `docs/protocol.md` §3 only to correct a shape you prove wrong from the source (cite), `docs/v1-tickets/runs/T12/build-report.md`.
No commits; never git checkout/restore/stash/reset. Do not install or run on the phone. Never connect to the real Hermes. No SDK installs.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T12` exits 0.

## Report
`docs/v1-tickets/runs/T12/build-report.md`, ≤35 lines, with the prompt types table (request fields -> response frame, source cite).
