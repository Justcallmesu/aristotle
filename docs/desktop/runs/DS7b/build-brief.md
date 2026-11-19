# DS7b fix brief — DS7 review findings (fix round 1 of 2)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Input: `docs/desktop/runs/DS7/review-report.md` (read in
full; file:line + fixes). Spec `docs/desktop/spec.md` **P24, P25, P26** (binding). The DS7 + DF11 code is uncommitted in the tree:
build on it.

## Fix (each behaviour change with a test seen failing first; never via git)
1. **P25-1**: licence gaps: the standard text of the declared licence (SPDX from `Cargo.toml`), marked "standard text of the
   declared licence; the crate ships none", with a copyright line from the crate's `authors` (or "the <crate> authors" when empty);
   an undeclared/unknown licence makes `licences.sh --check` (and write) exit non-zero. Standard texts live in the repo (e.g.
   `scripts/licence-texts/MIT.txt`, `BSD-3-Clause.txt`, `MPL-2.0.txt`, `Apache-2.0.txt`), copied from the SPDX originals.
2. **P26 (5) / M1**: a Rust standard library entry (rustc version from `rustc -V`, MIT OR Apache-2.0 texts).
3. **L6**: only crates actually linked into the two binaries (`cargo tree -e normal --target <host>` for `aristotle-tray` and
   `aristotle-desktop`, or `cargo metadata` resolve filtered the same way); the build report's crate count corrected.
4. **L4**: a release from a fresh clone works: the licence check does not depend on `node_modules` existing (read
   `package-lock.json` + the lockfile's packages, or have release run the desktop step first; pick the simpler, explain).
5. **P25-2 + P26 (3)(4)**: uninstall stops only processes whose resolved `/proc/<pid>/exe` equals the resolved installed path;
   stops the tray (and window) before the service; XDG relative paths handled the same way as `install.sh` (read both, align).
6. **P25-3**: `Exec=` quoted per the Desktop Entry spec; tests with a HOME containing a space.
7. **P26 (1)**: tray single-instance lock (`desktop/tray`): second instance exits 0 without registering a tray item; test with two
   processes (a helper like the logs orphan test). Uses `flock` via libc (no new deps).
8. **M2 + P26 (2)(3)**: installer compares resolved paths; also stops an `aristotle-desktop` running from the installed path before
   the replace; **L1**: one `/proc` scan per wait iteration at most, bounded total wait (~5 s) then SIGKILL; **L3**: the detached
   tray starts with `cd /` (or `$HOME`); **L5**: `--locked` for cargo in `build-desktop.sh`; **L8**: launcher clears its running
   slot before sending the failure notice; **N3**: the detached tray resets SIGINT/SIGQUIT to default; **N5**: the rollback line
   also names the desktop `.prev` files; **N2** CHANGELOG wording.
9. Gate floors in `docs/desktop/gates.sh` = new totals.

## Files
`scripts/**`, `server/internal/app/**`, `server/internal/service/**`, `desktop/**` (not target/node_modules),
`docs/desktop/gates.sh`, `desktop/THIRD_PARTY_NOTICES.txt`, `CHANGELOG.md`, `docs/release/**`.

## Verify
`timeout 3000 bash docs/v1-tickets/gates.sh docs/desktop/runs/DS7b/self-gate-full` (server, android, licences, scripts — android
must be green; AF1's fix is committed) and `timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DS7b/self-gate-desktop`; paste
both summaries verbatim.

## Hard rules
Never git write commands. No installs/new deps. Never run `install.sh`, `release.sh` or `aristotle-server install/uninstall/start/
stop` for real; never touch `~/.local/bin`, autostart, applications, the running service, or the preview tray
(`~/.hermes/profiles/pm/cache/scratch/preview/`). Never use broad `pkill`/`killall` patterns: kill only PIDs you started. No
skill/memory edits.

## Report
`docs/desktop/runs/DS7b/build-report.md`: per item change (file:line), tests, RED evidence, both gate summaries, anything left.
