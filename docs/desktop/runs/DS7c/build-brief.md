# DS7c fix brief — DS7b re-review findings (fix round 2 of 2, the last)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Input: `docs/desktop/runs/DS7b/review-report.md` (read in
full). Spec **P26, P27** (new, binding). The reviewer's scratch copy with both test fixes applied and diagnostics:
`~/.hermes/profiles/pm/cache/scratch/ds7b-review/ws` (read it for reference; write your own code in the repo).

1. **P27 (2)**: lock guard type whose `Drop` calls `flock(LOCK_UN)` (`desktop/tray/src/instance.rs`, `main.rs`).
2. **High**: launcher tests "Text file busy": one shared test lock held while each fake script is written and around every spawn
   (`desktop/tray/src/launcher.rs` tests). Same pattern in other test modules that write+spawn scripts in parallel: check and fix.
3. **Medium**: integration test proving a running tray holds the lock: `tests/instance.rs` points `ARISTOTLE_SERVER_BIN` at a stub
   that sleeps (never the real `~/.local/bin/aristotle-server`), the tray stays up, a second real tray exits 0, the lock is held
   while the first runs. Must fail if `main.rs` drops the lock right after taking it.
4. **P27 (1)**: watcher offline for 30 s -> the tray exits 0 (online again within 30 s cancels). Pure timer logic unit-tested
   (inject the clock/duration); the ksni callbacks only feed it.
5. **P27 (3)**: installer "Started the tray" only when the process is alive after ~1 s; else the other-tray line with pid and exe
   path. Tests.
6. Nits: README rollback `pkill -xf "$HOME/.local/bin/aristotle-tray"`; the relative-`XDG_DATA_HOME` test asserts the files land in
   the default dir; uninstall: remove the autostart entry before stopping the tray and the service (so a failed service step does
   not leave an autostart entry for a stopped tray) — keep "tray stopped before the service".

Each behaviour change with a test seen failing first (never via git). Floors in `docs/desktop/gates.sh` = new totals.
Files: `desktop/**` (not target/node_modules), `scripts/**`, `server/internal/service/**`, `server/internal/app/**`,
`docs/release/**`, `docs/desktop/gates.sh`.
Verify (paste both summaries verbatim): `timeout 3000 bash docs/v1-tickets/gates.sh docs/desktop/runs/DS7c/self-gate-full` and
`timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DS7c/self-gate-desktop`; then the tray test binary 50 times in 4
parallel loops (`cargo test -p aristotle-tray`), 0 failures, counts in the report.
Hard rules: never git write commands; no installs/new deps; never run install.sh, release.sh or aristotle-server
install/uninstall/start/stop for real; never touch ~/.local/bin, autostart, applications, the running service or the preview tray;
never broad pkill/killall (kill only PIDs you started); no skill/memory edits.
Report: `docs/desktop/runs/DS7c/build-report.md`.
