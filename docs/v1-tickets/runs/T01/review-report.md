# T01 review — spike documents (`docs/protocol.md`, `docs/spikes/v0-attach.md`, frames)

Reviewer: fresh context. Hermes HEAD checked = `717447e421` (matches the docs). Read-only; Hermes not contacted.
Paths `H/` = `/home/justcallmesu/.hermes/hermes-agent/`, `F/` = `docs/spikes/frames/`.

## Claim spot-checks (22 checked; 0 WRONG)
| # | Claim (doc:line) | Verdict | Source |
|---|---|---|---|
| 1 | Control socket: one line in/out, 2 s timeout, 0600 (protocol.md:12-16) | CONFIRMED | `H/gateway/control_socket.py:6`, `:38`, `:287` |
| 2 | `session-ticket` envelope ⊆ {protocol,verb,id,params}; params ⊆ 6 keys; needs same-UID peer (protocol.md:17-18,45-46) | CONFIRMED | `control_socket.py:369-378`, `:399-411` |
| 3 | Ticket = 43 chars, 30 s TTL, single use, stored hashed; `host` scope only with `interactive` (protocol.md:42,49-50) | CONFIRMED | `H/gateway/runtime_bootstrap.py:22,26,42-56,75`; `token_urlsafe(32)` |
| 4 | `identify` field set incl. `runtime_protocol`, `api_origin`, `served_profiles[{profile_id,home}]` (protocol.md:23-32) | CONFIRMED | `H/gateway/run_bootstrap.py:293-308`; `F/a-identify.jsonl` |
| 5 | Fail-closed checks: `runtime_protocol==1`, `state=="ready"`, `session-authority-v1` (protocol.md:33-35) | CONFIRMED | `H/hermes_cli/gateway_runtime.py:98-128` |
| 6 | WS subprotocols `hermes-gateway-v1` + exactly one `hermes-gateway-ticket.<t>`, no Origin, loopback peer (protocol.md:60-66) | CONFIRMED | `H/hermes_cli/web_server_boundary.py:156-171`; `H/gateway/run_api.py:428-447`; `F/a-probes.jsonl` |
| 7 | `gateway.ready` payload; `gateway.ping` answered inline; 33 ms token coalescing (protocol.md:73-79,112) | CONFIRMED | `H/tui_gateway/ws.py:643-649`, `:695-699`, `:129-130` |
| 8 | `client.capabilities` result keys (protocol.md:75-77) | CONFIRMED | `H/tui_gateway/methods_voice.py:522-540`; `F/a-connect.jsonl:3` |
| 9 | Event frame shape `{type,session_id,payload,replay_epoch,seq,authority_epoch,execution_generation,admission_id}` (protocol.md:88-94) | CONFIRMED | `H/gateway/session_events.py:48-64`; `F/d-client2.jsonl:67` |
| 10 | Profile selection: host ticket + `params.profile=<NAME>`; profile-bound socket naming another → `4001 profile_mismatch` (protocol.md:119-125) | CONFIRMED | `H/gateway/session_controls.py:173-199`, `:305-318`; `H/hermes_cli/profiles.py:464-477`; `F/b-sessions.jsonl` |
| 11 | Named profiles minted by default socket; Desktop asks `scope:host` for interactive (protocol.md:116-118) | CONFIRMED | `H/apps/desktop/electron/local-gateway.ts:614-620,631-634`; `gateway_runtime.py:155-159`; `multiplex_profiles: true` in config.yaml:1345 |
| 12 | Must `session.resume` before submit/answer (protocol.md:140-142) | CONFIRMED | `session_controls.py:813-816`; `H/gateway/session_authority.py:1035-1037` |
| 13 | `prompt.submit` needs an idempotency id (protocol.md:157) | CONFIRMED (nuance: `submission_id` OR `input_id`) | `session_controls.py:821-823` |
| 14 | Titled `session.create` returns existing session (protocol.md:132) | CONFIRMED | `session_controls.py:467-492` |
| 15 | Approval/clarify are events + `approval.respond{…,choice}` / `clarify.respond{…,answer}`, `*.settled` to all, `already_resolved` on late answer (protocol.md:177-195) | CONFIRMED | `H/gateway/session_pending_controls.py:18-101`; contracts `canonical_sessions.py:290-292`, `canonical.py:357-361`; `F/d-client1.jsonl:75`, `F/d-client2.jsonl:67,71`, `F/e-client1.jsonl:17,22` |
| 16 | Approval choices `once,deny` (+`session`,`always`) (protocol.md:193) | CONFIRMED (also: an `edit` prompt forces `once,deny` and carries `edit`; undocumented) | `session_pending_controls.py:22-34` |
| 17 | `srq-` ids, `request.cancel`, decline code 4404 (protocol.md:196-200) | CONFIRMED | `H/tui_gateway/server_requests.py:9-13`, `:119` |
| 18 | `session.info` carries `system_prompt` (protocol.md:98; v0-attach.md:97) | CONFIRMED field; shape description incomplete — see M1 | `H/tui_gateway/contracts/common.py:73-106`; `F/c-client1.jsonl:26` |
| 19 | REST header `X-Hermes-Gateway-Ticket`, purpose `native-http`, no Origin, loopback, 401/403 `profile_scope_mismatch`, cron needs `?profile=` (protocol.md:227-232) | CONFIRMED | `H/hermes_cli/dashboard_auth/native_http.py:15`, `:62-82`, `:191-217`, `:287-312`; `F/q5-rest.jsonl` |
| 20 | `PATCH /api/sessions/{id}` = `sidebar` mutation (v0-attach.md:81-82) | CONFIRMED | `H/hermes_cli/web_routers/sessions.py:930-938` |
| 21 | Terminal = Desktop-local `node-pty` over IPC `hermes:terminal:start|attach|write|resize|cwd|dispose` (protocol.md:239-244) | CONFIRMED | `H/apps/desktop/electron/terminal-ipc.ts:11`, `:367,443,455,467,481,491` |
| 22 | `shell.exec` is one-shot, local sessions only (protocol.md:223-224) | CONFIRMED | `H/gateway/session_shell.py:22-47` |

## Frame hygiene
No tickets (`F/a-probes.jsonl:1` shows `<redacted 43 chars>`; `q5-rest` stores only `ticket:true/false`), no bearer/API
tokens (the one `sk-` regex hit is a skill name). `system_prompt` redacted everywhere (21682 chars). Only session id seen:
`local-42f9aa95…` (spike); `session.list` captures were filtered by `title:"aristotle-spike"`; `b-sessions` = counts/field
names only; broadcast events have `{}` payloads. Clean apart from m1/n1.

## Findings
- **MAJOR (proven) M1 — `session.info` has two payload shapes; docs describe one.** protocol.md:98 lists
  "pending queue, revision, running, and a ~21 KB system_prompt" as one event. Frames show (a) a small state update
  `{pending,revision,running,execution_generation,authority_epoch,stored_session_id,desktop_protocol}`
  (`F/c-client1.jsonl:15,17,29`) and (b) the full inventory with `model,provider,tools,cwd,system_prompt,…` (`:26`).
  The contract defaults missing fields to `""`/`false` (`contracts/common.py:77-85`). If a phone reducer replaces state
  on every `session.info`, model, tools and title go blank. Fix: document both shapes and say "merge present keys".
- **MINOR m1 — The masked provider key hint is committed.** `credential_hint: "sk-[REDACTED]"` appears in session.info/resume frames
  (`F/c-client1.jsonl:26`, `F/f-busy-client2.jsonl:85`). That is 8 characters of a real API key. Fix: redact it like `system_prompt`.
  The relay-redaction decision (v0-attach.md:109-111) should cover this field too.
- **MINOR m2 — `system_prompt` frequency is overstated.** v0-attach.md:109 says "~twice per turn". Frames show one full
  `session.info` per turn (`F/d-client1.jsonl` seq 50, 96 for 2 turns), plus one in each resume snapshot and each `session.info` result.
  Fix the wording, since the redaction decision depends on it.
- **MINOR m3 — The spec conflict is not raised.** Spec Q7d allows any method with no allowlist, and spec §2 says "never edit Hermes config".
  The relay would still forward `config.set`, `shell.exec` and `session.mutate delete` (protocol.md:221-224). Add this to the v1 risks
  so the PM decides it before the relay ticket.
- **NIT n1** — `info.skills`/`tools` names stay in snapshots while `commands.catalog` was redacted (inconsistent, not secret).
- **NIT n2** — protocol.md:65 says a bad ticket is "refused" at `run_api.py:428-447`. In the code, an unredeemable ticket falls through to
  the web app's own gate (`run_api.py:456-460`), which returns the 403 that was observed. The result is the same, but the cited path is wrong.
- **NIT n3** — protocol.md:45-46 omits that `profile_id, instance_id, purpose` are required and that `restart_wait` must be
  `"refuse"` (`control_socket.py:375-378`). v0-attach.md:56-57's "ASSUMED" can cite `session_pending_controls.py:38,51`
  (publish is unconditional).

Later tickets can build on these documents once M1 is fixed. The bootstrap, profile, approval and REST facts all hold.

VERDICT: PASS-WITH-FINDINGS
