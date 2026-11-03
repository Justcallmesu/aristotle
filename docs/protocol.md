# Hermes gateway protocol — what Aristotle v1 needs

Source: Hermes v0.22.5 @717447e (`/home/justcallmesu/.hermes/hermes-agent/`, read-only). Paths below are
relative to that tree. Evidence frames: `docs/spikes/frames/`. Spike client: `spikes/v0-attach/`.

Labels: **EXERCISED** = sent by the spike and the answer captured (frame file named).
**From source, not exercised** = read in Hermes code/contracts only.

## 1. Connection bootstrap (EXERCISED — `a-identify.jsonl`, `a-connect.jsonl`, `a-probes.jsonl`)

### 1.1 Control socket
- Path: `$HERMES_HOME/gateway.sock` = `/home/justcallmesu/.hermes/gateway.sock`, mode 0600. If the path is
  longer than `sun_path` the socket lives elsewhere and `$HERMES_HOME/gateway.sock.path` names it
  (`gateway/control_socket.py:116-133`).
- Wire: ONE request per connection; one JSON line in, one JSON line out, then the server closes
  (`control_socket.py:6`, `:415-433`). 2 s read timeout on the request line.
- Peer check: the server reads the peer UID (SO_PEERCRED) and only a same-UID peer gets a `peer_subject`
  (`uid:<n>`); `session-ticket` refuses without it (`control_socket.py:369-371`, `:399-411`).

`identify` (no params):
```json
→ {"protocol":1,"id":1,"verb":"identify"}
← {"ok":true,"protocol":1,"id":1,"result":{
     "protocol":1,"kind":"hermes-gateway","pid":…,"start_time":…,"hermes_home":"/home/justcallmesu/.hermes",
     "profile":"default","supervisor":"systemd","launch_supervisor":"systemd","code_sha":"717447e4…","code_version":…,
     "instance_id":"f3ed47fb…","runtime_protocol":1,"authority_epoch":1,"state":"ready",
     "api_origin":"http://127.0.0.1:46189",
     "served_profiles":[{"profile_id":"/home/justcallmesu/.hermes","home":"/home/justcallmesu/.hermes"},
                        {"profile_id":"/home/justcallmesu/.hermes/profiles/pm","home":"…/profiles/pm"}, …],
     "parked_profiles":{…},"capabilities":["session-authority-v1","durable-admission-v1","event-replay-v1"]}}
```
(`gateway/run_bootstrap.py:293-308`). `profile_id` IS the profile's home path. `api_origin`'s port is
dynamic: re-read it on every connect. Client must refuse `runtime_protocol != 1` and require
`state == "ready"` and capability `session-authority-v1` (Hermes' own check:
`hermes_cli/gateway_runtime.py:98-152`).

`session-ticket`:
```json
→ {"protocol":1,"id":1,"verb":"session-ticket","params":{
     "profile_id":"/home/justcallmesu/.hermes","instance_id":"<identify.instance_id>",
     "purpose":"interactive","scope":"host"}}
← {"ok":true,"protocol":1,"id":1,"result":{"ticket":"<43 chars>","expires_in_seconds":30,
     "instance_id":"…","profile_id":"/home/justcallmesu/.hermes","runtime_protocol":1}}
```
- Envelope keys exactly `protocol, verb, id, params`; params keys ⊆ `profile_id, instance_id, purpose,
  scope, subject, restart_wait` (`control_socket.py:369-397`). Extra keys → `PermissionError`.
- Purposes (`gateway/runtime_bootstrap.py:9-20`): `interactive` (the `/api/ws` socket), `native-http`
  (REST, header), `native-profile-create`, `native-profile-registry`, others not for us.
- `scope`: `profile` (default) or `host`; `host` only with `interactive` (`runtime_bootstrap.py:22`, `:42-44`).
- Ticket: single use, 30 s TTL, stored hashed (`runtime_bootstrap.py:26`, `:58-76`).
- Refusals seen: unknown `profile_id` → `PermissionError: bootstrap binding rejected`; wrong
  `instance_id` → `PermissionError: stale runtime instance` (`a-probes.jsonl`).
- Client checks (as Hermes' own clients do): `ok`, `id == 1`, `instance_id`/`profile_id` echo, `runtime_protocol == 1`.

### 1.2 WebSocket upgrade
```
GET /api/ws HTTP/1.1
Host: 127.0.0.1:<port>
Upgrade: websocket / Connection: Upgrade / Sec-WebSocket-Version: 13 / Sec-WebSocket-Key: …
Sec-WebSocket-Protocol: hermes-gateway-v1, hermes-gateway-ticket.<ticket>
(no Origin header)
→ 101, Sec-WebSocket-Protocol: hermes-gateway-v1
```
- Exactly one `hermes-gateway-ticket.*` entry plus `hermes-gateway-v1` (`hermes_cli/web_server_boundary.py:156-171`).
- Peer must be 127.0.0.1/::1; any Origin header, bad Host, or bad ticket → refused
  (`gateway/run_api.py:428-447`). Observed: reused ticket, Origin header, garbage ticket, ticket after 31 s
  all → HTTP 403 at upgrade (`a-probes.jsonl`). `1013` close = gateway draining/not ready (`run_api.py:389-398`).
- The ticket's profile picks the session authority; `scope:"host"` lets the socket address sibling profiles
  (§3) (`run_api.py:472-488`, `gateway/session_controls.py:167-199`).
- No compression extension is needed (spike offered none).

### 1.3 After accept
1. Server sends `{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"skin":{…},
   "change_events":true,"heartbeat":true,"replay_epoch":"…","session_authority":true}}}` (`tui_gateway/ws.py:643-649`).
2. Client sends `client.capabilities {"server_requests":true}` → result lists server→client request methods
   (`approval, clarify, sudo, secret, terminal.read, window.read, preview.*, tour, vault.*, theme.*, …`),
   `declines_not_shown:true`, `canonical_clarify_batch:true` (`tui_gateway/methods_voice.py:522-540`).
3. Heartbeat: `{"jsonrpc":"2.0","id":N,"method":"gateway.ping","params":{}}` → `{"result":{"ok":true}}`,
   answered inline even while a handler is busy (`ws.py:695-699`). Desktop pings every 15 s (`ws.py:27-29`).

Framing: one JSON-RPC 2.0 object per WS text message (`ws.py:1-4`, `tui_gateway/transport.py:83-95`).
Client ids: integers. Server→client request ids: strings `srq-<12 hex>`.
Errors: `{"error":{"code":4001,"message":"<reason>","data":{"reason":…}}}` for refusals;
`-32601` unknown method; `5001 storage_unavailable` (`gateway/session_controls.py:282-377`).

## 2. Events (EXERCISED — `c-*.jsonl`, `d-*.jsonl`, `e-*.jsonl`, `f-busy-*.jsonl`)

Session event frame (`gateway/session_events.py:48-64`):
```json
{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"local-…",
  "payload":{…},"replay_epoch":"…","seq":19,
  "authority_epoch":1,"execution_generation":3,"admission_id":"…"}}
```
- `seq` is per session and monotonic within `replay_epoch`; reconnect with `session.events.since`.
- Process-wide broadcasts carry `session_id:""` and `payload:{}`: `sessions.changed`, `platforms.changed`,
  `projects.changed` (seen every few seconds). Treat as "refetch list" hints.

Seen in captures, per turn: `session.info` (pending queue, revision, running, and a ~21 KB
`system_prompt` — see risks), `message.start`, `agent.step {iteration}`, `thinking.delta {text}`,
`message.delta {text, turn_id, segment, offset}`, `tool.generating {name}`, `tool.start {tool_id, name,
context, tool_call_id, tool_name, args}`, `tool.complete {… is_error, result, duration_s, inline_diff}`,
`session.usage {usage}`, `message.complete {text, content, admission_id, outcome, status, usage}`
(`outcome` `completed`/`cancelled`, `status` `complete`/`interrupted`), `approval.request`,
`approval.settled`, `clarify.request`, `clarify.settled`.

From source, not exercised: `reasoning.delta`, `tool.progress`, `status.update`, `notice`, `error`,
`session.title`, `session.updated` (another viewer's mutation receipt), `session.replay_gap`
(the viewer's backlog overflowed: re-resume), `request.cancel {id, method, reason}` (withdrawn `srq-`
request), `todo.updated`, `subagent.start|text|tool|thinking|complete|roster`, `cron.changed`,
`notification.show|clear` (`tui_gateway/contracts/events.py`, `gateway/*.py`).

Token-type events (`message.delta`, `reasoning.delta`, `thinking.delta`) are coalesced ~33 ms server-side
(`ws.py:129-130`).

## 3. Profiles (EXERCISED — `b-sessions.jsonl`)
- The default gateway multiplexes every profile (`gateway.multiplex_profiles: true` in config;
  `served_profiles` lists default + 5 named). Named profiles have no socket of their own; the default
  socket mints their tickets (`hermes_cli/gateway_runtime.py:155-159`, `apps/desktop/electron/local-gateway.ts:631-634`).
- Two ways to reach profile `pm`:
  1. Desktop way: one `scope:"host"` ticket for the launch profile, then add `"profile":"pm"` to RPC params
     (`session_controls.py:305-318`; Desktop: `local-gateway.ts:615-620`). EXERCISED: `session.list
     {profile:"pm"}` returned pm's sessions.
  2. A `scope:"profile"` ticket with `profile_id: …/profiles/pm` → that socket is bound to pm. EXERCISED.
- A profile-scoped ticket naming another profile → `4001 profile_mismatch`. EXERCISED.
- `profile` value is the profile NAME (`default`, `pm`, …), resolved by `profile_matches_home`.

## 4. Sessions

| Method | Params | Result | Status |
|---|---|---|---|
| `session.list` | `{scope?: "live"|"stored", limit?: 1..200, include_hidden?, title?, profile?}` (`title` excludes `scope`) | `{sessions:[{session_id,id,title,source,started_at,message_count,model,running,resolved_id?}], scope}` | EXERCISED |
| `session.create` | `{request_id, source:"gui", title?, cwd?, model?, provider?, reasoning?, toolsets?, service_tier?, profile?, …}` (full list: `runtime.describe.session_create.parameters`). A titled create returns the existing session of that title. | snapshot (same as resume) | EXERCISED (`c-create.jsonl`) |
| `session.resume` | `{session_id, omit_messages?, display_projection?, profile?}` | snapshot `{session_id, stored_session_id, messages, message_count, running, authority_epoch, replay_epoch, last_sequence, subscription_id, revision, execution_generation, pending, prompts, info, open_requests, todo_state, pending_connection}` | EXERCISED |
| `session.detach` | `{session_id, subscription_id, end_reason?}` | `{detached}` | from source, not exercised (socket close detaches too) |
| `session.events.since` | `{session_id, last_sequence, replay_epoch?}` | `{events, latest_seq, replay_epoch, truncated, snapshot_required, open_requests}` | EXERCISED (`f-busy-client2.jsonl`) |
| `session.info` | `{session_id}` | inventory (model, provider, tools, skills, cwd, usage, …) | EXERCISED |
| `session.mutate` | `{session_id, request_id, expected_revision, operation, payload, expected_generation?}`; operations `rename {title}`, `archive {archived}`, `sidebar {title?,archived?,hidden?,pinned?,unread?}`, `model`, `reasoning {effort}`, `workspace {cwd}`, `branch`, `compress`, `rewind`, `delete`, … (`gateway/session_mutations.py:18-20`) | receipt `{session_id, operation, revision, …}`; conflicts `revision_conflict`/`stale_generation` carry fresh fences in `error.data` | from source, not exercised |
| `runtime.describe` | `{}` | `{instance_id, profile_id, authority_epoch, capabilities, session_create, skin}` | EXERCISED |

- **A connection must `session.resume` a session before it may submit/control/answer there**:
  `prompt.submit` checks `session_id in self.subscriptions` (`session_controls.py:813-816`) and
  `approval.respond` checks the actor is a subscriber (`gateway/session_authority.py:1035-1037`).
- History for the phone: `session.resume` without `omit_messages` (whole transcript in one frame), or REST
  `GET /api/sessions/{id}/messages?limit&offset&order` (Desktop's paging; §8).
- Several connections may resume the same session; each gets every event. One disconnecting does not
  stop the turn or the others (EXERCISED, `c-client2.jsonl` after `c-client1` quit).

## 5. Prompt, queue, steer, interrupt (EXERCISED — `c-*.jsonl`, `f-busy-*.jsonl`)

`prompt.submit` (`tui_gateway/contracts/canonical_sessions.py:209-234`):
```json
→ {"method":"prompt.submit","params":{"session_id":"local-…","text":"…","submission_id":"<uuid>",
     "attachments":[{"path":"…","mime":"image/png"}]?}}
← {"result":{"admission_id":"…","ref":{"profile_id":"…","session_id":"…"},"status":"queued"|"started"|…,
     "execution_generation":N,…}}
```
`submission_id` is required and is the idempotency key (retry with the same id). While a turn runs a
plain submit is **queued** (FIFO, shown in `session.info.payload.pending`): EXERCISED — "queued" ran after
the running turn.

| Method | Params | Result | Status |
|---|---|---|---|
| `session.steer` | `{session_id, execution_generation, text}` | `{status:"queued"|"redirected"|"rejected", text, execution_generation, authority_epoch}` | EXERCISED: `queued`, text reached the running turn ("steered" appended) |
| `session.redirect` | same | same | from source, not exercised |
| `session.interrupt` | `{session_id, execution_generation}` | handle `{ref, instance_id, authority_epoch, revision, execution_generation, execution_state}` | EXERCISED: turn ended `outcome:"cancelled", status:"interrupted"` |
| `prompt.steer_queued` | `{session_id, admission_id}` | admission | from source, not exercised |
| `prompt.replace_queued` | `{session_id, admission_id, submission_id, text, …}` | admission | from source, not exercised |
| `prompt.cancel` | `{session_id, admission_id}` | admission | from source, not exercised |
| `prompt.receipt` | `{session_id, admission_id, include_result?}` | admission | from source, not exercised |
| `prompt.background` | `{session_id, text}` | `{task_id}` | from source, not exercised |

`execution_generation`: take it from the latest event's `params.execution_generation` or the snapshot;
a stale one is refused `stale_generation`.

## 6. Approvals and clarify (EXERCISED — `d-*.jsonl`, `e-*.jsonl`)

Canonical (authority) sockets get approvals and clarify as **events**, answered by methods
(`gateway/session_pending_controls.py:18-101`), not as `srq-` server requests:
```json
← event approval.request payload {"kind":"approval","prompt_id":"3c94…","execution_generation":4,
     "command":"<write to AGENTS.md>","description":"…","choices":["once","deny"],"timeout_s":300}
→ {"method":"approval.respond","params":{"session_id":"…","execution_generation":4,
     "prompt_id":"3c94…","choice":"deny"}}
← {"result":{"status":"resolved","prompt_id":"3c94…"}}          (already_resolved if another viewer won)
← event approval.settled payload {"prompt_id":"3c94…","execution_generation":4}   (to every viewer)

← event clarify.request payload {"kind":"clarify","prompt_id":"da894a8c36","execution_generation":5,
     "question":"Which do you pick?","choices":["red (Recommended)","blue"],"multi_select":false,"timeout_s":…}
→ {"method":"clarify.respond","params":{"session_id":"…","execution_generation":5,"prompt_id":"da894a8c36","answer":"blue"}}
← {"result":{"status":"resolved","prompt_id":"da894a8c36"}}
← event clarify.settled payload {"prompt_id":"da894a8c36","execution_generation":5}
```
- `choices` for approvals: `once`, `deny`, plus `session`, `always` unless the gate forbids them.
- Withdrawal = `*.settled` for a prompt you did not answer (answered elsewhere, timed out, or turn ended).
- Reconnect: unanswered prompts are in the resume snapshot `prompts` (§4).
- `srq-` server→client requests still exist for `sudo`, `secret`, `vault.*`, batch `clarify`
  (`prompt.submit {clarify_batch:true}`), and window bridges (`terminal.read`, `window.read`, `preview.*`,
  `tour`). Answer `{"jsonrpc":"2.0","id":"srq-…","result":{…}}`, or decline with error code `4404`
  (not shown here) for window bridges; withdrawn by event `request.cancel {id, method, reason}`
  (`tui_gateway/server_requests.py:1-35`, `:117-121`). From source, not exercised.

## 7. Model, reasoning, slash commands, attachments, subagents, cron (from source, not exercised unless noted)
- **Model / reasoning**: `session.mutate` `operation:"model"` payload `{model, provider?, …}` or
  `operation:"reasoning"` payload `{effort}`, with `expected_revision` + `expected_generation`
  (`tui_gateway/contracts/canonical.py:82-95`; Desktop maps `/model`, picker → this,
  `apps/desktop/src/api/canonical-protocol.ts:440-470`). A guarded expensive model answers
  `confirmation_required` with a `confirm` token to resend. Options list: `model.options {session_id?, refresh?}`.
- **Slash commands**: `commands.catalog {}` → `{pairs:[[cmd,desc]…], canon, commands, sub, categories,
  skills, skill_count, plugin_commands}` (EXERCISED, `f-busy-client2.jsonl`, values redacted).
  Run one: `slash.exec {session_id, command:"/…"}` → `{output?, type?, message?, …}` or
  `command.dispatch {name, arg?, session_id?}`. Completions: `complete.slash`.
- **Attachments**: stage then submit. `image.attach_bytes {session_id, content_base64, filename?, ext?}` →
  `{attached, path, mime, …}`; `file.attach {session_id, data_url?|path?, name?}`; or REST
  `POST /api/chat/image-upload` / `/api/chat/file-upload`. Then `prompt.submit attachments:[{path, mime}]`.
- **Subagents**: `subagent.list {session_id}` → `{subagents:[…], delegations:[…]}`;
  `subagent.interrupt {session_id, subagent_id}`; `subagent.steer {session_id, subagent_id, text}`;
  `subagent.tail {session_id, subagent_id}`; events `subagent.*`.
- **Cron**: job list/create/edit/pause/resume/trigger/delete are REST only: `/api/cron/jobs[?profile=<name>]`
  (`hermes_cli/web_routers/cron.py`). WS `cron.submit|status|cancel|recover {job_id, request_id}` run a job
  through the owner (canonical, `tui_gateway/contracts/canonical.py:393-430`).
- **Config**: `config.get {key, session_id?}`, `config.set {key, value, session_id?, scope?}` — Aristotle must
  not use `config.set` for global config (spec: never edit Hermes config).
- **Shell**: `shell.exec {session_id, command}` runs one command in a LOCAL session's cwd
  (`gateway/session_shell.py:22-47`). Not a terminal.

## 8. REST `/api/*` (EXERCISED read-only — `q5-rest.jsonl`)
- Same listener as `/api/ws` (`api_origin`). Credential for a same-user process: header
  `X-Hermes-Gateway-Ticket: <ticket>` where the ticket was minted with `purpose:"native-http"` (no scope).
  One ticket per request (single use). Requires loopback peer and NO Origin header
  (`hermes_cli/dashboard_auth/native_http.py:15`, `:191-217`, `:287-312`).
- No header → 401. The ticket's profile must match any `profile` selector, else 403 `profile_scope_mismatch`;
  `/api/cron/jobs` requires an explicit `?profile=<name>` (`native_http.py:62-111`).
- Routes v1 may need: `GET /api/sessions` (list, pagination), `GET /api/sessions/search?q=`,
  `GET /api/sessions/{id}/messages?limit&offset&order`, `PATCH /api/sessions/{id}` (title/pinned/archived via
  the same `sidebar` mutation), `GET/POST/PUT/DELETE /api/cron/jobs…`, `POST /api/chat/image-upload`,
  `POST /api/chat/file-upload`, `GET /api/media`, `GET /api/profiles`.

## 9. Remote terminal (from source, not exercised)
Hermes Desktop's terminal pane is NOT a gateway feature: Electron main spawns a local PTY with `node-pty`
and drives it over Electron IPC `hermes:terminal:start|attach|write|resize|cwd|dispose`
(`apps/desktop/electron/terminal-ipc.ts:11`, `:367-491`); for an SSH gateway it spawns `ssh` in that PTY.
The gateway only offers `shell.exec` (one-shot), the `terminal.read` server request (agent reads the
Desktop pane), and the dashboard's `/api/pty` (a TUI chat PTY, `hermes_cli/web_routers/chat_ws.py:475`).
→ aristotle-server must host its own PTY for the v1 terminal feature.
