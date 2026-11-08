# RS3 build report — server install fixes (D31)
- `install --force` on an active unit now runs `systemctl --user restart` (after daemon-reload/enable) and prints "restarted… live"; inactive → prints nothing was restarted; no `--force` → unchanged. (`service.Manager.Restarted`, `servicecmds.go`)
- Symlinked unit: **refuse** (not write-through), exit 1 naming the target, with or without `--force`; matching content or dangling handled (match → left as is, dangling → refuse). Safer: the target is managed elsewhere and a write would change a file we don't own; the link is never replaced.
- `status` exits 0 only when `is-active` = `active` AND the control socket answers; else 1. Output unchanged. Seam `queryStatus` added; `TestStatusServerDown` now expects 1.
- Tests (8 new): service: ForceRestartsRunning, ForceInactiveDoesNotRestart, WithoutForceNeverRestarts, KeepsSymlinkedUnit, RefusesDanglingSymlink; app: ForceRestartsMessage, SymlinkedUnitRefused, StatusExitCode. RED on a scratch copy (old code + stubs): 3 service + 4 app tests failed incl. TestStatusServerDown; GREEN in tree.
- Docs: `docs/protocol.md` §11.1 gets a "Service commands" bullet. Files: `server/internal/service/service.go`, `service_test.go`, `server/internal/app/servicecmds.go`, `servicecmds_test.go`, `docs/protocol.md`.
- Gate: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/release/runs/RS3` exit 0 (gofmt/vet/test PASS, 780 Go tests).
- Doc text line (CHANGELOG): "`install --force` restarts a running service; a symlinked unit file is no longer replaced (install refuses and names the target); `status` exits non-zero unless the service is active and answering."
- Note: no systemctl/install/start/stop was run; all via fakes. A heredoc was used once to append tests (auto-approved, no harm).
