route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`.

# TY1 — "Running" screen: all running work across profiles (Q81a, Q82 D1)

Read first: `docs/v1-tickets/runs/TX1/report.md` (VERIFIED Hermes facts with file:line — your contract; re-read the cited Hermes
source at /home/justcallmesu/.hermes/hermes-agent for exact field shapes), `docs/spec.md` Q75-Q82, `docs/design/v2/design-notes.md`
(v2 component language), `data/SessionRepository.kt` (profiles.list, session.list), `data/chat/ControlFrames.kt`, the relay RPC client.

## Do
A new screen reachable from Home's ⋮ menu ("Running", with a count badge when > 0) and from the status stack header in a chat ("see all").
Per profile (profiles.list): `session.active_list {profile}` + `subagent.roster {profile}` → rows grouped by profile: session title,
status (working / waiting for you / starting; idle sessions hidden unless they have subagents/background), "N subagents · N background".
Match roster ids on both `id` and `resolved_id` (TX1 §1 risk). Refresh every 10 s ONLY while the screen is visible and online, and on
`sessions.changed`; pull-to-refresh. Tap a row → opens that chat (existing navigation; the chat's status stack already shows details and
Stop). Empty state "Nothing running". Pure tests: fan-out merge, id matching, grouping/sort (waiting first, then working, then by last
activity), poll lifecycle.

## Files
New `ui/running/**`, new `data/RunningWork.kt`, the ⋮ menu entry in `ui/sessions/SessionsScreen.kt` (that menu only), a "see all" link
in `ui/chat/StatusStack.kt`, the route in `ui/AristotleApp.kt` (PairedPage entry + route only), tests.

## Hard rules
Hard rules: no commits; never git checkout/restore/stash/reset. No adb; never connect to the real Hermes or server. Two other
workers build TY1/TY3/TY6 in parallel: edit ONLY your files; strings append-only (EN + ID); in `docs/protocol.md` add ONLY your own new
section (TY1 = §17, TY3 = §18, TY6 = §19) at the end — documenting the Hermes RPCs you use with the TX1 citations.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TY1` exits 0 (if it fails only in another worker's
files, say so). Report `docs/v1-tickets/runs/TY1/build-report.md` ≤12 lines.
