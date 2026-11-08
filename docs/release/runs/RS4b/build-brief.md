# RS4b — install.sh hardening from the RS4 review

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `0a4d3e5` (RS4 committed). Read `PROJECT.md`, your input
`docs/release/runs/RS4/review-report.md` (MINOR-1, MINOR-2, NITs), `runs/RS4/build-report.md`, `scripts/install.sh`, `scripts/release.sh`,
`scripts/test/**`, `docs/release/README.md`. Find how the server marks its remote-terminal shells (grep `server/` for the env var it sets,
e.g. `ARISTOTLE_TERMINAL`; cite file:line in the report; if there is none, use `/proc/self/cgroup` naming the service unit).

## Do
1. **MINOR-1:** `install.sh` refuses to run inside the app's remote terminal (or anything inside the service's cgroup) with a clear message:
   "run this in a terminal on the PC, not from the phone: the restart would end this session mid-update". README: an update closes every
   open phone terminal.
2. **MINOR-2:** when `aristotle-server install --force --start` fails, print the rollback command and the journal tail (same as the status
   timeout path; one shared function), exit non-zero. Test it.
3. Executable bit on `scripts/release.sh`, `scripts/install.sh` (and the bundle's copy keeps it); test that the bundled install.sh is executable.
4. Tests for the three `release.sh` refusals the review lists as untested.
5. Other NITs that are one-liners; list the skipped ones. NOT in scope (deferred to the public step): nested-jar asset scan, static/trimpath binary.

## Hard rules
Files: `scripts/**`, `docs/release/README.md`, `docs/release/runs/RS4b/build-report.md`. Nothing else. Forbidden: running `install.sh`/`release.sh`
against the real HOME or repo; systemctl; real `aristotle-server install|start|stop|pair`; `~/.local/bin`, `~/.config/systemd`,
`~/.config/aristotle` (never read it); git tag/push/commit/checkout/restore/stash/reset; adb. Scratch copies + temp HOME + stubs.
Avoid `bash -c`/heredocs/chained `$(...)` in YOUR commands. Gate after your LAST edit: `bash scripts/test/run.sh` exits 0 (the PM runs the full gate).

## Report
`docs/release/runs/RS4b/build-report.md`, ≤ 10 lines.
