# DS1c build brief — `installed` in `status --json` (P13)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Spec `docs/desktop/spec.md` P13 (read it). Contract:
`docs/protocol.md` §22. Pattern: how `status --json` builds its top-level object today (`server/internal/app/servicecmds.go`,
`runStatus`/`serverState`) and `service.Manager.UnitPath()` (`server/internal/service/service.go:131`).

## Goal
`status --json` gets a top-level `installed` bool = the unit file exists at `Manager.UnitPath()` (a symlinked unit counts as
installed; a dangling symlink does not). Present in every state, including server down. Plain `status` and `status --porcelain`
output stay byte-identical (porcelain gets no new line). Exit code unchanged. Document it in §22 and say there why `enabled` is not
used (on this PC `systemctl --user is-enabled <absent unit>` prints `enabled`, exit 0).

## Tests
Installed / not installed / symlink / dangling symlink with a temp unit dir (stub runner, never real systemctl); fixtures updated
(`status.json`, `status_not_running.json`, `status_sparse.json`) plus `status_not_installed.json`; goldens untouched and green.
See the new tests RED first against a scratch copy (never git). Verify: `cd server && gofmt -l . && go vet ./... && go test ./...`.

## Files you may create/edit
`server/internal/app/**`, `server/internal/service/**`, `docs/protocol.md` (§22 only).
## Hard rules
Never git write commands; never run systemctl or touch the running service, `~/.config/systemd`, `~/.local/state/aristotle`,
Hermes. No installs, no skill/memory edits.
## Report
`docs/desktop/runs/DS1c/build-report.md` (changes with file:line, tests, RED evidence, gate tail with exit codes).
