# DS7 + DF11 review brief (deep, fresh context)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/desktop/spec.md` (D12, D14, D18, P5, P8, P11,
**P24, P25**), `docs/desktop/design.md` §5 (new `WindowFailed*` rows) and §6, the briefs and reports in `docs/desktop/runs/DS7/` and
`docs/desktop/runs/DF11/`, `docs/release/README.md`. Scope: the uncommitted tree on top of 75461e3 (`git diff`, `git status`).
This code installs and removes files in the user's home and kills processes: the highest-stakes ticket so far.

## Already proven by the PM
Full project gate (server, android, licences, scripts) and desktop gate in `runs/DS7/pm-gate-full`, `runs/DS7/pm-gate-desktop`;
mutants `runs/DS7/pm-mutants.log` (webkit check inverted; replaced tray with a "(deleted)" exe not found; release skipping the desktop
version check). P25 (licence gap text, uninstall path restriction, Exec quoting) is decided but NOT built yet: report what P25 needs
as findings with file:line so the fix round can do it.

## Check (highest stakes first)
1. **install.sh desktop step**: nothing outside the documented paths is written/removed; atomic binary replace + `.prev`; the old
   tray is stopped only when its exe is the installed path (incl. "(deleted)" after the replace); new tray started detached, not as a
   child that dies with the installer (setsid, stdin/stdout/stderr redirected, no SIGHUP on terminal close); a failure anywhere in
   the step warns and the server install still reports success; `verify_bundle` refuses a tampered/unlisted desktop file before ANY
   change; an older bundle without desktop files is skipped; `set -euo pipefail` traps (a failing `readlink`/`grep` must not abort
   the whole installer); runs with no display (headless/SSH) and inside the app's remote terminal refusal; second-PC case (fresh
   HOME, no `~/.config/autostart` dir).
2. **uninstall (Go)**: removes exactly the three files; kill logic (what does it match today, P25 (2)); errors; tests with fake
   `/proc` meaningful.
3. **release.sh**: desktop gate placement, version stamping really reaches both binaries (`ARISTOTLE_VERSION` at compile time:
   check cargo rebuilds when only the env changes — stale `target/` could ship an old version string!), `--version` check, checksums
   and modes, clean-tree check after the build (does the UI build or cargo write tracked files?).
4. **Licences**: the notices file covers every runtime crate of the two binaries and the npm runtime deps + fonts; generation is
   reproducible; `--check` catches drift; the 5 gaps (P25 (1)).
5. **DF11**: notice only on non-zero/signal within 5 s; no zombie; no child output in the text (P11); `--version` before any setup.
6. CHANGELOG lines user-facing and accurate; README install/remove instructions match the code.

## Output
`docs/desktop/runs/DS7/review-report.md`: verdict + findings (severity, file:line, fix). Read-only: never edit code; no git write
commands; never run `install.sh`, `release.sh` or `aristotle-server install/uninstall/start/stop` for real (the test harness with a
temp HOME is fine); never touch `~/.local/bin`, autostart, applications, the running service or the preview tray in
`~/.hermes/profiles/pm/cache/scratch/preview/`. Tests under `timeout`.
