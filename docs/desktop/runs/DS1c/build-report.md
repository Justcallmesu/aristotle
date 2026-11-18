# DS1c build report — `installed` in `status --json` (P13)

## Changes
- `server/internal/service/service.go:136` `Manager.Installed()`: `os.Stat(UnitPath())` succeeds and is not a directory. Stat follows symlinks, so a symlinked unit counts and a dangling one does not. No systemctl call.
- `server/internal/app/servicecmds.go` (`statusJSON`, `newStatusJSON`, `runStatus`): new top-level `installed` bool, between `enabled` and `server`, fed by `m.Installed()`. Present in every state. Plain and `--porcelain` printers untouched; exit code logic untouched.
- `docs/protocol.md` §22, `status --json` row (line 1694): documents `installed`. It says why `enabled` is not used (absent unit prints `enabled`, exit 0, P13) and that plain/porcelain are unchanged.
- Fixtures: `status.json`, `status_not_running.json`, `status_sparse.json` now carry `"installed":true`. New `status_not_installed.json` (`installed:false`, server down, service inactive).
- Goldens (`golden_test.go`, plain/porcelain assertions) untouched and green.

## Tests
- `service_test.go:384` `TestInstalled`: absent, dangling symlink, symlink to a real file, and no runner calls.
- `json_test.go:374` `TestJSONStatusInstalled`: file / absent / symlink / dangling symlink, each with the server up and down. It checks that `installed` is a bool, that stderr is empty, and that `--porcelain` never mentions it. Uses a temp unit dir and the stub runner.
- `json_test.go:423` `TestJSONStatusNotInstalledFixture`.
- `status_test.go`: `runStatusCmd` now delegates to the new `runStatusUnit(setup)`. By default it writes a unit file (installed), so existing tests keep their meaning. Small helper `installUnit`.

## RED evidence
A scratch copy of `server/` (tests and fixtures in place, no implementation) was run with `go test ./internal/app ./internal/service`. The service test referenced `Installed()` and was stubbed to `false` so the package compiled.
- Result: FAIL in `internal/app`. `TestJSONStatusRunning`, `TestJSONStatusServerDown`, `TestJSONStatusInstalled` (all 8 sub-cases: `installed <nil>`), `TestJSONStatusNotInstalledFixture` and `TestJSONStatusWithOptionalFieldsAbsent` failed on the missing key.
- Result: FAIL in `internal/service`. `TestInstalled`: "symlinked unit reported not installed".
- The scratch copy sits in `$TMPDIR/ds1c-red-202489`. I did not delete it, because an `rm -rf` needs approval.

## Gate (`cd server && gofmt -l . && go vet ./... && go test ./...`)
- `gofmt -l .`: no files, exit 0 (after `gofmt -w` on `status_test.go`).
- `go vet ./...`: exit 0.
- `go test ./...`: every package `ok`, exit 0 (`internal/app` and `internal/service` ran fresh).

## Notes
- The hard rules held. No git write commands, no systemctl, no touch of `~/.config/systemd`, the state dir or Hermes, no installs. I ran `git status --short` once, read-only.
- A directory at the unit path counts as not installed (my addition; the brief did not cover it).
