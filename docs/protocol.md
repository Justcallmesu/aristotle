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

`identify` (no params; VERIFIED `control_socket.py:224`, `:347-351`: the handler is `build_identify_payload()`, which
takes no arguments, so any `params` sent are ignored; `protocol` is not checked for non-private verbs, send `1` anyway):
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
- Envelope keys ⊆ `protocol, verb, id, params`, and `protocol` must equal `1`. `params` must be an object.
  **Required**: `profile_id`, `instance_id`, `purpose`. **Optional**: `scope` (`profile` default | `host`),
  `subject`, `restart_wait` (if present it must equal `"refuse"`; presence makes the ticket refused while a
  Hermes restart wait is open, `control_socket.py:375-378`, `:394` → `runtime_bootstrap.py:40-41`). Any other
  key is refused. VERIFIED `control_socket.py:369-397`. Aristotle sends only the three required keys + `scope`.
- `subject`: omit it. If sent it must be a structured login subject (`auth:v1:[provider, issuer, user]`),
  else `invalid bootstrap subject` (`control_socket.py:387-391`). VERIFIED source; not exercised.
- Errors: every refusal is `{"ok":false,"error":"PermissionError: <message>","protocol":1,"id":…}`
  (`control_socket.py:352-353`; the server then closes the connection). VERIFIED messages (source):

  | `error` message | Cause | Cite |
  |---|---|---|
  | `authenticated runtime bootstrap unavailable` | peer is not the same UID (no `peer_subject`) or no ticket store | `control_socket.py:370-371` |
  | `invalid bootstrap envelope` | extra envelope key, or `protocol != 1` | `:372-373` |
  | `invalid bootstrap parameters` | `params` not an object, a required key missing, an unknown key, or `restart_wait != "refuse"` | `:373-378` |
  | `stale runtime instance` | `instance_id` ≠ the store's (Hermes restarted) | `:379-380` (EXERCISED, `a-probes.jsonl`) |
  | `invalid bootstrap subject` | bad/oversized/non-structured `subject` | `:387-391` |
  | `bootstrap binding rejected` | `profile_id` not served, unknown `purpose`, bad `scope`, or `scope:"host"` without `purpose:"interactive"` | `runtime_bootstrap.py:42-44` (unknown `profile_id` EXERCISED, `a-probes.jsonl`) |
  | `bootstrap capacity exhausted` | too many unredeemed tickets | `runtime_bootstrap.py:48-49` |

  Non-JSON, non-object, or an oversized request line (> 64 KiB) → `{"ok":false,"error":"…"}` or the connection closes
  with no answer (`control_socket.py:36`, `:418-419`, `:327-329`, `:352-353`).
- Purposes (`gateway/runtime_bootstrap.py:9-20`): `interactive` (the `/api/ws` socket), `native-http`
  (REST, header), `native-profile-create`, `native-profile-registry`, others not for us.
- `scope`: `profile` (default) or `host`; `host` only with `interactive` (`runtime_bootstrap.py:22`, `:42-44`).
- Ticket: single use, 30 s TTL, stored hashed (`runtime_bootstrap.py:26`, `:58-76`).
- Refusals seen: unknown `profile_id` → `PermissionError: bootstrap binding rejected`; wrong
  `instance_id` → `PermissionError: stale runtime instance` (`a-probes.jsonl`).
- Client checks (as Hermes' own clients do): `ok`, `id == 1`, `instance_id`/`profile_id` echo, `runtime_protocol == 1`.

**Profile homes (T07b, D18).** When `$HERMES_HOME` is a profile home (`~/.hermes/profiles/<name>`) with no `gateway.sock`,
aristotle-server asks the root home's gateway (`~/.hermes/gateway.sock`), as Hermes' own clients do (sources cited in
`docs/v1-tickets/runs/T07b/build-report.md`), and uses it only if `served_profiles` lists that profile. The launch profile is
then that profile, not `default`. A systemd-started server has no `HERMES_HOME` and uses the root home.

**Profiles from the phone (T14, from source, not exercised).** The relay needs no profile selection of its own: its
socket is host-scoped on the launch profile (§12), so the phone reaches any served profile the Desktop way, a
`"profile":"<name>"` key in the params (§3; `gateway/session_controls.py:305-318`). The roster comes from Hermes over the
same socket: `profiles.list {include_sessions:true}` → `{profiles:[{name, path, display_name, …}], …}`, the launch
profile's own row first (`gateway/session_group_controls.py:45`, `:390-460`; the Desktop asks it too,
`apps/desktop/src/components/assistant-ui/thread/user-message.tsx:146`). Hermes also lists profiles it only found on
disk (parked, not served); a call naming one is refused `4001 profile_mismatch` (`session_controls.py:173-198`). Only the
own row and the siblings this socket routes to carry session fields (`last_session`, `session_group_controls.py:316-370`,
`:424-430`), so the phone offers exactly those. `ErrUnknownProfile` (aristotle-server's `resolveProfile`) is not on this
path; it applies only to REST calls the server makes itself. Names (D11/D25): on the wire `profile` is Hermes' name,
resolved by `profile_matches_home`, so `default` is always the root `~/.hermes`; the phone's "launch profile" is a call
**without** `profile` (the roster's first row), never the string `default`.

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
- Peer must be 127.0.0.1/::1; a bad Host, an **ill-formed** ticket subprotocol (no `hermes-gateway-v1`, more than
  one `hermes-gateway-ticket.*` entry, or an empty ticket), or a non-loopback peer is closed `4403` before accept
  (`gateway/run_api.py:442-447`; `hermes_cli/web_server_boundary.py:160-171`; seen by an HTTP client as 403).
  A **missing** ticket subprotocol (no `hermes-gateway-ticket.*` entry at all, `reason == 'none'`) is NOT closed
  4403: it falls through to the web app like the cases below (`run_api.py:428-441`; D6). The cases the spike
  observed take a different path (VERIFIED by reading source; the 403 status itself is EXERCISED, `a-probes.jsonl`):
  - **Missing ticket subprotocol** → `run_api.py:429` (`reason == 'none'`) hands the request on to the web app.
  - **Origin header present** → `run_api.py:429-441` hands the request on to the web app (`self.app`) unredeemed.
  - **Well-formed but unredeemable ticket** (reused, expired, garbage): both `redeem` calls raise `PermissionError`
    (`run_api.py:450-454`; `runtime_bootstrap.py:61-74`: `invalid bootstrap ticket`, `expired bootstrap
    ticket`, `bootstrap binding rejected`) → `return await self.app(...)` (`run_api.py:455-460`).
  In both cases the web app's own auth gate then returns the HTTP 403. ASSUMED: that gate's body/reason text
  (not captured). Observed: reused ticket, Origin header, garbage ticket, ticket after 31 s all → HTTP 403 at
  upgrade (`a-probes.jsonl`). `1013` close = gateway draining/not ready (`run_api.py:389-398`).
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

Seen in captures, per turn: `session.info` (**two payload shapes, see §2.1**; 4 events per turn), `message.start`, `agent.step {iteration}`, `thinking.delta {text}`,
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

**Background subscription rule (phone, T10).** A socket gets a session's events only after it resumed it (§4). On
every new relay the app calls `session.list` (launch profile) and `session.resume {session_id, omit_messages:true}`
for each `running` session; after `sessions.changed` it lists again and resumes the ones that started since. A
resumed session stays resumed until a `session.replay_gap` for it (Hermes ended this viewer's subscription; the app
removes it from the resumed set and resumes it again on the same socket, which rebuilds the snapshot and re-reads the
open prompts) or until the socket ends. Alerts: `message.complete` = turn finished (not when `outcome`
is `cancelled`); `approval.request` / `clarify.request` (and the resume snapshot's `prompts`, the same dicts,
`gateway/session_pending_controls.py:53-64`) = needs you; `*.settled` removes that notification. Side effect: a resume calls `cancel_persisted_close`, so the phone's automatic
resume can cancel a pending close of a session that a TUI/CLI left mid-turn (narrow case; the Desktop sends no
`end_reason`, unaffected); when the phone socket drops, Hermes arms an orphan close for it again.

### 2.1 `session.info`: two payload shapes (+ two snapshot forms)

`session.info` is the one event type whose `payload` is NOT a fixed set of keys. Hermes publishes it from several
places with different key sets, and the contract model defaults every missing field to `""`/`false`/`{}`
(`tui_gateway/contracts/common.py:73-106`, `SessionLiveInfo`; VERIFIED source). **Client rule (reducer):
MERGE only the keys present in the payload into the session's state. Never replace the state with the payload,
and never apply the contract defaults for absent keys**; replacing would blank model, tools, title and cwd on every
partial update.

**Shape A — partial state update (7 keys, always these 7; all 47 shape-A frames captured have exactly this set).** Published by `_publish_pending`
(`gateway/session_authority.py:442-457`; VERIFIED source). Keys: `stored_session_id`, `pending` (list of queued
admissions), `authority_epoch`, `desktop_protocol`, `running`, `execution_generation`, `revision`. It is the only
place a viewer learns the CAS `revision` for `session.mutate` (comment at `session_authority.py:448-450`) and the
current `running`/`execution_generation`. Carries NO `system_prompt` and NO `credential_hint`. EXERCISED:
`c-client1.jsonl:15,17,29` (and every `session.info` event with `seq` 1, 3, 12 there). Event envelope also has
`replay_epoch, seq, session_id, type`; shape A frames at `:15` have no `authority_epoch`/`execution_generation`
in the envelope (they are inside `payload`), later ones (`:17`) carry them in both.

**Shape B — full inventory (23 keys in the captured end-of-turn event).** Published once at the end of a turn by
`publish_settled_info` = `agent_inventory(agent)` ∪ `local_session_info(...)`
(`gateway/session_local_inventory.py:225-270`; `:209-223`; `gateway/session_local.py:447-487`; VERIFIED source).
Keys seen (`c-client1.jsonl:26`): `provider, tools{group:[names]}, system_prompt, context_length, source, model,
lazy, profile_id, desktop_protocol, profile_name, cwd, reasoning_effort, reasoning_effort_wire, service_tier, fast,
launch_request, title, started_at, last_active_at, yolo, api_mode, tool_count, credential_hint`. EXERCISED. It does
NOT carry `pending`, `revision`, `running`, `execution_generation`, `authority_epoch`, `stored_session_id` (those
come only from shape A), nor `skills`, `usage`, `version`, `mcp_servers`, `enabled_toolsets` (those are in the RPC
result form below). **Carries `system_prompt` (~21 KB; 21682 chars in the spike) and `credential_hint`.**

**Shape B′ — policy repaint (from source, not exercised).** `local_session_info(authority, ref)` alone, with no
`system_prompt` and no `tools`/`context_length`, published after `session.mutate` of `model|reasoning|workspace|
edit_policy` (`gateway/session_mutations.py:353-359`), a policy refresh (`session_policy_refresh.py:294-296`), a
messaging pick (`session_messaging_picks.py:102`) and an auto-title (`run_turn_progress.py:959`). Same merge rule.
It still includes `credential_hint` (`session_local.py:487`, `_classic_cli_fields` `:491-520`). Not in the frames.
(Another source-only variant, `{stored_session_id, pending_submissions}`, is the TUI owner-forward path,
`tui_gateway/owner_forward.py:184`; not on the `/api/ws` canonical path.)

**RPC / snapshot form (full, merge it the same way).** The `session.info {session_id}` result and the `info`
field of `session.create` / `session.resume` results are `snapshot_info` = inventory ∪ `local_session_info` ∪
`usage` (`gateway/session_local_inventory.py:192-206`; `session_controls.py:554-559`, `:609-704`). 34 keys in
`f-busy-client2.jsonl:85` (`result` is the info object itself, not wrapped): shape B's keys plus `skills{group:[names]}`,
`enabled_toolsets, mcp_servers, tool_availability, branch, project, usage, version, release_date, update_behind,
update_command`. `system_prompt` and a non-null `credential_hint` exist only once an agent is built: a lazy
(never-run) session's resume `info` has neither key populated (`c-client1.jsonl:7`: no `system_prompt`,
`credential_hint: null`); the same session after its first turn has both (`c-send.jsonl:8`). EXERCISED.

**Frequency (corrects the earlier "about twice per turn").** EXERCISED, counted per `seq`:
per one-prompt turn each viewer got **3 shape-A events + 1 shape-B event** (4 `session.info`), e.g.
`d-client1.jsonl` turn 1: A at seq 25, 27, 52; B at seq 50; turn 2: A at 53, 55, 98; B at 96
(`d-client1.jsonl:13,15,43,45` and `:59,61,110,112`; same in `d-client2`, `e-*`, `f-busy-*`). So the full
`system_prompt`-bearing shape B is **once per turn** (per viewer). Beyond the turn cycle it also appears: once in
each `session.resume`/`session.create` snapshot `info` (after the first turn), and once per explicit
`session.info` call (`f-busy-client2.jsonl:85`). Shape A repeats more often when the queue changes (an extra A at
seq 132 in `f-busy-client1.jsonl:23` during the queue/steer test). ASSUMED (not isolated in frames): the three
A events per turn correspond to admission, run start and settle.

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
| `session.info` | `{session_id}` only; any other key → `invalid_params` (`session_controls.py:554-559`) | the full inventory object itself (34 keys, incl. `system_prompt`, `credential_hint`; §2.1) | EXERCISED (`f-busy-client2.jsonl:85`) |
| `session.mutate` | `{session_id, request_id, expected_revision, operation, payload, expected_generation?}`; operations `rename {title}`, `archive {archived}`, `sidebar {title?,archived?,hidden?,pinned?,unread?}`, `model`, `reasoning {effort}`, `workspace {cwd}`, `branch`, `compress`, `rewind`, `delete`, … (`gateway/session_mutations.py:18-20`) | receipt `{session_id, operation, revision, …}`; conflicts `revision_conflict`/`stale_generation` carry fresh fences in `error.data` | from source, not exercised |
| `runtime.describe` | `{}` | `{instance_id, profile_id, authority_epoch, capabilities, session_create, skin}` | EXERCISED |

**Session list, the phone's use (T14, from source unless noted).**
- `scope:"live"` (the default) = sessions with a runtime now (running or idle), newest started first, internal
  sources and closed ones left out; `scope:"stored"` = resumable history (closed local sessions, legacy local rows,
  this profile's messaging conversations), newest activity first (`session_controls.py:561-607`,
  `gateway/session_listing.py:27-86`). A stored row may carry `id` (lineage root) ≠ `session_id` = `resolved_id`
  (compression tip, `session_listing.py:21-24`); resume it by `session_id`, as the Desktop does. The stored feed is empty
  unless the socket holds a native ticket of that profile (`session_listing.py:35-37`); the relay's does, and host-scoped
  sibling connections get the same identity (`session_controls.py:136-158`, `:173-192`). The phone merges the two: a
  stored row sharing any id with a live row is dropped (a closed session resumed elsewhere moves feeds).
- **Paging:** there is no offset or cursor. `limit` is 1..200 and anything else is `invalid_params`
  (`session_controls.py:565`). The phone lists live with `limit:200` and history in pages of 50: "load more" sends the
  same call with `limit` 50 larger (50 → 100 → 150 → 200). At 200 it stops and says older sessions are on the PC. The
  Desktop pages with REST `/api/sessions?limit&offset` (`apps/desktop/src/api/sessions.ts:103-121`), which the relay
  does not carry (§8, §12). The `title` param is an exact title lookup, not a search (`:573-590`); there is no WS
  search, so the phone searches the titles it has loaded.
- **Rename:** the Desktop's canonical `session.title` becomes `session.mutate {…, operation:"rename",
  payload:{title}}` with the session's `expected_revision` (`apps/desktop/src/api/canonical-protocol.ts:328-347`,
  `:440-442`). The phone holds no revision for a session it has not opened, so it sends `expected_revision:0`; a
  `revision_conflict` refusal carries the current `revision` in `error.data` and is retried once at it
  (`session_controls.py:845-849`, `_refusal_data` `:22-35`). This is **last writer wins**, unlike the Desktop, which
  never renames without a known revision and does not auto-retry: a Desktop rename made while the phone's dialog is open
  is overwritten (title only; accepted, T14 review). No subscription is needed
  (`gateway/session_mutations.py:39-70`).
- **Model / reasoning switch (T15, from source, not exercised).** The Desktop's picker sends `config.set {key:"model",
  value:"<model> --provider <slug>"}` and its canonical adapter turns it into `session.mutate {session_id, request_id,
  expected_revision, expected_generation, operation:"model", payload:{model, provider?, confirm?, reasoning?}}`
  (`apps/desktop/src/app/session/hooks/use-model-controls.ts:359-365`, `apps/desktop/src/api/canonical-protocol.ts:82-92`,
  `:329-349`, `:471-474`); a level is `operation:"reasoning", payload:{effort}` (`"none"` = thinking off, `null` = back to
  the profile; `hermes_state_mutations.py:13-26`). `expected_generation` is required for these policy operations
  (`gateway/session_mutations.py:149-154`). Answers: a receipt with `revision` (applied; every viewer gets a shape-B'
  `session.info` and the `session.updated` receipt, `:330-359`); `status:"deferred"` with `model`/`effort`, `revision`,
  `execution_generation` while a turn runs (applies at the next turn, `:133-142`, `gateway/session_deferred_pick.py:59-61`);
  `status:"confirmation_required"` with `confirm` (token), `confirm_message`, `target_model`, `target_provider`,
  `warnings` for a guarded target, nothing written; resending with `payload.confirm` applies it
  (`gateway/session_mutation_model.py:339-378`). `revision_conflict` / `stale_generation` refusals carry `error.data
  {reason, revision, execution_generation}` (`gateway/session_controls.py:22-35`, `:844-850`). The revision is learnt
  from shape-A `session.info`, the snapshot and `session.updated.revision` (shape B' has none). The list:
  `model.options {session_id, explicit_only:true}` (keys `profile`, `session_id`, bools `explicit_only`,
  `include_unconfigured`, `refresh`; `gateway/session_config.py:213-236`) → `{providers:[{slug, name, models[],
  aliases?, capabilities{model:{reasoning, can_disable_reasoning?, fast}}, unavailable_models?, warning?, …}], model,
  provider}` (`apps/shared/src/gateway-contract.generated.ts:822-862`).
- **Pin:** Hermes stores one (`session.mutate sidebar {pinned}`, REST `PATCH pinned`), but `session.list` rows carry no
  `pinned` (`tui_gateway/contracts/canonical_sessions.py:171-181`) and the Desktop keeps its pins locally, mirrored over
  REST (`apps/desktop/src/store/session-pin-sync.ts:1-21`). The phone's pins are its own and are labelled so.
- **New session:** `session.create {request_id, source:"gui", profile?}` (EXERCISED `c-create.jsonl`), no `title`.
- **Leaving a profile:** `session.detach {session_id, subscription_id, profile?}` without `end_reason` is a viewer leaving,
  not a close (`session_controls.py:714-737`); `subscription_id` comes from the resume answer.

| Method | Params | Result | Status |
|---|---|---|---|
| `profiles.list` | `{include_sessions?, profile?}` (any other key → `invalid_params`) | `{profiles:[{name, path, is_default, display_name, …, last_session?}], bot_mode_protocol, install_id}` | from source, not exercised |

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
| `session.steer` | `{session_id, execution_generation, text}` (+ `profile`; any other key, a blank `text` or a non-int generation → `invalid_params`) | `{status:"queued"|"redirected"|"rejected", text, execution_generation, authority_epoch, delivered?}` or `{voice_stopped:true}`. `rejected` with `delivered:false` = no agent saw it (safe to send as the next prompt); a plain `rejected` may follow delivery, never re-send it. No idempotency key (T13: `gateway/session_busy_controls.py:175-217`, `tui_gateway/contracts/canonical_sessions.py:273-287`) | EXERCISED: `queued`, text reached the running turn ("steered" appended) |
| `session.redirect` | same | same | from source, not exercised |
| `session.interrupt` | `{session_id, execution_generation}` | handle `{ref, instance_id, authority_epoch, revision, execution_generation, execution_state}` | EXERCISED: turn ended `outcome:"cancelled", status:"interrupted"` |
| `prompt.steer_queued` | `{session_id, admission_id}` | admission | from source, not exercised |
| `prompt.replace_queued` | `{session_id, admission_id, submission_id, text, …}` | admission | from source, not exercised |
| `prompt.cancel` | `{session_id, admission_id}` | admission | from source, not exercised |
| `prompt.receipt` | `{session_id, admission_id, include_result?}` | admission | from source, not exercised |
| `prompt.background` | `{session_id, text}` | `{task_id}` | from source, not exercised |
| `slash.exec` | `{session_id, command, profile?}` (`command` = the line without its slash, as the Desktop sends it; any other key → `invalid_params`, `gateway/session_commands.py:148-155`) | `{type:"exec", output}`, or `{type:"skill"\|"send", message, display, notice?}` = a turn the CLIENT submits with its own `submission_id` (`:418-529`); a command a shared-gateway session cannot run → `unsupported_command` with `error.data.detail` hint (`:118-126`, `:456-458`) | from source, not exercised (T15) |
| `commands.catalog` | `{session_id?, profile?}` only (`gateway/session_discovery.py:8-35`) | `{pairs:[[cmd, desc]], canon{alias→cmd}, commands{cmd:{argument_mode, desktop, desktop_subcommands?}}, categories, skills{cmd:{usage, origin}}, skill_count, sub, warning, plugin_discovery_error, plugin_commands}`; `desktop` null = offered, `"hidden"`, or the reason the Desktop has no surface (`tui_gateway/command_discovery.py:137-149`, `:206-250`) | EXERCISED keys (`f-busy-client2.jsonl:80-81`, values redacted) |

`execution_generation`: take it from the latest event's `params.execution_generation` or the snapshot;
a stale one is refused `stale_generation`.

**Attachments (T16, from source, not exercised).** Stage each item on the session socket, then submit. This is the
Desktop's path for bytes that are not on the gateway's disk (`apps/desktop/src/app/session/hooks/use-prompt-actions/index.ts:148`,
`:165-230`); the local Desktop stages images over REST `POST /api/chat/image-upload` instead (`upload-server-image.ts:34-40`,
when it owns the composer queue), which the relay does not carry and does not need: both write into Hermes' own tree, by Hermes.
```json
→ {"method":"image.attach_bytes","params":{"session_id":"…","content_base64":"<b64>","filename":"IMG_1.jpg"}}
← {"result":{"attached":true,"path":"<home>/cache/images/upload_….jpg","mime":"image/jpeg","name":"…","bytes":N,…}}
→ {"method":"file.attach","params":{"session_id":"…","name":"report.pdf","data_url":"data:application/pdf;base64,<b64>"}}
← {"result":{"attached":true,"name":"report.pdf","path":"…","ref_path":"…","ref_text":"@file:<path>","uploaded":true}}
→ {"method":"prompt.submit","params":{"session_id":"…","submission_id":"…","text":"@file:<path>\n\n<typed text>",
     "attachments":[{"path":"<home>/cache/images/upload_….jpg","mime":"image/jpeg"}]}}
```
- `image.attach_bytes` params closed to `session_id`, `profile`, `content_base64`|`data`, `filename`, `ext`; Hermes trusts
  the filename's extension first, so it must match the bytes; only PNG/JPEG/GIF/WebP, ≤ 25 MiB (`gateway/session_images.py:8-45`,
  `tui_gateway/prompt_attachments.py:21`, `:91-99`). `file.attach` params closed to `session_id`, `profile`, `path`, `data_url`,
  `name`, `staged_upload`; a `path` names a file on the PC, so the phone never sends one (`gateway/session_ancillary.py:137-180`).
- `prompt.submit attachments`: ≤ 10 entries, each exactly `{path, mime}`, `mime` ∈ png/jpeg/gif/webp, `path` directly in
  Hermes' image staging dir (`gateway/session_ingress_media.py:122-156`). Files ride in `text` as leading `@file:` lines; text
  is `[refs, typed].join("\n\n")`, or `What do you see in this image?` for images alone (`submit.ts:289-303`, `:1080-1088`).
- Retry: same `submission_id` and same text. Hermes recognises a retried admission by the images' sha256, not their staging
  names (`session_ingress_media.py:158-181`), so images may be staged again; a file's `ref_text` must be reused (staging it
  again may rename it and change the text).
- No chunked upload exists: each staging call is one message (the Desktop's "one-shot file.attach", `apps/desktop/src/store/updates.ts:114`).
  Through the relay that is one ≤ 1 MiB frame (§12).
- Stored user rows name their attachments in text: native-vision rows end `\n\n[Image attached at: <home>/…/native-inputs/<sha256>/<name>]`
  per image, then one `[screenshot]` line per image (`agent/image_routing.py:542-563`, `agent/session_persistence.py:122-141`);
  older rows carry `@image:<path>` lines; files are the leading `@file:` block. The Desktop reads them this way
  (`apps/desktop/src/lib/chat-messages/hydration.ts:40-50`, `:510-521`). A turn's `pending` text has the `@file:` lines only.

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
  `tour`). Frame `{"jsonrpc":"2.0","id":"srq-…","method":"sudo","params":{"session_id":…,"command":…}}`, written to
  every viewer attached to the session (`gateway/session_server_requests.py:171-178`). Withdrawn by the session event
  `request.cancel {id, method, reason}` (reason `resolved` when some viewer answered, `timeout`, `interrupted`, …;
  `:188-208`, `tui_gateway/contracts/server_requests.py:364-379`). Still open ones are in `open_requests` of
  `session.resume` / `session.events.since` (`session_server_requests.py:125-134`, `session_controls.py:700-703`,
  `:745-751`). From source, not exercised.
  - **Corrected (T12):** on the canonical owner (`/api/ws`, `session_authority:true`) a response frame is routed by
    `session_controls.py:283-289` → `_resolve_response` (`:909-923`) → `session_server_requests.resolve`
    (`:78-97`), and ANY response settles the request for every viewer: an `error` frame (including `4404`) settles it
    with `""`. The `4404` "not shown" decline counting exists only in the sidecar (`tui_gateway/server_requests.py:249-278`).
    So a client must never answer or decline a window bridge it does not own.
  - `sudo` / `secret` answer: `{"jsonrpc":"2.0","id":"srq-…","result":{"value":"<value>"}}` (`ValueResult`,
    `tui_gateway/contracts/server_requests.py:26-30`, `:206-223`); `value:""` = skipped/declined
    (`gateway/session_sudo_prompt.py:22-26`, `gateway/session_secret_prompt.py:45-46`). No reply comes back.
  - Batch `clarify` (`params.questions[{qid, question, choices?, multi_select}]`, `answers` on replay): lock each answer
    with `clarify.lock {request_id, question_id, answer}` (no `session_id`; `tui_gateway/contracts/prompt_voice.py:265-285`)
    → `{status:"ok", remaining:[…]}` or `{status:"expired"}`; the last lock settles it (`gateway/session_clarify_lock.py:15-38`).
  - A multi-select clarify answer (event or batch) is a JSON array string of labels (`tools/clarify_gateway.py:213-230`,
    `tools/clarify_tool.py:37-47`).

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

## 10. Relay redaction (spec Q34)
aristotle-server passes Hermes frames to the phone unchanged except these two fields (spec §2, Q34). VERIFIED
that the fields exist in these places (scan of every frame in `docs/spikes/frames/`; source cites in §2.1):

| Field | Where it appears (path in the JSON-RPC frame) | Seen in frames | Replacement |
|---|---|---|---|
| `system_prompt` | event `session.info` shape B: `params.payload.system_prompt` | `c-client1.jsonl:26` (16 events) | remove the key, add `params.payload.system_prompt_chars` = length of the string (characters) |
| `system_prompt` | result of `session.info`: `result.system_prompt` | `f-busy-client2.jsonl:85` | same, `result.system_prompt_chars` |
| `system_prompt` | result of `session.create` / `session.resume` / `session.activate`: `result.info.system_prompt` | `c-send.jsonl:8` (5 frames) | same, `result.info.system_prompt_chars` |
| `credential_hint` | event `session.info` shapes B and B′: `params.payload.credential_hint` | `c-client1.jsonl:26` (16 events) | remove the key |
| `credential_hint` | result of `session.info`: `result.credential_hint` | `f-busy-client2.jsonl:85` | remove the key |
| `credential_hint` | result of create/resume/activate: `result.info.credential_hint` (value `null` for a lazy session, `c-client1.jsonl:7`) | 8 frames | remove the key |

- `system_prompt` -> `system_prompt_chars` (integer; 0 if the original was empty or `null`).
  `credential_hint` -> key removed, no replacement. Shape A (`session.info` with 7 keys) carries neither field and
  passes unchanged.
- The redaction applies by field name at those paths only; it does not touch message text, tool output or other
  fields. `info.skills` / `info.tools` (names only) are NOT redacted (see `docs/spikes/v0-attach.md`).
- ASSUMED, not in frames: `session.events.since` returns replayed events, so a replayed shape-B `session.info`
  would sit at `result.events[].params.payload.{system_prompt,credential_hint}`. The relay should apply the same
  event rule to every element of `events[]`. Not captured: the only `session.events.since` frame returned
  `events: []` with `truncated: true, snapshot_required: true` (`f-busy-client2.jsonl:83`).
- ASSUMED: Hermes' own `credential_hint` value is `mask_secret(api_key)` (`gateway/session_local.py:511-514`), i.e.
  partial key characters, so it must not reach the phone or logs. The committed frames show the literal string
  `"[REDACTED]"` (the spike client already replaced it), so the real masked shape is not in the repo.
- The phone gets `system_prompt_chars` only so a UI can show that a prompt exists; nothing else about it.

## 11. aristotle-server API (phone side)
What the app talks to on the laptop (T05/T05b/T07c; code in `server/internal/{listen,httpapi,pairing,tlscert}`).
This is aristotle-server's own API, not Hermes'. No cleartext between phone and laptop on any network (spec Q49):
every phone-facing listener is HTTPS with the same pinned certificate (§11.3).

### 11.1 Listeners
All listeners use the same port (config `port`, default 8760). The server never binds `0.0.0.0`/`::`.

| Listener | Bound on | Scheme | Peers admitted (else `403`, text body `forbidden`) |
|---|---|---|---|
| LAN | each RFC1918 IPv4 address of an up and running (carrier) physical interface (`/sys/class/net/<if>/device` exists), or only the interfaces named in config `lan_interfaces` | HTTPS (`wss://` for §12), self-signed cert, pinned | RFC1918 peers inside that interface's own subnet (on-link) |
| Tailscale | each 100.64.0.0/10 or fd7a:115c:a1e0::/48 address of a TUN interface named `tailscale*` | HTTPS (`wss://` for §12), the same cert as LAN, same pin | tailnet peers (same two ranges) |
| Loopback | config `host` (127.0.0.1 or ::1) | HTTP | local only (not for phones); serves `/healthz` only |

- Each listener is also pinned to its interface (`SO_BINDTODEVICE`), so a packet arriving on another interface never reaches it. The interface pin applies on Linux only; elsewhere the peer gate is the only interface check.
- A LAN interface must be up and running (has carrier) to get a listener; the same holds for Tailscale.
- LAN and Tailscale listeners speak TLS only. A plain-HTTP request to them gets Go's fixed `400` ("Client sent an
  HTTP request to an HTTPS server") from the TLS layer and never reaches the peer gate or a route. Without a TLS
  configuration no LAN or Tailscale listener is bound at all.
- The same address ranges on any other interface (a hotel or carrier CGNAT handing out 100.x) get no listener.
- `aristotle-server pair` prints the pairing QR code (§11.4; `--no-qr` leaves it out), then the code, every listener
  URL (all `https://`) with its interface: the default-route LAN interface first, other LAN interfaces next, Tailscale
  last; then the one certificate fingerprint, valid for every address. Interfaces are re-scanned every 10 s.

### 11.2 Endpoints (LAN and Tailscale listeners)
| Method, path | Auth | Success | Errors |
|---|---|---|---|
| `GET /healthz` | none | `200 {"status":"ok","version":"…"}` | — |
| `POST /v1/pair` | none (one-time code in the body) | `201 {"device_id":"dev_<12 hex>","token":"<43 chars>","addresses":[…]}` | `400`, `401`, `500` (below) |
| `GET /v1/me` | Bearer | `200 {"device_id":"…","name":"…"}` | `401` |
| `GET /v1/addresses` | Bearer | `200 {"addresses":[…]}` | `401` |
| any other `/v1/*` | Bearer | `404` with a valid token | `401` without one |

All JSON responses carry `Cache-Control: no-store`. Error bodies are `{"error":"<message>"}`.

There is no upload endpoint (T16): pictures and files go to Hermes as `image.attach_bytes` / `file.attach` frames on the
relay socket (§5 Attachments, §12 Limits), and Hermes writes them into its own tree. aristotle-server stores no attachment.

**`POST /v1/pair`** request body (JSON object, unknown fields refused, at most 4 KiB, must arrive within 10 s):
`{"code":"<8 chars>","device_name":"<optional, ≤64 chars>"}`.
- `code`: the code `aristotle-server pair` printed. Case-insensitive; hyphens and spaces ignored; `O`→`0`, `I`/`L`→`1`.
  Never put it in the URL or query.
- `device_name`: invalid UTF-8 bytes, control (Cc), format (Cf: bidi overrides/isolates, zero-width) and line/paragraph
  separator characters are stripped, then spaces trimmed; more than 64 characters left -> `400`. A name given on the laptop
  (`pair --name`) wins; with neither, the name is `device`.
- `400`: body not that JSON object, `code` missing, name too long, body over 4 KiB or too slow. A `400` does not
  count as a wrong attempt.
- `401 {"error":"invalid or expired pairing code"}`: no active code, expired, wrong, already used, or this peer is
  locked out. The cases are not distinguished.
- `500`: the device could not be stored; run `pair` again.
- Code rules: one active code at a time (a new `pair` replaces it), single use, expires 5 minutes after issue.
  Wrong attempts are counted per peer IP: after 5 that IP cannot redeem this code, other IPs still can. After 20
  wrong attempts in total the code is invalidated.

**Addresses** (`addresses` in the pair response and in `GET /v1/addresses`): every listener bound right now, in the
order `pair` prints them (default-route LAN first, other LAN next, Tailscale last), each
`{"url":"https://<ip>:<port>","kind":"lan"|"tailscale","iface":"<interface>"}`; IPv6 hosts are bracketed. Always a
JSON array (`[]` when nothing is bound). One pin covers all of them (§11.3). The phone stores the whole list at
pairing and refreshes it with `GET /v1/addresses` after each connect, so a DHCP change or a Tailscale address
added later reaches it without pairing again. The phone checks each entry itself (HTTPS, an RFC1918 or tailnet IP
literal whose range matches `kind`) and drops any other.

**Bearer auth.** Every `/v1/*` route except `POST /v1/pair` needs `Authorization: Bearer <token>` (scheme
case-insensitive). The token is 32 random bytes, base64url without padding (43 chars). The server stores only its
SHA-256. Unknown or revoked token -> `401 {"error":"unauthorized"}` with `WWW-Authenticate: Bearer`.
A revoke (`aristotle-server devices revoke <id>`) applies from the next request on.

**`401` vs `403`.** `403` comes from the listener's peer gate, before any route runs: the phone's address is not
allowed on that listener (wrong network, or a LAN peer outside the interface's subnet). Re-pairing does not help;
use another address. `401` means the code or token was refused: pair again (or re-enter the code).

### 11.3 Certificate pin (every listener)
The certificate is self-signed (ECDSA P-256, 20 years) and served by every LAN and Tailscale listener. It is created
on first start and kept across restarts and IP changes. The app trusts it by pin, not by CA or hostname: the IP SANs
(LAN and Tailscale addresses seen when it was created; LAN only for a certificate from before T07c) are only a
courtesy and can be stale.

- **Fingerprint** = SHA-256 of the certificate's DER-encoded SubjectPublicKeyInfo, shown as uppercase hex in
  space-separated groups of 4 characters (16 groups), e.g. `7E54 D87B … BCA7 EAA7`. `pair` prints it.
- **Android:** `val spki = (cert as X509Certificate).publicKey.encoded` (the DER SubjectPublicKeyInfo), then
  `MessageDigest.getInstance("SHA-256").digest(spki)`, uppercase hex, grouped by 4. An OkHttp `CertificatePinner`
  pin `sha256/<base64>` is the base64 of the same 32 bytes.
- On the manual pairing path the app shows this fingerprint for the user to compare with the laptop's, for a LAN and
  a Tailscale address alike, and stores it with the device token. On the QR path (§11.4) the fingerprint comes from
  the code on the laptop's screen, so there is no compare step; the app shows it small for reference. One pin per PC
  covers all its addresses. The pin changes only if the user deletes the key pair, and then every phone must re-pair.
- The app never sends cleartext: `http://`/`ws://` URLs are refused before a socket opens, and the Android network
  security config forbids cleartext with no exceptions. A pairing stored by an earlier build with an `http://`
  Tailscale URL is read as `https://` on the same host and port, keeping its pin; one without a pin must pair again.

### 11.4 Pairing QR code
`aristotle-server pair` prints, on its own stdout only (never the server log), a QR code holding one URI (T08):

`aristotle://pair?v=1&c=<code>&fp=<fingerprint>&a=<url>&a=<url>…`

| Key | Value |
|---|---|
| `v` | payload version, `1`. The app refuses any other value ("update the app"). |
| `c` | the one-time code as issued: 8 characters of `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (§11.2 rules: single use, 5 minutes) |
| `fp` | the certificate fingerprint (§11.3) as 64 hex digits, uppercase, no spaces |
| `a` | one per address, the same URLs in the same order as `GET /v1/addresses` (`https://<ip>:<port>`, IPv6 bracketed), at least 1, at most 8 |

- Each value is query-escaped (Go `url.QueryEscape`: `:` -> `%3A`, `/` -> `%2F`, `[` -> `%5B`, `]` -> `%5D`).
  Keys appear in the order above, each once except `a`. No device token or any other secret: the code is the only
  one, and it is the same code `pair` prints for typing.
- Encoded at error correction level L and drawn with Unicode half blocks (black on bright white, 4-module quiet
  zone) so it fits 80 columns. If all addresses would make it wider, the last ones are left out (`pair` says how
  many); the pair response (§11.2) still gives the phone the whole list.
- The app (Google code scanner, QR only, no camera permission) accepts exactly this form: `aristotle://pair?` prefix,
  the four keys and nothing else, `v=1`, `c` and `fp` as above, each `a` an `https://` URL whose authority is an IP
  literal with an explicit port, in the LAN or tailnet ranges (§11.2), at most 4096 characters in all. Anything else
  is refused with a message. A valid scan then pairs exactly like the manual path, pinned to `fp`, trying the `a`
  addresses in order; the first that pairs is the stored typed address, and the scanned list is stored with the
  pair response's.

## 12. Relay socket (`/v1/ws`)
The phone's live Hermes session socket, through aristotle-server (T06; code in `server/internal/relay`). One phone
socket = one fresh Hermes `/api/ws` socket (own ticket, `scope:"host"`, launch profile; §1, §3). Frames pass through
unchanged both ways, except §10's redaction on the way to the phone. No method allowlist (spec Q7d).

**Profiles (T14).** The relay has no profile selection and needs none: because its ticket is host-scoped, a phone frame
whose params name `"profile":"<name>"` is routed by Hermes to that served profile (§1 "Profiles from the phone", §3), and
`profiles.list` lists them. A phone frame without `profile` acts on the launch profile. Response frames to `srq-`
requests name no profile; Hermes settles them as whichever sibling connection attached the session
(`gateway/session_controls.py:909-919`). REST (§8: search, offset paging, the pin flag) is not carried by the relay.

**Upgrade.** `GET /v1/ws` as `wss://` on the LAN and Tailscale listeners alike (pinned cert §11.3; there is no
`ws://`), HTTP/1.1 upgrade (ALPN `http/1.1`; OkHttp does this). Header `Authorization: Bearer <device token>`, as for every
`/v1/*` route. No subprotocol, no compression. An `Origin` header is optional; if sent, its host must equal `Host`, else
`403` (the WebSocket library's check; with Bearer-only auth it is the cross-site defence). Missing/unknown/revoked token -> HTTP `401`
before any upgrade (Hermes is not contacted); a peer outside the listener's range -> `403` (§11.2). The request log
line is written at upgrade time (status 101).

**After accept.** aristotle-server dials Hermes (identify, ticket, upgrade, `client.capabilities
{"server_requests":true}` and its ack; §1). Then the phone receives, in order, whatever Hermes sent first
(`gateway.ready`, §1.3) and every later frame. The connector's own `client.capabilities` request (`id` 1) and its
ack are never shown to the phone.

**Capabilities: the phone need not send any.** Hermes records them per socket (`tui_gateway/methods_voice.py:522-541`):
each `client.capabilities` call updates only the keys it names and is answered normally, so a phone that sends one
gets a normal result. Consequences:
- Hermes writes `srq-` requests (`sudo`, `secret`, `vault.*`, batch `clarify`, window bridges) to every attached
  viewer (§6). The phone answers only `sudo`/`secret` (`result.value`, `""` = skip) and batch `clarify` (via
  `clarify.lock`). It never answers or declines window bridges or `vault.*`: on the owner, any response (`error`/`4404`
  included) settles the request for every viewer. Unanswered requests wait for another viewer or their deadline. The
  owner's fanout ignores the per-socket `server_requests` flag (`tui_gateway/transport.py:209-211`,
  `session_server_requests.py:171-178`), so `server_requests:false` does not fail them early (that is sidecar-only,
  `tui_gateway/server_requests.py:249-278`). Corrected 2026-11-03 from the T12 review; the old text said to decline
  window bridges with `4404`.
- **`desktop_local` is refused (spec Q43).** The relay socket holds a native ticket, so Hermes would treat a phone
  claiming `desktop_local` as the local Desktop app and run its first-boot work (`hermes_cli/process_identity.py:425-431`,
  `tui_gateway/methods_onboarding.py:107`). A phone frame whose decoded `method` is `client.capabilities` and whose
  decoded `params` object has the key `desktop_local` (any value, `false` too; escaped spellings such as
  `desktop\u005flocal` count) closes the socket `1008 desktop_local_refused`, and the frame never reaches Hermes. Keys
  are matched exactly, as Python does. A frame Go's JSON parser rejects but Python's may accept (`NaN`, `Infinity`,
  leading Unicode spaces) is refused the same way when its bytes contain `capabilities` or a `\u` escape.
- JSON-RPC ids are the phone's own; reusing id `1` is safe (the connector's request is answered before the
  phone's first frame is forwarded).

**Close codes** (sent by aristotle-server to the phone; Hermes' own codes on an established socket pass through):

| Code | Reason text | When | Phone should |
|---|---|---|---|
| `1000` | Hermes' reason | Hermes closed normally | reconnect when needed |
| `1001` | `going_away` | aristotle-server is stopping (also: a new socket during shutdown) | retry with backoff |
| `1003` | `text_frames_only` | the phone sent a binary frame | fix the client |
| `1008` | `desktop_local_refused` | the phone's `client.capabilities` named `desktop_local` (see Capabilities) | fix the client; never send it |
| `1009` | `read limited at …` | a phone frame over 1 MiB (sent by the WebSocket library) | shrink it; an attachment must fit one frame (see Attachments below) |
| `1011` | `pong_timeout` | within 15 s of a ping (sent every 25 s), no byte came from the phone and no write that had to wait for it got bytes out (see Keepalive) | reconnect; answer every ping and never block the read thread |
| `1013` | `try_again` / Hermes' reason | Hermes draining or not ready, at dial (`ErrDraining`) or later (Hermes sent 1013) | retry with backoff |
| `4401` | `revoked` | the device was revoked (`devices revoke`); every live socket of it closes within 1 s. A phone that has not read the 4401 within 0.5 s (not reading, or still draining buffered data) gets a TCP close instead | re-pair |
| `4409` | `superseded`, reason `superseded by a newer connection` | the same device opened a relay on the other listener (lan vs tailscale); its older sockets there are closed. Same-listener sockets are untouched, and the newer socket is admitted before the 4430 cap counts the old ones | do not reconnect this socket (the phone has moved networks) |
| `4429` | `rate_limited` | more than 600 phone frames in 60 s on this socket | reconnect later, send less |
| `4430` | `too_many_connections` | the device already has 4 open relay sockets | close one |
| `4502` | `hermes_unavailable`, `hermes_unavailable: refused (4403)`, `hermes_unavailable: connection lost` | any other dial failure; Hermes closed with 4403; Hermes vanished without a close frame | retry with backoff |
| `4503` | `hermes_not_running` | the Hermes control socket is missing or refuses connections | show "Hermes is not running on the PC" |
| `4505` | `hermes_protocol_unsupported (unsupported hermes runtime protocol: N)` | Hermes' `runtime_protocol` is not one this server speaks | update aristotle-server |
| other | Hermes' reason | Hermes closed an established socket with any other sendable code | treat like 4502 |

Reasons are at most 123 bytes and never contain tickets or tokens. A dial failure is reported only after the upgrade
succeeded, so the phone always gets a close code instead of a hang or an HTTP error.

**Limits** (per phone socket unless noted): phone frames ≤ 1 MiB each (text only); ≤ 600 phone frames per 60 s
(sliding); ≤ 4 sockets per device. Messages Hermes -> phone are up to 64 MiB (the relay's read limit on the Hermes
socket; a whole-transcript `session.resume` can be large, and Hermes itself accepts up to 384 MiB,
`hermes_cli/web_server_boundary.py:26`); a larger one ends the relay with `4502 hermes_unavailable: connection lost`.
Messages over 32 KiB reach the phone as a fragmented WebSocket message (32 KiB frames plus continuation frames;
OkHttp reassembles them), so pings and close frames are not stuck behind one large frame.

**Attachments and the frame cap (T16).** An attachment is one `image.attach_bytes` / `file.attach` frame (§5; Hermes has no
chunks), so the phone sends at most 783 360 bytes per item (765 KiB: `(1 MiB − 4 KiB envelope) / 4 × 3`, base64), and at
most 10 items plus the submit per message, well under 600 frames/min. The limits are not raised for this. Images over the cap,
HEIC/HEIF/BMP, and PNG/WebP with Exif-GPS or XMP are re-encoded on the phone as JPEG (long edge 1568 px first, Hermes' own embed
size, `tools/vision_tools.py:358-365`; no metadata); a JPEG that fits is sent as it is with its Exif GPS IFD blanked and XMP
removed. A GIF over the cap and any other file over the cap are refused on the phone with the limit shown. Unlike the Desktop,
which sends originals (`use-prompt-actions/utils.ts:515-537`), the phone strips location: phone photos carry GPS.

**Keepalive.** aristotle-server sends the phone a WebSocket ping every 25 s, whatever the traffic (at most 4 unanswered
pings outstanding). It closes the phone `1011` when, within 15 s of a ping, neither of these happened: a byte arrived
from the phone (the pong, a frame, part of a frame), or a write to the phone that had to wait for it got bytes out. A
write counts only when the kernel refused it (`EAGAIN`) and later took its bytes. That normally needs the phone's TCP
to acknowledge data; with `TCP_NOTSENT_LOWAT` (below) a refused write can also clear when unsent bytes go out into a
window the phone opened earlier, which lasts about one round trip after it dies (so a phone that dies just before a
ping can earn one more round). A frame the kernel takes into a free buffer proves nothing, so a phone that is gone is
closed within 40 s even while Hermes streams events to it, however loaded the PC is. (On non-Unix builds the server
cannot see `EAGAIN` and counts a write that took at least 10 ms instead; a scheduling stall can then cost a dead phone
one more round.) A slow phone downloading a large message keeps its socket while those writes keep completing, even
when its pong waits behind the data. On Linux each phone socket has `TCP_NOTSENT_LOWAT` = 128 KiB: at most that much
waits unsent in the kernel, so writes keep waiting on the phone until little is left. Once the last bytes are in the
kernel nothing waits any more: the phone must then read what is buffered for it and answer a ping within 15 s. While a
phone frame waits for Hermes to take it, the phone is not read and its pongs are unseen: pings still go out, but no
`1011` is decided from a window that overlaps the wait. It does not ping Hermes (loopback); the phone may still call
`gateway.ping` itself (§1.3).

Client obligations (the phone app):
- **Answer every ping** with its own pong. RFC 6455 lets a client answer only the most recent ping; such a client can
  leave 4 pings unanswered, after which the server sends no more pings and an idle phone is closed `1011`. OkHttp
  answers every ping.
- **Keep reading.** WebSocket clients answer pings only while they read. OkHttp reads, and answers pings, on one
  thread that also runs `onMessage`: the app must hand each incoming frame off (queue/channel to another thread) and
  never block that callback; a callback blocked for more than 15 s delays the pong and the socket is closed `1011`.
- **Reconnect by close code** (the app, T09): `4401` or an HTTP `401` at upgrade: stop, wipe the pairing, pair
  again. `4505`: stop until the user checks again. `4503`: retry quietly every 10 s. `1000`, `1001`, `1011`, `1013`,
  `4502` and the rest: retry with exponential backoff and jitter (1 s doubling to a 30 s cap, reset once a relay
  delivers `gateway.ready`), and at once when the phone's network changes or the app comes to the foreground. No
  client ping (`pingInterval`); the server's pings are the keepalive.

**Redaction.** Hermes -> phone only, exactly §10: `system_prompt` -> `system_prompt_chars`, `credential_hint` removed,
at `params.payload`, `result`, `result.info` and every `result.events[].params.payload`. Frames without either field
name pass byte for byte; redacted frames are re-encoded (same fields and values, key order and whitespace may
differ). A frame that names a field but is not a JSON object is dropped (counted in the close log), never forwarded.

**Audit.** One log line on open (`device_id`, `listener`) and one on close (`device_id`, `listener`, `duration`,
`close_code`, `closed_by`, `frames_in/out`, `bytes_in/out`, `dropped`, and counts per JSON-RPC `method` name sent by
the phone; only names this document lists (§1-§9, e.g. `session.resume`, `prompt.submit`, `commands.catalog`) are
logged by name, any other is counted as `other`, so a token sent as a method never reaches the log). Never frame bodies, tickets or tokens.
