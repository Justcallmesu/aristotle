# T21 build report

Gate: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T21` → exit 0 (gofmt, vet, 254 tests pass).

## Files
- new `server/internal/service/{service.go,exec.go,service_test.go}` (Runner interface, unit text, Manager; ExecRunner is the only real exec)
- new `server/internal/app/{servicecmds.go,servicecmds_test.go}`
- edited `server/internal/app/{app.go,controlcmds.go,serve.go}` (dispatch + usage, `status` control cmd, D7)
- Not mine: `server/internal/relay/sock_unix_test.go` shows modified in the tree (pre-existing, untouched by me).

## Unit file (`$XDG_CONFIG_HOME/systemd/user/aristotle-server.service`)
```
[Unit]
Description=Aristotle server (relay between the Aristotle app and Hermes)
[Service]
Type=simple
ExecStart=<os.Executable, symlinks resolved> serve
Restart=on-failure
RestartSec=5
NoNewPrivileges=yes
PrivateTmp=yes
UMask=0077
[Install]
WantedBy=default.target
```
No Environment=, no StateDirectory=. Path is quoted and `%`/`$` escaped if needed; relative or newline paths rejected.

## Commands
- `install [--start] [--force]`: write unit (atomic) → `systemctl --user daemon-reload` → `enable` → (`start`). A differing existing unit is refused (exit 1, says rerun with `--force`); an identical one is fine. Prints the `loginctl enable-linger $USER` hint; never runs it.
- `uninstall [--purge [--yes]]`: `disable --now`, remove unit, `daemon-reload`. State dir kept unless `--purge` (prompts y/N; `--yes` skips; `--yes` alone is a usage error). Missing unit is not an error.
- `status`: `is-active`, `is-enabled`, then new control command `status` → version + listener URLs; "not running" if no socket.
- `logs [-f]`: `journalctl --user -u aristotle-server [-f]`.
- D7: `watchSignals`: first SIGINT/SIGTERM cancels, second exits 130. `http.Server.ErrorLog = slog.NewLogLogger(handler, Warn)` in `serveListener`.

## Tests
service: TestUnitText, TestUnitTextQuotesPath, TestUnitDirXDG, TestInstallWritesAndEnables, TestInstallStart, TestInstallIdempotentSameUnit, TestInstallRefusesDifferentUnitUnlessForce, TestInstallEnableFailure, TestUninstall, TestUninstallWhenNotInstalled, TestState, TestLogs.
app: TestInstallCommand, TestInstallRefusesDifferent, TestUninstallKeepsState, TestPurgeConfirmation, TestStatusServerDown, TestLogsCommand, TestWatchSignalsSecondExits130, TestHTTPErrorsGoThroughSlog. All use a fake runner and temp XDG dirs.

## NOT-VERIFIED
- Real systemd behaviour (unit loads, starts at login, restarts on failure): nothing was installed or enabled. Only `systemctl --user --version` (261) was run.
- `status` against a live server (control `status` handler and address printing) has no test; only the server-down path is tested.
- `watchSignals` with real OS signals and `os.Exit` wiring in `runServe`: unit-tested with a fake exit only.
- The `http.Server.ErrorLog` bridge covers the loopback server in `serveListener`; the LAN/Tailscale servers in `internal/listen` are outside T21's file scope and unchanged.
- Journal output and `--purge` against the real state dir.
