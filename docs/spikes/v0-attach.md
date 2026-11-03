# v0 spike — attach to a live Hermes session from Go

Date: 2026-11-03. Hermes v0.22.5 @717447e (gateway `supervisor: systemd`, multiplexing 6 profiles).
Client: `spikes/v0-attach/` (Go std lib only; hand-written RFC 6455 client). Frames: `docs/spikes/frames/`.
Wire reference for v1: `docs/protocol.md`.

Verdict: **the risky link works.** A same-user Go process, with nothing but socket access, gets a ticket,
opens `/api/ws`, attaches next to other viewers, streams turns, and answers approvals and clarify prompts.
No password, config change or restart was needed.

## What was proven

| # | Claim | Result | Evidence |
|---|---|---|---|
| a | `identify` + `session-ticket` + WS connect | PASS. `runtime_protocol: 1`, `api_origin: http://127.0.0.1:46189` (dynamic port), launch profile `default`, `served_profiles` = default + bkm-reports-bot, developer, pm, reviewer, teacher (profile_id = home path). Negative probes: reused / expired (31 s) / garbage ticket and any Origin header → HTTP 403 at upgrade; unserved profile_id and stale instance_id refused by the socket. | `a-identify.jsonl`, `a-connect.jsonl`, `a-probes.jsonl` |
| b | `sessions` lists existing sessions | PASS. Host ticket, default: live 3 (source desktop 2, gui 1), stored 28 (desktop). `profile:"pm"`: live 1, stored 2. Row fields: `id, message_count, model, running, session_id, source, started_at, title` (+ `resolved_id` in stored). No titles/content recorded. | `b-sessions.jsonl` |
| c | One `aristotle-spike` session; two clients attached; send "Reply with the single word pong."; both stream; one leaves, the other keeps receiving | PASS. Session `local-42f9aa95…94d2` (source `gui`). Both clients got seq 1–12 identically (`message.start` … `message.complete {text:"pong"}`). Client 1 quit; a third short-lived `send` connection submitted again; client 2 got seq 13–24 including `message.delta "pong"` and `message.complete`. | `c-create.jsonl`, `c-client1.jsonl`, `c-client2.jsonl`, `c-send.jsonl` |
| d | Approval round trip | AUTO-APPROVED, then PASS. `rm -r /tmp/aristotle-spike-probe` was auto-approved by smart approvals (tool result: "Command was flagged (delete in root path) and auto-approved by smart approval."; directory gone). Config read only: `approvals` is unset in `~/.hermes/config.yaml`, so the default `approvals.mode: "smart"` applies (`hermes_cli/config_defaults.py:1652-1663`). To get a real prompt without touching config, the spike asked for a `write_file` to `/tmp/aristotle-spike-probe/AGENTS.md` — protected instruction files always ask (`tools/file_tools_write_guards.py:180-193`). Both clients got `approval.request` (choices `once, deny`); client 1 sent `approval.respond deny` → `resolved`; both got `approval.settled`; the write was blocked, nothing created. Client 2's view of the withdrawal is exactly `approval.settled {prompt_id, execution_generation}` — no `request.cancel`. | `d-client1.jsonl`, `d-client2.jsonl` |
| e | Clarify | PASS. Agent's clarify tool → `clarify.request {question, choices:["red (Recommended)","blue"], multi_select:false}` on both clients; client 2 answered `clarify.respond "blue"`; both got `clarify.settled`; reply "blue". | `e-client1.jsonl`, `e-client2.jsonl` |
| extra | Queue, steer, interrupt while busy | PASS. Submit during a running turn → queued, ran after it. `session.steer` → `status:"queued"`, the running turn appended "steered". `session.interrupt` → turn ended `outcome:"cancelled", status:"interrupted"`. Also `commands.catalog`, `session.events.since`, `session.info`. | `f-busy-client1.jsonl`, `f-busy-client2.jsonl` |
| extra | REST with a native-http ticket | PASS (read-only). See Q5. | `q5-rest.jsonl` |

Frames are redacted: no tickets ever written; `system_prompt` values (user memories/skills, ~21 KB) and the
`commands.catalog` values replaced by `<redacted …>`. Only spike-session content remains. `e-*` files are the
tail of the same client run as `d-*` (split at 02:50:40Z), so they start without `gateway.ready`.

## Spike questions (spec §5)

**Q1 — Exact frames.** VERIFIED (captured: `a-*.jsonl`; source: `gateway/control_socket.py:339-397`,
`gateway/run_bootstrap.py:293-308`, `hermes_cli/web_server_boundary.py:156-171`, `gateway/run_api.py:428-488`).
`identify` → `{protocol, kind, pid, hermes_home, profile, supervisor, launch_supervisor, code_sha,
code_version, start_time, instance_id, runtime_protocol, authority_epoch, state, api_origin,
served_profiles[{profile_id, home}], parked_profiles, capabilities}`. Ticket request
`{"protocol":1,"id":1,"verb":"session-ticket","params":{"profile_id","instance_id","purpose":"interactive","scope":"host"}}`
→ `{ticket, expires_in_seconds:30, instance_id, profile_id, runtime_protocol:1}`. WS subprotocols
`hermes-gateway-v1, hermes-gateway-ticket.<ticket>`, server echoes `hermes-gateway-v1`; no Origin header.
Full shapes in `docs/protocol.md` §1.

**Q2 — List, resume, live events; methods needed.** VERIFIED for independent clients (captured `b`, `c`,
`f`); ASSUMED for co-viewing with the Desktop window itself (not tested with the Desktop attached).
`session.list {scope:"live"|"stored"}` lists; `session.resume {session_id, omit_messages}` subscribes the
connection and returns a snapshot (`gateway/session_controls.py:609-704`); events then stream to every
subscriber. The Desktop's own attach is the same call: it maps `session.activate` → `session.resume`
(`apps/desktop/src/api/canonical-protocol.ts:320`) over the same kind of host-scoped interactive ticket
(`apps/desktop/electron/local-gateway.ts:615-620`), which is why the assumption is strong. A connection
must resume before it may submit or answer (`session_controls.py:813-816`, `gateway/session_authority.py:1036`).
Methods v1 needs: `docs/protocol.md` §4–§7.

**Q3 — Approval/clarify round trip, withdrawal.** VERIFIED (captured `d`, `e`; source
`gateway/session_pending_controls.py:18-101`). On canonical sockets approvals and clarify are events
(`approval.request`/`clarify.request`) answered by `approval.respond {session_id, execution_generation,
prompt_id, choice}` / `clarify.respond {…, answer}`; every viewer then gets `*.settled`. That `*.settled`
is the withdrawal signal when the Desktop (or any other viewer) answers first; a late answer returns
`already_resolved`. The `client.capabilities {server_requests:true}` call is still sent (Desktop does) and
governs `srq-` server requests (sudo, secret, batch clarify, window bridges) withdrawn by `request.cancel`
(`tui_gateway/server_requests.py:1-35`) — from source. ASSUMED: approval/clarify events arrive even
without the capability call (they are published to the event stream unconditionally), not tested.

**Q4 — Profiles.** VERIFIED (captured `b-sessions.jsonl`; source `gateway/session_controls.py:167-199`,
`:305-318`, `gateway/runtime_bootstrap.py:21-44`). Yes: the default gateway serves all profiles
(`gateway.multiplex_profiles: true`; identify lists all six). The default socket mints tickets for any
served profile (`profile_id` = that profile's home path). Selection: either (1) a `scope:"host"` interactive
ticket plus `"profile":"<name>"` in each RPC's params (the Desktop way, one socket for all profiles), or
(2) a `scope:"profile"` ticket with the profile's `profile_id` (socket bound to that profile). A
profile-bound socket naming another profile gets `4001 profile_mismatch`. `aristotle-stocks` will be
reachable the same way once it exists (multiplexer hot-serves new profiles; `control_socket.py:534-539`) —
ASSUMED for a profile created later.

**Q5 — Socket-only vs REST, credential.** VERIFIED for the credential and the read routes tried
(`q5-rest.jsonl`; `hermes_cli/dashboard_auth/native_http.py:15`, `:191-217`, `:287-312`); route list from
source. REST accepts a same-user process with header `X-Hermes-Gateway-Ticket: <ticket>` minted with
`purpose:"native-http"`, one ticket per request, loopback peer, no Origin; no header → 401; profile
selector must match the ticket's profile (403 `profile_scope_mismatch`), and `/api/cron/jobs` requires
`?profile=<name>`.
- Socket covers: session list/create/resume/history-in-snapshot, events, submit/queue/steer/interrupt,
  approvals/clarify, rename/pin/archive/model/reasoning (`session.mutate`), slash commands, image/file
  staging (`image.attach_bytes`, `file.attach`), subagents, running a cron job (`cron.submit`).
- REST only (from source): cron job CRUD/pause/resume/trigger (`/api/cron/jobs…`), paged transcript
  (`/api/sessions/{id}/messages`), session search (`/api/sessions/search`), file browsing/download
  (`/api/files…`, `/api/media`), upload endpoints (`/api/chat/image-upload|file-upload`), profiles CRUD.
- Both: rename/pin/archive (`PATCH /api/sessions/{id}` runs the same `sidebar` mutation,
  `hermes_cli/web_routers/sessions.py:930-938`).

**Q6 — Remote terminal.** VERIFIED from source, not exercised (`apps/desktop/electron/terminal-ipc.ts:11`,
`:367-491`; `apps/desktop/src/app/right-sidebar/terminal/terminal-gateway.ts:10-35`). The Desktop's terminal
is a `node-pty` PTY in Electron main driven over Electron IPC (`hermes:terminal:start|attach|write|resize|
cwd|dispose`); for SSH gateways it runs `ssh` in that PTY. Nothing goes through `/api/ws`. The gateway has
only `shell.exec` (one command), the `terminal.read` server request, and the dashboard's `/api/pty` (a TUI
chat PTY). aristotle-server must provide its own PTY channel.

## Surprises
1. Approvals/clarify on the canonical socket are events + `*.respond` methods, not the `srq-` server requests
   the docs describe (`website/docs/developer-guide/programmatic-integration.md:88-101` describes the older
   sidecar path). The brief's "approvals are withdrawn without `client.capabilities`" applies to `srq-` only.
2. Smart approvals auto-approve ordinary dangerous commands (`rm -r` in /tmp). Approval cards on the phone
   will be rarer than expected; protected-file writes and smart-escalated commands still ask.
3. Every `session.info` event and resume snapshot carries the full `system_prompt` (~21 KB, includes the
   user's memories and skill list). It goes to every viewer on every turn.
4. `session.create` with a `title` is create-if-missing: the same title returns the existing session.
5. REST `/api/*` is not open on loopback: it needs its own one-use `native-http` ticket per request.
6. Process-wide `sessions.changed` / `platforms.changed` / `projects.changed` events arrive every few
   seconds with empty payloads on every socket.
7. The Desktop terminal is local Electron, so "remote terminal" is new work for aristotle-server.

## Risks for v1
- Internal protocol (instance_id, epochs, canonical method set) can change on a Hermes update; keep the
  fail-closed check on `runtime_protocol`, `state:"ready"` and `session-authority-v1`, and re-identify on
  every connect (port and instance_id change on restart).
- Relaying frames "unchanged" forwards `system_prompt` (memories) to the phone ~twice per turn: bandwidth
  on mobile and private text on the device. Decide in v1 whether the server strips it (spec §2 says frames
  pass unchanged — needs a decision).
- Logs: frames contain prompt text and memories; the server must never log frame bodies (PROJECT.md rule).
- One ticket per WS connection and per REST request; the relay must mint just-in-time (30 s TTL) and handle
  `1013` (draining) and 403 by re-identifying.
- `execution_generation` / `expected_revision` fences: the phone (or server) must track them from events,
  or controls fail `stale_generation` / `revision_conflict`.
- Desktop co-viewing (Q2) and approval answered on the Desktop (Q3 withdrawal) were proven with CLI clients
  standing in for the Desktop; confirm once in v1 QA with the Desktop window open on a test session.
- Large frames: `session.resume` without `omit_messages` returns the whole transcript in one WS message;
  use `omit_messages` + REST paging for long chats.

## NOT VERIFIED
Desktop window as a co-viewer; approval events without `client.capabilities`; `srq-` server requests
(sudo/secret/batch clarify/window bridges) and `request.cancel`; model/reasoning switch; slash execution;
attachments; subagents; cron via REST writes or `cron.submit`; REST writes; `session.detach`;
`session.replay_gap`; a later-created profile being served.

## Leftovers
The `aristotle-spike` session (`local-42f9aa95…94d2`, profile default, source `gui`) remains in Hermes and
shows in the Desktop sidebar; delete it from the Desktop when no longer needed. `/tmp/aristotle-spike-probe`
was removed by the spike turn. No spike processes are running.
