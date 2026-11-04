# T19b build brief — remote terminal, phone side

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` Q7c, Q51 (secret-field safeguards),
Q52–Q55; `docs/design.md`; `docs/protocol.md` §11.2, §12, **§14** (terminal WebSocket: frames, control messages, close codes 4401/4403,
limits); `docs/terminal.md`; `docs/v1-tickets/runs/T19/redteam-report.md` (phone MAJORs: typed secrets, bounded output); then the app's
`data/{ServerClient,LinkManager,RelaySocket,PinnedTls,CredentialStore}.kt`, `ui/settings/*`, `ui/sessions/SessionsScreen.kt`,
`ui/AristotleApp.kt`. The server side (T19a) is in the working tree, uncommitted and under review — read it, do not edit it.

## Goal
When the PC has the terminal enabled and this phone is granted, the user opens a real terminal to the laptop from the phone.

## Requirements
1. **Emulator**: PM technical decision — Termux `terminal-emulator` + `terminal-view` (Apache-2.0) if the artifacts are available from a
   Maven repo usable by this build (JitPack `com.github.termux.termux-app:terminal-view` or similar — verify the licence file and that it
   builds with AGP 9 / compileSdk 36). Host the View in Compose (`AndroidView`). Feed it bytes from the socket instead of a local process
   (implement the session client the library expects; cite how). If Termux can't be used cleanly, STOP and report the options — do not
   hand-write a VT100 emulator in this ticket.
2. **Screen**: "Terminal" entry (sessions top bar overflow or settings), shown only when `/v1/me` says `terminal.enabled && granted`
   (re-read on each connect). One screen per PC; up to 2 tabs (server cap). Top: PC name, connection, Close. Extra-keys row: Esc, Tab, Ctrl
   (sticky), Alt, ↑ ↓ ← →, Home/End, PgUp/PgDn, `|`, `/`, `-`, `~`. Font Atkinson Mono, size pinch/zoom (min/max), light/dark from design.md.
3. **Transport**: a separate pinned WSS to `/v1/terminal` on the current endpoint (same LinkManager address choice, pin and token; not the
   relay socket). Binary frames both ways; send `resize` on layout/rotation/keyboard changes (debounced). Paste: chunk to ≤ 16 KiB frames.
   Close codes: 4401 -> "This phone was revoked"; 4403 -> "Terminal turned off on the PC" (hide the entry); 1006/network switch -> "Terminal
   closed (connection changed)" + Reopen (Q55: no reattach). Show the server's exit code on `exit`.
4. **Safety (red-team phone MAJORs)**: keyboard with no suggestions/learning (`IME_FLAG_NO_PERSONALIZED_LEARNING`, no autocorrect, visible
   password-style input type if the view allows it — verify on Termux's view), FLAG_SECURE on the terminal window by default (no screenshots,
   blank recents), output through a bounded queue (drop-oldest with a "output skipped" marker, never unbounded), no terminal content in logs,
   clipboard copy only on explicit user action.
5. **Lifecycle**: the terminal socket closes when the screen leaves (no background shells from the phone); the background service (T10)
   does not keep terminals open.
6. EN/ID strings.

## Tests
Visibility rule from `/v1/me`; frame encode/decode (binary, resize JSON, exit/closing); paste chunking; close-code mapping; bounded queue;
resize debounce; no reconnect on close (Q55).

## Hard rules
Files: `android/**`, `docs/v1-tickets/runs/T19b/build-report.md`. Do not edit `server/**` or `docs/protocol.md` (report mismatches).
New dependencies: only the Termux libraries (state version + licence). No commits; never git checkout/restore/stash/reset. Do not install
on the phone; never connect to a real server.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T19b` exits 0.

## Report
`docs/v1-tickets/runs/T19b/build-report.md`, ≤30 lines: library + version + licence, how bytes reach the view, safety flags verified,
tests, NOT-VERIFIED.
