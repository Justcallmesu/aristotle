# T19a build report — remote terminal, server side
Gate: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T19a` exit 0 (gofmt, vet, 311 tests pass, 0 fail; 2 SKIP: `TestHelperCLI` helper, `TestCloseKillsTheCgroup` without delegation). `go test -race ./...` green; terminal/relay/control `-race -count=5` green. Paths: `server/internal/` unless noted.

## Red-team BLOCKERs / MAJORs
- **B1 switch+grants+ordering**: `terminal/grants.go` (`terminal.json` 0600 via statefile.WriteAtomic, :43 OpenGrants, :127 update; disable/ungrant apply even if the save fails, grant never does). devices.json untouched. CLI `terminal enable|disable|status`, `devices grant-terminal|ungrant-terminal` (`app/terminalcmds.go`), control socket only. Order in `terminal/hub.go:153` ServeTerminal: httpapi 401 → 403 JSON (`httpapi/httpapi.go:140`) → upgrade → register → re-check paired+enabled+granted (:173) → shell started by the same goroutine only while no close is requested, torn down when one is (`session.go:117`, :354). Hooks: revoke → 4401 (:222), any grant change → recheck → 4403 `terminal_disabled`/`terminal_not_granted` (:231). Revoke also drops the grant (`app/controlcmds.go`).
- **B2 escalation**: control `Authorize(cmd, peerPID)` hook (`control/control.go:59,220`, SO_PEERCRED pid in `peercred_linux.go`); `app/terminalcmds.go:28` refuses `pair.*`, `terminal.enable`, `devices.grant_terminal`; disable/ungrant/revoke/status allowed. Detection `terminal/hub.go:305`: env marker in `/proc/<pid>/environ`, terminal cgroup, parent chain and session id vs live shells; unreadable peer = inside (fail closed). Tested with a real child CLI carrying the marker, and end to end: a phone terminal runs the CLI with `env -u ARISTOTLE_TERMINAL` → refused by ancestry, no code issued (`app/terminal_test.go`). Documented as a speed bump, not a boundary.
- **M1 cleanup**: unit gains `Delegate=yes` (`service/service.go:64`, golden test `TestUnitTextGolden`). Delegated: each shell is cloned straight into `<service cgroup>/aristotle-term-<id>` (clone3 CLONE_INTO_CGROUP), SIGHUP, then `cgroup.kill` + rmdir (`terminal/cgroup_linux.go:154`); stale cgroups swept at start. Fallback: SIGHUP+SIGCONT to the group **and every process of the shell's session**, SIGKILL after 5 s (`proc.go:52`, `sys_linux.go:159`); logs `method=session` at start and warns per close that setsid/daemons may survive. Revoke/disable kill without the grace.
- **M2 liveness**: relay's rule reused — tracking extracted to exported `relay.PhoneConn` (`relay/phoneconn.go:14`, relay embeds it; relay tests unchanged and green); ping 25 s / 15 s, NOTSENT_LOWAT (`session.go:305`). Idle 30 min from input bytes only, max 8 h (`session.go:292`); caps 2/device, 4 total.
- **M3 limits**: byte-rate token bucket 64 KiB burst + 64 KiB/s that delays reading, never closes (`limits.go:87`); frame ≤ 64 KiB (1009); resize clamped 20–500 × 5–200 (`limits.go:71`); output = one 32 KiB buffer + blocking write (backpressure).
- M4/M5 are phone-side: written into protocol §14 "Phone obligations" for T19b.

## Choices
- PTY: std lib only (`/dev/ptmx` + TIOCSPTLCK/TIOCGPTN/TIOCSWINSZ via `SyscallConn`, `sys_linux.go:21`); no creack/pty. Linux-only, `sys_other.go` stubs fail closed.
- Shell `$SHELL -l` → passwd shell → `/bin/bash -l`, in `$HOME`; env minus systemd/`ARISTOTLE_*` vars + `TERM=xterm-256color`, `ARISTOTLE_TERMINAL=<id>` (`proc.go:106`). Banner as first binary frame.
- Close codes: 1000 exit, 1001, 1008 bad_control_frame, 1009, 1011 pong_timeout/shell_start_failed, 4401, 4403, 4408 idle_timeout/max_session, 4430 too_many_terminals; `exit`/`closing` JSON before close. `/v1/me` gains `terminal:{enabled,granted}` (`httpapi/auth.go:54`). Docs: protocol §12 note + new §14; `docs/terminal.md`.

## cgroup on this machine (read-only checks + a test scope)
cgroup2fs, kernel 7.1 (`cgroup.kill` present), systemd 261. Tests run inside `hermes-gateway.service` (not delegated) → cgroup test skips there. **Finding:** systemd 261's user manager sets no `user.delegate` xattr even with `Delegate=yes` (checked on a transient scope), so detection also accepts "own cgroup is `aristotle-server.service` and the installed unit file says `Delegate=yes`" (`service.InstalledUnitDelegates`, `cgroup_linux.go:39`). `TestCloseKillsTheCgroup` passed for real under `ARISTOTLE_TEST_CGROUP_DELEGATED=1 systemd-run --user --scope -p Delegate=yes` (setsid child killed, cgroup removed). Real unit never installed; no `terminal enable` on the real state dir; no listener on 8760.

## Mutation checks
Removing the post-register re-check, the `escalates` list, the ancestry check, or input-only idle each fails a test (the first needed the test hook moved before register; done).

## NOT VERIFIED
- The real installed unit with `Delegate=yes` (cgroup path in production; existing installs need `install --force` + restart, said in docs).
- A real bash/zsh login shell from the service env (tests use `/bin/sh`); `$SHELL` presence in the unit env.
- Phone side (T19b). Protocol §11.2's `/v1/me` row and the 403 note there were not edited (scope §12/§14 only): PM to update.
- Non-Linux builds: `internal/hermes` already fails to build on darwin/windows (pre-existing); terminal stubs vet clean on darwin.
