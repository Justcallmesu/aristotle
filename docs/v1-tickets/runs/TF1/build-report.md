# TF1 build report
- Removed `PrivateTmp=yes` from `UnitText` (server/internal/service/service.go); comment explains the user-namespace implication. NoNewPrivileges, UMask, Delegate kept.
- Golden test and `TestUnitText` updated; new `TestUnitTextNoUserNamespaceOptions` rejects PrivateTmp/Users/Devices/Network/Mounts, Protect*, RestrictNamespaces, path-sandbox and root options (systemd.exec(5) user-mode list; `man` not installed here, so the list is from memory of that page, not re-read).
- `install` on a differing unit: already correct. `--force` replaces it, and the error says "rerun with --force to replace it" (service.go:134). No change.
- docs/terminal.md: "Private /tmp" bullet replaced by a "Shared /tmp" bullet with the reason. CHANGELOG [Unreleased] gets a Fixed section with one Server line.
- Gate: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TF1` exit 0 (gofmt, vet, 481 tests pass).
- Not done: user still must run `install --force` and restart to pick up the new unit; real unit untouched.
