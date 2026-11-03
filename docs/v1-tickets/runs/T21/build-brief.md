# T21 build brief — `aristotle-server install|uninstall|status|logs` (systemd user service)

route: std

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` Q26, Q31, board row D7 in
`docs/v1-tickets/tickets.md`, then `server/cmd/aristotle-server`, `server/internal/app/{app,cli,serve}.go`, `server/internal/config`.

## Goal
The user runs `aristotle-server install` once and the server runs as a systemd **user** service that starts at login and restarts on
failure; `uninstall` removes it cleanly. No root.

## Requirements
- `install [--start]`: copies nothing, it writes `$XDG_CONFIG_HOME/systemd/user/aristotle-server.service` (default
  `~/.config/systemd/user/`) pointing at the absolute path of the running binary (`os.Executable`, symlinks resolved), then
  `systemctl --user daemon-reload` and `enable` (and `start` with `--start`). Unit: `Type=simple`, `ExecStart=<bin> serve`,
  `Restart=on-failure`, `RestartSec=5`, `NoNewPrivileges=yes`, `PrivateTmp=yes`, `UMask=0077`, `WantedBy=default.target`.
  **No** `Environment=HERMES_HOME` (the connector finds the root gateway; protocol §1.1). No `StateDirectory=` (it creates 0755 dirs;
  the server enforces 0700 itself).
- Refuse to overwrite a unit that differs, unless `--force`; print the diff-free reason.
- `uninstall`: `disable --now`, remove the unit, `daemon-reload`. Never touches the state dir (devices, cert) unless `--purge`,
  which asks for confirmation (`--yes` to skip) and then removes the state dir.
- `status`: `systemctl --user is-active/is-enabled` + the listener URLs from the running server via the control socket if up.
- `logs [-f]`: `journalctl --user -u aristotle-server` passthrough.
- After install, print one line: to keep it running when logged out, run `loginctl enable-linger $USER` (do NOT run it).
- All `systemctl`/`journalctl` calls go through a small interface so tests use a fake runner; never call the real systemctl in tests.
- D7: a second Ctrl-C during graceful shutdown exits immediately (code 130); `net/http` server errors go through slog
  (`http.Server.ErrorLog` bridged).

## Hard rules
Files: `server/cmd/**`, `server/internal/app/**`, a new `server/internal/service/**`, `docs/v1-tickets/runs/T21/build-report.md`.
Do NOT run `install` for real, do not call the real `systemctl --user enable/start`, do not create files in the real
`~/.config/systemd`. Read-only real probes (`systemctl --user --version`) are fine. No commits. Std lib only.

## Verify
`GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T21` exits 0.

## Report
`docs/v1-tickets/runs/T21/build-report.md`, ≤30 lines: the unit file text, commands, tests by name, NOT-VERIFIED.
