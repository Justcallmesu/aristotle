# T19a security review — remote terminal, server
Read: brief, design/red-team, build brief/report, `docs/terminal.md`, protocol §11.2/§12/§14, full diff + untracked files. `go test -race ./...`
(temp XDG) and `-race -count=3` terminal/control/app: green. No listener, no 8760, no unit install, no systemd-run. Paths under `server/internal/`.
## Brief items
1. **No shell while off/ungranted/revoked — CLOSED.** The 403 comes before the upgrade (`httpapi/httpapi.go:140`). Then register (`terminal/hub.go:164`),
   re-check (:173), and the shell starts only while `stopCtx` is live (`session.go:90`); a close during the start tears it down (:109). Hooks run after the
   state flips (`devices/devices.go:187-195`, `grants.go:493`). Locks are always taken hub→store (no deadlock). Shutdown sets `closed` under the lock
   (`hub.go:261`). A bad `terminal.json` fails start (`app/serve.go:85`); a failed save never enables or grants (`grants.go:489`). Input after a close is
   dropped (`session.go:202`). Race tests cover revoke/disable/ungrant.
2. **Escalation.** PID reuse and a dead connecting child both fail closed (unreadable = inside, `hub.go:309,334`). Desktop's integrated terminal sets
   `HERMES_DESKTOP_TERMINAL`, not this marker (`hermes-agent/apps/desktop/electron/terminal-ipc.ts:199`), so the user's PC terminals pass. Bypasses (m2) are
   the same-uid class B2 accepted; the code (`hub.go:303`) and the docs (`docs/terminal.md:34`) call the checks a speed bump. Stealing tls-key or editing
   devices.json/terminal.json is covered by the runbook (steps 3-4).
3. **Cleanup.** clone3 puts the shell in its cgroup, then `cgroup.kill` + rmdir (`cgroup_linux.go:154`). Fallback: SIGHUP/SIGCONT, then SIGKILL to every
   session member (`sys_linux.go:159`). The shell is reaped (:250). Caps are checked under the lock (`hub.go:197`). A wrong delegation guess (file says yes,
   service not restarted) still works, because the user@ subtree is user-owned. Low impact.
4. **Limits — OK.** Input is a token bucket that delays and never closes (`limits.go:87`). Output: one 32 KiB buffer and a blocking write (bounded heap
   under `yes`). Frame cap 64 KiB (`hub.go:159`), resize clamp (`limits.go:71`), idle counts input only (`session.go:231,296`), 8 h max (:294).
5. **Env.** `NOTIFY_SOCKET`, `LISTEN_*`, `INVOCATION_ID`, `MANAGERPID` and `ARISTOTLE_*` are dropped (`proc.go:94-102`); `Type=simple` means NotifyAccess
   is none anyway. `SSH_AUTH_SOCK` is documented. `DBUS_*`, `DISPLAY` and `WAYLAND_DISPLAY` are inherited (m1).
6. **Logging — OK.** Logs carry only ids, pid, counts, codes and the errno name (`session.go:92,125,147`). No PTY data, control text, shell path or token.
7. **Relay refactor — unchanged.** Field moves only: the clock is still `opts.Now` (`relay/session.go:60`) and `kill`→`Cut` does the same close. No relay
   `_test.go` changed; the tests are green under -race.
8. **Delegate=yes — OK.** The server enables no controllers and KillMode=control-group still kills the subtree. A changed unit needs `--force`
   (`service/service.go:133`); `install --force` + restart is documented (`docs/terminal.md:59`).

## Findings (no BLOCKER/MAJOR)
- **m1 MINOR (docs honesty):** `docs/terminal.md:46` ("X11 sockets … are not visible") implies the GUI is out of reach. VERIFIED here: the user-manager
  env has `DISPLAY`, `WAYLAND_DISPLAY`, `XAUTHORITY` and `DBUS_SESSION_BUS_ADDRESS`, and X listens on abstract `@/tmp/.X11-unix/X0,X1` (PrivateTmp does not
  hide these); Wayland lives in `$XDG_RUNTIME_DIR`. So the shell can capture the screen, read the clipboard and type into the unlocked desktop, which also
  gets around the control check and NNP. Add "your live desktop session" to "What enabling means" and qualify the sandbox bullets.
- **m2 MINOR (docs precision):** In session mode, `setsid -f env -u ARISTOTLE_TERMINAL aristotle-server pair` passes every check. VERIFIED: ppid becomes
  systemd --user, new sid, no marker. In cgroup mode the user@ subtree's `cgroup.procs` files are user-owned (VERIFIED), so `echo $$ > <service
  cgroup>/cgroup.procs` escapes both `cgroup.kill` and the cgroup check. Qualify `docs/terminal.md:52-53` ("including … setsid and daemonised").
- **m3 Consider:** `escalates` is a deny-list (`app/terminalcmds.go:28`), so a new widening command would be allowed by default. Prefer an allowlist.
- **m4 Nit:** a corrupt or non-0600 `terminal.json` stops the whole server, relay too (`app/serve.go:85`); consider starting disabled and logging loudly.
- **m5 Nit:** a blocked PTY write suspends keepalive (`session.go:219,319`); a vanished phone keeps its slot for up to the 30 min idle limit.

NOT VERIFIED: the cgroup path under the real unit (the test skips here); a real bash/zsh login shell in the unit env; `/proc/<pid>/root/tmp` vs PrivateTmp.
VERDICT: PASS-WITH-FINDINGS
