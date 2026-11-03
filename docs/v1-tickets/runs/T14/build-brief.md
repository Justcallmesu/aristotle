# T14 build brief — sessions screen: live + stored history, search, pin, rename, new session, profile switch

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` Q4, Q25, Q48; `docs/design.md` +
`docs/design/mockups.html` sessions screen; `docs/protocol.md` §1 (profiles: how the relay reaches a profile, `ErrUnknownProfile`), §2,
§4 (`session.list` scopes live/stored, paging, the 50 cap), §7, §11 (`/v1/me`), §12; board rows D29, D30 in `docs/v1-tickets/tickets.md`;
then `ui/sessions/*`, `data/{SessionRepository,SessionWatcher,ResumedSessions,LinkManager,AppContainer}.kt`, `ui/chat/ChatViewModel.kt`,
and on the server `internal/hermes` + `internal/relay` (profile selection). Verify every method against the Hermes source
(`/home/justcallmesu/.hermes/hermes-agent/`, read-only) and cite file:line.

## Goal
The phone's session list matches the Desktop sidebar: live sessions on top, stored history below (Q48), across the user's Hermes profiles.

## Requirements
1. **List**: live section (running/idle with status line as today) then "History" (stored, newest first) with paging past 50 (load more on
   scroll) using the protocol's real paging (cite). Opening a stored session resumes it the same way the Desktop does (cite the method).
2. **Search** (title/preview, server-side if Hermes has it, else client-side over loaded rows — say which), **pin** and **rename** using
   the Desktop's own methods only (if Hermes has no pin, pin is phone-local and labelled so; never invent a server method), **new session**
   (the Desktop's create method; opens its chat with the composer focused).
3. **Profile switch** (Q25): a profile picker in the top bar listing the user's Hermes profiles (from whatever the protocol/relay offers —
   cite; if the relay needs a change, make the minimal server change and document it in protocol §1/§12). Switching changes the list, the
   watcher's subscriptions (T10) and notifications' profile; chats/prompts keep their profile (T12b matched by profile+session — keep that
   correct). The last profile is remembered.
4. **D29** (T09b review): clear a network-change signal raised during an in-flight connect; try the last-good address before stale
   preferred-kind ones; T09-built pairings without a typed address keep the last-good protected.
5. **D30**: the LAN/Tailscale `http.Server`s in `internal/listen` get the same slog `ErrorLog` bridge as loopback.
6. EN/ID strings; design.md conformance.

## Tests
Repository/ViewModel: live+stored merge (a session moving from stored to live appears once), paging, search, rename/pin frames vs source,
new session, profile switch rewires the watcher (old profile unsubscribed, alerts for the new one), D29 cases; Go test for D30 and any relay
profile change.

## Hard rules
Files: `android/**`, `server/**` (only what req 3/5 need), `docs/protocol.md` §1/§4/§12, `docs/v1-tickets/runs/T14/build-report.md`.
No commits; never git checkout/restore/stash/reset. Do not install on the phone; do not touch port 8760; never connect to the real Hermes.

## Verify
`bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T14` exits 0.

## Report
`docs/v1-tickets/runs/T14/build-report.md`, ≤35 lines, with a methods table (UI action -> method, source cite).
