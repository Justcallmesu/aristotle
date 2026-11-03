# T01 build brief — v0 spike: attach to a live Hermes session from Go

route: deep (unknown internal protocol; must trace Hermes source and prove behaviour before writing anything durable)

Repo root: `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md` (hard rules) and
`docs/spec.md` §2, §5, §6 first.

## Goal
A throwaway Go CLI in `spikes/v0-attach/` (own `go.mod`, module `aristotle/spikes/v0attach`) that proves a
same-user process can join Hermes' live sessions the way Hermes Desktop does, and two documents that let
later tickets build the real thing without re-reading Hermes source.

## Non-goals
Production code, nice structure, tests beyond what the spike needs, anything under `server/` or `android/`.

## Facts already verified (start here; re-open to confirm)
Hermes source: `/home/justcallmesu/.hermes/hermes-agent/` (read-only).
- Control socket: `/home/justcallmesu/.hermes/gateway.sock` (0600). Verbs incl. `identify`, `session-ticket` (`gateway/control_socket.py:339`). Ticket TTL 30 s (`gateway/runtime_bootstrap.py:26`).
- Request shape (from subagent, verify): `{"protocol":1,"id":1,"verb":"session-ticket","params":{profile_id,instance_id,purpose:"interactive",scope:"host"}}`. Reference clients: `ui-tui/scripts/gateway_bootstrap.py:83`, `apps/desktop/electron/local-gateway.ts:661`, `hermes_cli/gateway_client.py:285`.
- WS: dial `<api_origin>/api/ws` (api_origin from `identify`) with subprotocols `hermes-gateway-v1` and `hermes-gateway-ticket.<ticket>`, no Origin header (`gateway/run_api.py:342-505`, ticket redeem `:428-450`).
- After connect send `client.capabilities {server_requests: true}` or approvals are withdrawn (`tui_gateway/server_requests.py:31`, `:136`).
- Method catalog (docs): session.list, session.create, session.resume/activate, session.history, prompt.submit, session.steer, session.interrupt, prompt.background, approval.respond, commands.catalog, config.get/set …

## What to do
1. Read the reference clients above and write down the exact frames.
2. CLI subcommands (flags are up to you): `identify`, `sessions` (list), `create --title aristotle-spike`,
   `attach <session_id>` (prints every event as one JSON line to stdout and to a capture file), `send <session_id> <text>`,
   `approve <session_id> <prompt_id> <choice>`. Mint a fresh ticket for every connection.
3. Prove, and save evidence for each:
   a. identify + ticket + WS connect works; record `runtime_protocol`, `api_origin`, profile ids.
   b. `sessions` lists the existing sessions (record count and fields only; do not print titles or content of the user's sessions).
   c. Create ONE session titled `aristotle-spike`. Attach TWO CLI clients to it at once; `send` "Reply with the single word pong." from one; both clients receive the streamed events. Disconnect one; the other keeps receiving.
   d. Approval round trip: in the spike session run `mkdir -p /tmp/aristotle-spike-probe` yourself first, then send
      "Use the terminal tool to run: rm -r /tmp/aristotle-spike-probe". If an approval request arrives, answer `deny` from client 1 and record what client 2 receives (withdrawal). If Hermes auto-approves (smart approvals), record that and how the approval mode is configured (read only; do NOT change config).
   e. Clarify prompt if cheap to trigger (e.g. ask the agent to use the clarify tool with two choices); record the frames.
4. Answer spec §5 questions 1–6 from source + evidence. For Q4 (profiles): there is no `gateway.sock` under
   `~/.hermes/profiles/pm/`; find out whether the default gateway serves all profiles and how a client selects a
   profile (ticket `scope`, `profile_id`, a method). For Q5: list which data is socket-only vs REST `/api/*` and what
   credential REST accepts for a same-user process. For Q6: how Desktop drives the remote terminal.
5. Write:
   - `docs/spikes/v0-attach.md`: what was proven (a–e) with evidence, answers to §5 Q1–Q6 each labelled VERIFIED (file:line or captured frame) or ASSUMED, surprises, risks for v1.
   - `docs/protocol.md`: the frames and methods v1 needs (connection bootstrap, session list/resume/history, prompt submit, steer/interrupt/queue, approvals, clarify, model/reasoning set, slash commands, attachments, subagents, cron, terminal), with request/response shapes. Mark anything not exercised as "from source, not exercised".
   - `docs/spikes/frames/*.jsonl`: captured frames from c–e, redacted (no tokens/tickets; only the spike session's content).

## Hard rules
- Never edit Hermes config or state files; never restart Hermes or the Desktop; do not touch `~/hermes-mobile-remote/` or port 8650.
- Only the `aristotle-spike` session receives prompts. Never send to, interrupt, or answer approvals in any other session.
- Never print or save tickets, tokens or other sessions' content. No commits, no git commands that change state.
- Leave no processes running when you finish.
- Files you may create: `spikes/v0-attach/**`, `docs/spikes/**`, `docs/protocol.md`, `docs/v1-tickets/runs/T01/build-report.md`. Nothing else. `PROJECT.md`, `docs/spec.md`, `docs/v1-tickets/*.md` are not yours.

## Stop and report (do not work around) if
- The ticket path needs anything beyond same-user socket access (e.g. a password, a config change, a restart).
- `identify` reports a protocol you cannot speak, or the WS upgrade is refused for a reason you cannot fix within the rules.

## Verify
- `cd spikes/v0-attach && gofmt -l . && go vet ./... && go build ./...` (gofmt prints nothing).
- Evidence files exist for a–d.

## Report
`docs/v1-tickets/runs/T01/build-report.md`, at most ~50 lines: files created; commands run with exit codes; a–e result
(PASS/FAIL/AUTO-APPROVED + evidence file); one line per §5 question with VERIFIED/ASSUMED; NOT-VERIFIED list; risks.
