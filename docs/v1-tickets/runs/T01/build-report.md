# T01 build report — v0 spike: attach to a live Hermes session from Go

Outcome: **all of a–e proven; no stop rule triggered** (socket access only; no password, config change or restart).

## Files created
- `spikes/v0-attach/` — `go.mod` (module `aristotle/spikes/v0attach`), `main.go`, `control.go`, `ws.go` (std-lib RFC 6455 client), `rpc.go`, `probe.go`, `rest.go`.
  Subcommands: `identify`, `sessions`, `create`, `attach` (+ control FIFO: send/approve/clarify/steer/interrupt/raw/quit), `send`, `approve`, `clarify`, `probe`, `rest`. Every write verb refuses any session other than the one titled `aristotle-spike`; a fresh ticket per connection; tickets never printed; `system_prompt` redacted in captures.
- `docs/spikes/v0-attach.md` — results, §5 answers, surprises, risks.
- `docs/protocol.md` — bootstrap, events, profiles, sessions, prompt/queue/steer/interrupt, approvals/clarify, model/slash/attachments/subagents/cron, REST, terminal.
- `docs/spikes/frames/` — `a-identify`, `a-connect`, `a-probes`, `b-sessions`, `c-create`(+`-summary.json`), `c-client1/2`, `c-send`, `d-client1/2`, `e-client1/2`, `f-busy-client1/2`, `q5-rest` (.jsonl).

## Commands run (exit codes)
- `cd spikes/v0-attach && gofmt -l . && go vet ./... && go build ./...` → gofmt prints nothing, 0 / 0 / 0 (built binary removed).
- `v0attach identify` 0 · `probe --expiry` 0 · `sessions` ×7 cases 0 · `create --title aristotle-spike` 0 · `attach` ×6 clients (c, d/e, f pairs) exited 0 via `quit` · `send --wait` 0 · `rest` ×9 0.
- `mkdir -p /tmp/aristotle-spike-probe` 0 (later removed by the spike turn).

## a–e
- a PASS — `a-identify.jsonl`, `a-connect.jsonl`, `a-probes.jsonl`. runtime_protocol 1, api_origin `http://127.0.0.1:46189`, 6 served profiles; reuse/expiry/Origin/garbage ticket → 403.
- b PASS — `b-sessions.jsonl` (counts + field names only).
- c PASS — `c-client1/2.jsonl`, `c-send.jsonl`: both clients identical seq 1–12 ("pong"); after client 1 quit, client 2 got seq 13–24.
- d AUTO-APPROVED then PASS — `rm -r` auto-approved by smart mode (`approvals` unset → default `smart`); real prompt obtained via a protected `AGENTS.md` write in /tmp: client 1 `deny` → `resolved`; client 2 saw `approval.settled` (that is the withdrawal). `d-client1/2.jsonl`.
- e PASS — `clarify.request` → `clarify.respond "blue"` from client 2 → `clarify.settled` on both. `e-client1/2.jsonl`.
- extra PASS — queue, `session.steer`, `session.interrupt` (`f-busy-*.jsonl`); REST with native-http ticket (`q5-rest.jsonl`).

## §5 questions
- Q1 frames — VERIFIED (captured + `control_socket.py:339-397`, `run_api.py:428-488`).
- Q2 list/resume/events — VERIFIED with two CLI clients; Desktop window as co-viewer ASSUMED (same call path, `canonical-protocol.ts:320`).
- Q3 approval/clarify + withdrawal — VERIFIED (events + `*.respond`, `*.settled`); `srq-` requests / `request.cancel` from source only.
- Q4 profiles — VERIFIED: default gateway serves all; host ticket + `params.profile=<name>`, or profile-scoped ticket.
- Q5 REST vs socket — VERIFIED credential (`X-Hermes-Gateway-Ticket`, purpose `native-http`, one per request) and read routes; route split from source.
- Q6 terminal — VERIFIED from source: Desktop uses local `node-pty` over Electron IPC; no gateway channel.

## NOT VERIFIED
Desktop window co-viewing; approvals without `client.capabilities`; `srq-` server requests; model/reasoning switch; slash exec; attachments; subagents; cron writes / `cron.submit`; REST writes; `session.detach`; replay gap; later-created profile.

## Risks
- Internal protocol may change on Hermes update → fail closed on `runtime_protocol`/`state`/`session-authority-v1`; re-identify each connect (port + instance_id change).
- `session.info` and resume snapshots carry the full ~21 KB `system_prompt` (user memories) to every viewer each turn: conflicts with "frames pass unchanged" for bandwidth/privacy — needs a PM decision.
- Controls need `execution_generation` / `expected_revision` tracking, or they fail `stale_generation`/`revision_conflict`.
- Docs describe approvals as `srq-` requests; the live canonical path uses events — v1 must follow the code, not the docs.
- Remote terminal is new server work (own PTY).
- Leftover: session `aristotle-spike` (`local-42f9aa95…94d2`) stays in Hermes/Desktop sidebar; delete when done. No processes left running.
