# T21 review report

Read the diff (`app.go`, `controlcmds.go`, `serve.go`, `servicecmds.go`), `service/{service,exec}.go` and the test seams. Did not run the gate (PM gate GREEN is accepted) and made no systemctl calls.

## Checks
- **Unit file**: valid for systemd. It has `Type=simple`, `Restart=on-failure`, `RestartSec=5`, `NoNewPrivileges`, `PrivateTmp`, `UMask=0077` and `WantedBy=default.target`. There is no `HERMES_HOME` and no `StateDirectory`.
- **ExecStart quoting**: a path with spaces, tabs, quotes, backslash or `;` is wrapped in `"..."` with `\\` and `\"` escaped. `%` becomes `%%` and `$` becomes `$$`. Relative, newline and NUL paths are rejected. This matches the systemd rules.
- **Restart policy**: a first SIGTERM gives a clean exit 0, so `systemctl stop` does not trigger a restart. A crash restarts after 5s. A second signal exits 130, which is a failure. That only happens when SIGKILL is near anyway, so it is harmless.
- **Refuse-to-overwrite**: an exact text compare. An identical unit is fine, a differing one gives `ErrUnitDiffers` with the `--force` hint, and the check runs before any write. The write is atomic (tmp file, then rename). Fine.
- **Uninstall**: `disable --now`, remove the unit, `daemon-reload`. A missing unit is tolerated. A systemctl failure is ignored only when the unit file was absent. Fine.
- **Purge safety**: `config.StateDir()` always ends in `.../aristotle`, so `RemoveAll` cannot hit `$HOME` or `/`. The relative `XDG_STATE_HOME` case falls back to `~/.local/state/aristotle`. `RemoveAll` on a symlinked state dir removes only the link, not the target. Confirmation runs before anything changes. `--yes` without `--purge` is a usage error. Fine.
- **D7**: `watchSignals` cancels on the first signal and calls `exit(130)` on the second. The channel buffer of 2 is enough. `ErrorLog` is bridged with `slog.NewLogLogger(handler, Warn)` in `serveListener`.
- **Tests**: every systemctl/journalctl call in the tests goes through the fake runner, and temp XDG dirs are used. `ExecRunner` is only referenced as the production default in `servicecmds.go`, never in tests.

## Findings
No BLOCKER or MAJOR findings.
- MINOR: `install --force` over a running service does not restart it, so the old binary keeps running until the next restart. Print a hint or run `try-restart`.
- MINOR: if the unit path is a symlink (dotfile managers), `rename` replaces the link with a regular file. `ReadFile` follows the link, so the compare uses the target's content.
- MINOR: `status` exits 0 even when the service is inactive. This is acceptable, since the brief did not specify an exit code.
- NIT: `serveListener` bridges `ErrorLog` only for the loopback server. The brief already lists the LAN/Tailscale servers as out of scope.
- NIT: `NotVerified` items from the build report stand: real systemd load, the live `status` handler and real signals are untested.

VERDICT: PASS-WITH-FINDINGS
