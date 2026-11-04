# T19a build brief — remote terminal, server side

route: deep (a shell on the user's laptop). Repo `/home/justcallmesu/project/personal/Aristotle`. Read in order: `PROJECT.md`;
`docs/spec.md` Q7c, Q7d, Q26, Q35, **Q52–Q55**; `docs/v1-tickets/runs/T19/design-draft.md` and **`runs/T19/redteam-report.md` (every
BLOCKER and MAJOR there is a requirement)**; `docs/protocol.md` §11, §12; then `server/internal/{app,config,control,devices,httpapi,listen,
relay,service}` and their tests.

## Requirements
1. **Switch + grants (B1):** a server-owned file in the state dir (`terminal.json`, 0600, written atomically) holds `enabled` and the
   granted device ids. CLI via the control socket: `aristotle-server terminal enable|disable|status`,
   `aristotle-server devices grant-terminal <id>` / `ungrant-terminal <id>`. Never toggleable over HTTP. Do not add fields to
   `devices.json` (older binaries reject unknown fields). Disable / ungrant / revoke close that device's terminal sockets at once
   (hooks like revoke's, close code 4401 for revoke, a new 4403 `terminal_disabled` otherwise — add to protocol §12).
2. **Ordering (B1):** `GET /v1/terminal` = authenticate token -> register -> re-check device still valid -> check enabled + granted ->
   only then start the shell (copy the relay's order, `relay/relay.go:111-120`). A race with disable/revoke must never leave a shell.
3. **Terminal processes can't escalate (B2):** the control socket refuses `pair.*`, `terminal enable`, `grant-terminal` when the peer
   process is inside a terminal (mark every terminal child with `ARISTOTLE_TERMINAL=<id>` AND check the peer's process ancestry/cgroup,
   not only the env var, which a shell can unset). `disable`, `ungrant`, `revoke`, `status` stay allowed from anywhere.
4. **Shell (Q54):** `$SHELL -l` (fallback `/bin/bash -l`) in `$HOME`, as a child of the service. PTY via `/dev/ptmx` with std-lib syscalls
   if clean, else `github.com/creack/pty` (MIT) — say which. Env: service env minus systemd/aristotle internals, `TERM=xterm-256color`,
   `ARISTOTLE_TERMINAL=<id>`. Print a one-time banner line at start: "Aristotle terminal — sudo is unavailable (service sandbox); this shell
   has full access to your account."
5. **Cleanup (MAJOR):** every terminal in its own cgroup v2 child when the service has delegation (`Delegate=yes` added to the T21 unit;
   update `service` package + its golden test), killed with `cgroup.kill` on close; without delegation, fall back to new session +
   process-group SIGHUP then SIGKILL after 5 s and log that background jobs may survive. Verify cgroup v2 + delegation on this machine
   read-only (`/sys/fs/cgroup`), never by installing the unit for real.
6. **Liveness + limits (MAJOR):** WS ping 25 s / 15 s like the relay; idle 30 min counted from INPUT only; max session 8 h; max 2 per device,
   4 total; byte-rate cap for input (not a frame cap: typing must not be throttled; ~64 KiB/s burst), output backpressure (stop reading the
   PTY when the socket can't keep up; no unbounded buffers), frame ≤ 64 KiB, resize clamped (cols 20–500, rows 5–200).
7. **Protocol:** binary frames = PTY bytes both ways; text frames = JSON control `{"type":"resize","cols","rows"}` from the phone,
   `{"type":"exit","code"}` / `{"type":"closing","reason"}` from the server. `/v1/me` gains `terminal: {enabled, granted}`. Document in
   protocol §14 (new).
8. **Logging:** open/close/exit/kill with device id, terminal id, pid, duration, bytes in/out, close reason. Never content.
9. **Docs:** `docs/terminal.md` — what enabling means (full account access; revoke is not cleanup), the lost-phone runbook (revoke, disable,
   rotate cert: `aristotle-server cert rotate` if it exists — else say how; check `~/.ssh/authorized_keys`, crontab, systemd user units,
   shell rc files for anything added), and the sandbox limits (no sudo, private /tmp, umask 077).

## Tests (Go, temp XDG dirs, `-race`)
Grant file CRUD + atomic write; disabled/ungranted/revoked -> refused, and racing disable during start leaves no process; control refusals
from a fake terminal peer; PTY echo round trip with `/bin/sh`; idle (input-only) and max-length timers with a fake clock; per-device and
total caps; resize clamp; backpressure with a fast-output child (`yes`) — memory bounded; close kills the process group (and the cgroup path
when delegation is available — skip with a reason otherwise); `/v1/me` flag; nothing from PTY content in logs.

## Hard rules
Files: `server/**`, `docs/protocol.md` §12/§14, `docs/terminal.md`, `docs/v1-tickets/runs/T19a/build-report.md`. Never install or enable
the real systemd unit, never run `terminal enable` against the real state dir, never start a shell server on a real listener or port 8760.
android/ has another worker: do not touch. No commits; never git checkout/restore/stash/reset.

## Verify
`GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T19a` exits 0.

## Report
`docs/v1-tickets/runs/T19a/build-report.md`, ≤35 lines: how each red-team BLOCKER/MAJOR is addressed (file:line), PTY choice, cgroup
result on this machine, tests, NOT-VERIFIED.
