# T19c build brief — T19a security-review fixes (before the terminal is ever enabled)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/T19a/review-report.md` (m1–m5),
`docs/terminal.md`, `docs/spec.md` Q52–Q55, then `server/internal/terminal/**`, `server/internal/app/terminalcmds.go`,
`server/internal/control/**`.

## Fix (each with a test that fails before the fix; keep a scratch copy of the old file to prove it, never use git to revert)
1. **m1 env (PM decision):** the shell's environment drops `DISPLAY`, `WAYLAND_DISPLAY`, `XAUTHORITY`, `DBUS_SESSION_BUS_ADDRESS`,
   `XDG_SESSION_*`, `XDG_SEAT*`, `SWAYSOCK`, `HYPRLAND_INSTANCE_SIGNATURE`, `I3SOCK`, `NOTIFY_SOCKET` (already), `SSH_AUTH_SOCK`,
   `GPG_AGENT_INFO`, `DESKTOP_SESSION`, `XDG_CURRENT_DESKTOP` by default. A server config key `terminal.keep_env` (list, default empty,
   PC-side only) lets the user keep some. Test the scrub list.
2. **m1/m2 docs:** rewrite `docs/terminal.md` "What enabling means" honestly: the phone shell is the user's account; it can still reach the
   desktop through the abstract X sockets / the user bus by setting the variables itself, read every file, and bypass the escalation
   check and the cgroup in one command (`setsid -f env -u ARISTOTLE_TERMINAL …`; user-owned `cgroup.procs`). The check and cgroup are
   speed bumps against accidents, not a security boundary. The real controls are: terminal off by default, per-device grant, revoke +
   disable, short idle, and the lost-phone runbook. Keep the runbook; add "check for moved cgroup processes / leftover processes:
   `systemctl --user status aristotle-server`, `pgrep -u $USER -a`".
3. **m3 allowlist:** control commands from a terminal process are refused unless on an explicit allowlist (status, disable, ungrant,
   revoke, devices list, terminal status, logs read-only). Test that a new unknown command is refused from a terminal.
4. **m4:** a corrupt or wrong-permission `terminal.json` does not stop the server: log an error, treat the terminal as disabled (fail
   closed), keep the relay running; `terminal status` reports the problem. Test.
5. **m5:** keepalive pings continue while a PTY write is blocked (separate writer goroutine or deadline), so a vanished phone is dropped by
   the 25 s/15 s rule, not the 30 min idle. Test with a blocked PTY.

## Hard rules
Files: `server/**`, `docs/terminal.md`, `docs/protocol.md` §14 (only if a shape changes), `docs/v1-tickets/runs/T19c/build-report.md`.
android/ has uncommitted T19b work: do not touch. Never install the unit, never `systemd-run`, never enable the terminal on the real state
dir, never listen on a real port or 8760. No commits; never git checkout/restore/stash/reset.

## Verify
`GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T19c` exits 0, and `go test -race ./internal/terminal/ ./internal/app/`.

## Report
`docs/v1-tickets/runs/T19c/build-report.md`, ≤20 lines, listing which tests failed against the old code.
