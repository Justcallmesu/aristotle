# DS7b re-review brief (deep, fresh context; fix round 1 of 2 — after this only a last round)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/desktop/spec.md` (P8, P24, P25, P26), the prior review
`docs/desktop/runs/DS7/review-report.md`, `docs/desktop/runs/DS7b/{build-brief,build-report}.md`. Scope: the whole uncommitted tree
on top of 3f48ab7 (DS7 + DF11 + DS7b; `git diff`, `git status`).

PM proven: full gate GREEN (904 Go, android 1490, licences, scripts) + mutants `runs/DS7b/pm-mutants.log` (uninstall stopping
programs from any dir; second tray instance not detected — both KILLED). **Desktop gate RED** (`runs/DS7b/pm-gate-desktop`):
`instance::tests::the_first_takes_it_the_second_is_refused_and_a_released_lock_is_free_again` failed at `tray/src/instance.rs:89`
(re-acquire after `drop(first)` returned `Second`); the worker's own run passed. PM hypothesis (ASSUMED): launcher tests in the
same test process fork children (fake scripts sleeping 6 s); between fork and exec a child shares the lock's open file description,
so the lock is not free right after `drop`. Confirm or refute (reproduce in a loop, e.g. the tray test binary 50x), say whether
production can hit it (tray spawning the window while holding the lock), and give the fix.

## Check
1. Every prior finding (P25-1..3, M1, M2, L1, L3-L8, N2, N3, N5) closed correctly (file:line); L2, N1 and the half of N3 left out:
   is leaving them out safe?
2. Tray lock: lock path when `XDG_RUNTIME_DIR` is unset; lock held for the whole process life (the `File` not dropped early); a
   crashed tray releases it; the installer's "replace a running tray" still works with the lock (old one exits before the new one
   starts; what if the old one ignores SIGTERM); a second instance exits before registering a tray item or notifying.
3. Uninstall/installer path matching with resolved paths both sides; uninstall order; no process outside `BIN_DIR` touched.
4. `licences.sh` now runs `npm ci` / `cargo fetch --locked` itself: inside the gate that "never modifies source files" — does it
   change tracked files (package-lock, Cargo.lock)? Network needed? Is that acceptable for a release from a fresh clone?
   Licence texts match SPDX originals; unknown licence fails; the crate list = linked crates.
5. `Exec=` quoting correct per the Desktop Entry spec (the reserved characters and `%` escaping), tests meaningful.
6. Nothing regressed in DS7/DF11.

## Output
`docs/desktop/runs/DS7b/review-report.md`: verdict + findings (severity, file:line, fix). Read-only: never edit code; no git write
commands; never run install.sh, release.sh or aristotle-server install/uninstall/start/stop for real (the scripts/test harness is
fine); never touch ~/.local/bin, autostart, applications, the running service or the preview tray; never broad pkill. Tests under
`timeout`; mutants on a scratch copy outside the repo.
