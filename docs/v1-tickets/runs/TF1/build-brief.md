# TF1 — systemd unit: drop PrivateTmp (control socket refuses every PC command under the service)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`.
## Bug (VERIFIED by the PM 2026-11-05 on the real install)
The unit written by `aristotle-server install` has `PrivateTmp=yes`. In a systemd **user** unit that implies a private user namespace
(`/proc/self/uid_map` = `1000 1000 1` inside), so the server cannot read `/proc/<pid>/environ` of processes outside the unit (EACCES).
`terminal.Hub.InTerminal` (`server/internal/terminal/hub.go:308-315`) fails closed on that error, so every control command from a normal PC
terminal is refused: journal `control command refused from a remote terminal cmd=pair.start ... seen_by=unreadable_peer`. Probe:
`~/.hermes/profiles/pm/cache/scratch/probe-privtmp.sh` (with PrivateTmp: Permission denied; without: ok). Workaround live on the laptop: drop-in
`~/.config/systemd/user/aristotle-server.service.d/override.conf` `PrivateTmp=no` (do not touch it).
## Do
1. Remove `PrivateTmp=yes` from the unit template (find it under `server/internal/service/`); add a comment why (user-unit sandboxing options
   that imply PrivateUsers — PrivateTmp, ProtectHome, ProtectSystem... — break the peer checks). Keep NoNewPrivileges, UMask, Delegate.
2. A unit-template test asserting none of the options that imply a user namespace in a user unit appear (PrivateTmp, PrivateUsers, ProtectHome,
   ProtectSystem, PrivateDevices, ProtectKernel*, ProtectControlGroups, RestrictNamespaces...: check `man systemd.exec` "implied when running in
   user mode" list and cite it).
3. `install` on an existing unit that differs: confirm `--force` replaces it; the output names `--force` when the unit is outdated (check
   current behaviour; only change if it does not).
4. `docs/terminal.md`: one line on why the unit has no PrivateTmp. CHANGELOG [Unreleased] Fixed: one Server line.
## Hard rules
Only `server/internal/service/**`, `docs/terminal.md`, `CHANGELOG.md`, the report. Never run `install`/`uninstall`/`systemctl`/`systemd-run`, never
touch the real unit, port 8760 or the real state dir. No commits; never git checkout/restore/stash/reset.
Verify: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TF1` exits 0. Report ≤ 10 lines at `runs/TF1/build-report.md`.
