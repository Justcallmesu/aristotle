# RS3 — server install fixes (D31) for the release installer

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `6e39b75`. Read `PROJECT.md`, `docs/release/spec.md`, `docs/v1-tickets/tickets.md`
rows D31 (and any other OPEN row targeting T22 that touches `install`/`status`/the unit), `docs/protocol.md` (the service/CLI section),
`server/internal/app/servicecmds.go`, `server/internal/service/service.go` + their tests, `scripts/build-server.sh`.

## Do
1. `install --force` on a RUNNING service restarts it so the new binary/unit is live (and says so); without `--force` on an existing install,
   the current behaviour stays.
2. A symlinked unit file is kept a symlink (write through to its target, or refuse with a clear message naming the target — pick the safer,
   say why).
3. `status` exits non-zero when the service is inactive/failed (0 only when active and answering); output unchanged otherwise.
4. Tests for each with fakes (no real systemd: the existing seam in `service.go`); show each new test RED on a scratch copy first.
5. Update the CLI/service section of `docs/protocol.md` (or the doc that describes `install`/`status`).

## Hard rules
Files: `server/internal/app/**`, `server/internal/service/**`, `docs/protocol.md`, `docs/release/runs/RS3/build-report.md`. Not yours: `android/**`,
`gradle/**`, `scripts/**` (RS2 runs in parallel), other docs, `CHANGELOG.md` (Doc text line in the report). **Forbidden commands:** `systemctl`
(any), `aristotle-server install|uninstall|start|stop`, `loginctl`, `systemd-run`, anything writing under `~/.config/systemd` or `~/.local/bin`
— the real service on this PC is the user's. No commits; never `git checkout/restore/stash/reset`. Avoid `bash -c`/heredocs/chained `$(...)`.
Gate after your LAST edit: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/release/runs/RS3` exits 0.

## Report
`docs/release/runs/RS3/build-report.md`, ≤ 10 lines.
