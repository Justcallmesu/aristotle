# DS7b re-review report (DS7 + DF11 + DS7b, fresh context, fix round 1 of 2)

Scope: the uncommitted tree on 3f48ab7 (23 tracked files changed, 1025+/51-, plus the new files). This was a read-only
review. The only repo file written is this report. Probes and mutants ran on a scratch copy
(`~/.hermes/profiles/pm/cache/scratch/ds7b-review/ws`, with its own target dir). The repo diff is unchanged (`git diff --stat`
is the same before and after). The preview tray (pid 242035) was not touched.

## Verdict
**CHANGES REQUIRED. The changes are small and are all in the tray tests; the product code is right.** Every DS7 review
finding the builder took on is closed correctly. Nothing in DS7/DF11 regressed. The PM's hypothesis for the red desktop gate is
**confirmed**. My loops also found a **second, more frequent flake in the same test binary** (H1, about 10-25% of runs). The
release runs the desktop gate, so either flake blocks a release at random. One test gap needs closing too (M2: a mutant that
drops the lock at once survives every test). Both flakes have proven fixes below. M2's fix is a few lines.

What I ran (all on this PC):
- `go test -count=1 ./internal/service/ ./internal/app/`: ok.
- `scripts/test/install_test.sh`: all passed.
- The tray unit-test binary 50x, then the scratch build with diagnostics 60x and 4 x 150 runs (6 parallel loops).
- The tray integration binary (`tests/instance.rs`) 20x: 0 failures.
- `cargo test -p aristotle-tray --bin aristotle-tray` 20x: 2 failures, both H1.
- A fresh-clone licences probe; `gio launch` and `systemd-xdg-autostart-generator` probes for `Exec=`; one mutant.

## The PM's red gate (`instance.rs:89`): cause confirmed
- **Mechanism**, from the diagnostic build: on failure it lists every process with the lock file open. The only holder was a
  child of the test process (`PPid` = the test pid) whose exe was still the test binary. That is a child between fork and exec.
  It holds a duplicate of the lock's open file description, so the `flock` survives `drop(first)` until the child execs. The
  forks come from the launcher tests (`launcher.rs:72`, the fake-script tests).
- **Deterministic probe**: a child held before exec for 500 ms (`pre_exec` sleep), then `drop(first)`. Re-acquire returns
  `Second` while the child waits and `First` after its exec.
- **Rate**: 3 of 150 runs under load (6 parallel loops). 0 of 60 runs one at a time. That matches "the worker's run passed and
  the PM's failed".
- **Production cannot hit it in any way that matters.** The tray never drops the lock while it runs: `_instance` lives to the
  end of `main` (`tray/src/main.rs:51`). The lock `File` is O_CLOEXEC, so the window app and the CLI children drop the
  duplicate at exec. The only window is the microseconds after a tray exits while one of its children sits between fork and
  exec. In that window a new tray would exit 0 and nothing would run. The installer starts the new tray only after the old pid
  is gone (`install.sh:670-673`), so this is negligible.
- **Fix (M1 below)**: unlock explicitly on drop. Proven: the probe shows the lock free during the child's pre-exec, and 0 of
  150 failures under the same load.

## Findings
| # | Sev | Where | Finding | Fix |
|---|---|---|---|---|
| H1 | High (gate) | `desktop/tray/src/launcher.rs:129-130` and `:202-203` (script written), `:72` (spawn); failing asserts `:156`, `:169`, `:187` | **ETXTBSY flake.** Each launcher test writes a fake `aristotle-desktop` script and spawns it, in parallel threads. A fork in another thread inherits the still-open write fd, so the exec of the fresh script fails: `Os { code: 26, ExecutableFileBusy }` (seen through a diagnostic `eprintln` on the scratch copy). Then `open()` returns `Failed`, so `Started` is false, the notice count is 0, or the slot is never set. Measured: the repo binary failed 12 of 50 runs on its own, `cargo test -p aristotle-tray --bin aristotle-tray` 2 of 20, and the scratch build 17 of 150 under load. None of the earlier gate logs shows it (about 6 runs, luck). Introduced with the DF11 tests; DS7b's L8 test (`:196`) adds one more writer. A test-harness race, not a product bug. | Test-only: one `static SPAWN_GATE: Mutex<()>` in `launcher::tests`, held while each script is written and chmodded **and** around every `l.open(..)` call that can spawn. Proven on the scratch copy: **0 of 150** under 6 parallel loops, with M1 applied as well. Do not add a retry to `open()`. Writing to a temp file and renaming does not help. |
| M1 | Medium (gate) | `desktop/tray/src/instance.rs:23`, `:49-50`; test `:82-91` | The race above: a lock released by closing alone stays held while any forked, not-yet-exec'd child holds a duplicate. | Hold a guard instead of a bare `File`: `pub struct Lock(File)` with `Drop` calling `flock(fd, LOCK_UN)` (`LOCK_UN = 8`). Use `Instance::First(Lock)`, and in `main.rs:31` return `Option<Option<instance::Lock>>`. `LOCK_UN` on the shared open file description frees the lock even while a child holds a copy. The tray's behaviour is unchanged: it still holds the lock until it exits, and the kernel still frees it on a crash. Proven: probe and 0 of 150 under load (3 of 150 before). |
| M2 | Medium (test gap) | `desktop/tray/src/main.rs:51`; `desktop/tray/tests/instance.rs:84-96`, `:143-161` | **Mutant survives**: `let _instance = match single_instance()` changed to `let _ = match …` drops the lock right after taking it. With that change a running tray holds no lock, and all 3 integration tests still pass (run on the scratch copy). No test shows that a *running tray* holds the lock: the "first" tray in the tests exits 1 at once (no session bus), and the holder is the test helper, not a tray. Also, `tray()` calls `env_remove("ARISTOTLE_SERVER_BIN")`, so the test tray's first poll runs the **real** `~/.local/bin/aristotle-server` (read-only, but not hermetic). | In `tray()`, set `ARISTOTLE_SERVER_BIN` to a scratch stub script that sleeps about 5 s, so the first poll blocks and the tray stays up before the D-Bus step. Add a test: start that tray, `wait_for(|| take_lock(&runtime).is_none())`, start a second real tray and expect exit 0 with no output, then kill the first. Check the mutant is killed. |
| L1 | Low (reasoned, live check) | `tray/src/instance.rs` (oldest wins); `scripts/install.sh:648-656` | The lock makes the M3 case **single but possibly stale**. A tray started by the installer survives logout (Linger=yes here), and ksni re-registers it with the new panel (default `watcher_offline` keeps it waiting, `tray.rs:162`). The autostart tray at the next login now exits silently. The survivor keeps the old session's environment. This PC's session has `XAUTHORITY=/run/user/1000/xauth_zzZtzo` (random for each login) and `WAYLAND_DISPLAY=wayland-1`. A window opened from that tray can then fail on X11, or if the socket name changes ("The window could not open"). Before DS7b the same case gave two trays, and the newer one worked. | PM live check after the real install: log out and back in, then `pgrep -fa aristotle-tray` (expect 1) **and click Open**. If it fails, either a tray from a different `XDG_SESSION_ID` replaces the holder (write pid and session into the lock file; the newcomer sends SIGTERM and then waits on the lock), or start the installer's tray with `systemd-run --user -p PartOf=graphical-session.target …` so it ends at logout. |
| L2 | Low | `scripts/install.sh:653-655`, `:665-677` | If a tray from **another path** holds the lock (a dev or preview build that already has the lock), the installed tray exits 0 at once, but the installer prints "Started the tray". The builder dropped the second half of N3 for this reason, which is fair: a plain `kill -0` would misreport. **For the PM's real install now**: the preview tray (pid 242035, an older build without the lock) is not in `~/.local/bin`, so it stays up and you get **two icons** after the install. Stop it by its pid before or after installing. | Optional: after `start_tray`, if `desktop_procs` finds no tray but some `aristotle-tray` runs outside `BIN_REAL`, print "another aristotle-tray (<path>) is running; the installed one starts when it stops". |
| N1 | Nit (info) | `scripts/install.sh:555-564` | Quoting follows the Desktop Entry spec. Checked against real parsers: a path with a space, `$`, `'`, `~ & ; \| < > ( ) * ? #`, `"`, `` ` `` or `\` launches correctly with `gio launch` (GLib). systemd's xdg-autostart generator (what Plasma 6 uses for autostart here) handles the **space** case: `systemd-analyze verify` is clean. It produces a unit systemd rejects for `$` (it writes `\$`), and it mis-parses `"`/`\`. Both GLib and the generator refuse a path with `%`, because they look up the binary path without undoing `%%`. These are bugs in those parsers, and such home paths are unrealistic. The P25 (3) case (a space) works in both. | None. Optionally a README note that `$ % " \` in the home path are not supported by every desktop. |
| N2 | Nit | `docs/release/README.md:77` | `pkill -f ~/.local/bin/aristotle-tray` is a regex over the full command line. It also hits an editor or a `tail` that has the path in its arguments. | `pkill -xf "$HOME/.local/bin/aristotle-tray"` (autostart and the installer run it with no arguments). |
| N3 | Nit | `server/internal/app/servicecmds_test.go:194-202` | The relative-`XDG_DATA_HOME` test only asserts exit 0. It does not check that the files at the fallback `~/.local/share/...` were removed. | Create the menu entry and icon under `$HOME/.local/share` first and assert they are gone. |
| N4 | Nit | `server/internal/app/servicecmds.go:113-125` | The tray is now stopped before `newManager`/`Uninstall()`. If `systemctl --user daemon-reload` fails, uninstall exits 1 after stopping the tray but before removing the autostart entry, so the tray comes back at the next login. A re-run fixes it. | Accept, or remove the desktop files before the service when the service step fails. |

## 1. Prior findings: closure check
| Finding | Status | Where |
|---|---|---|
| P25-1 licence gaps | Closed | `scripts/licences_desktop.py:73-89` (texts for every alternative, marker, `Copyright (c) <authors>` or "the <crate> authors"), `:202-213` (an error exits 1, in write and check modes). 4 standard-text entries in the notices file. |
| P25-2 uninstall by dir | Closed | `server/internal/service/desktop.go:94-102` (`EvalSymlinks`), `:128` (`filepath.Dir(exe) != dir`); an empty Dir matches nothing (`:110`). The PM's mutant U1 was killed. |
| P25-3 Exec quoting | Closed | `scripts/install.sh:555-564`, `:578-586` (literal `replace_token`); tests `install_test.sh:762-784`. Real-parser check in N1. |
| M1 Rust std notice | Closed | `licences_desktop.py:155-162` (rustc from `rustc -V`, MIT + Apache-2.0, "The Rust Project Contributors"). |
| M2 symlinked BIN_DIR | Closed | `install.sh:591-594` (`pwd -P`), `:606`; Go side resolved as well (P26 (3)). |
| L1 scan cost | Closed | `install.sh:600-612` (one `find`), `:615-622` (bounded by `$SECONDS`), `:633-637` (SIGKILL, then 2 s more). |
| L3 / N3 (first half) | Closed | `install.sh:653` `( cd / && trap - INT QUIT && exec setsid … )`; my run: cwd `/`, SigIgn `…1000`. |
| L4 fresh clone | Closed | `licences_desktop.py:116-118`, `:176-178`. **Verified for real**: a tree copy with no `node_modules` and an empty `CARGO_HOME` gives exit 0. It ran `npm ci` and `cargo fetch --locked` itself, the output is **byte-identical** to `desktop/THIRD_PARTY_NOTICES.txt`, and `Cargo.lock`/`package-lock.json` are unchanged (sha256). |
| L5 `--locked` | Closed | `scripts/build-desktop.sh:38`; `tauri build --help` (CLI 2.12.5) confirms trailing args go to the runner (cargo). |
| L6 linked set | Closed | `licences_desktop.py:126-147`. My `cargo tree -e normal --target x86_64-unknown-linux-gnu` per binary (desktop with and without `tauri/custom-protocol`, same 288) gives a union of 300 = the 297 notice crates + the 3 workspace crates, with no difference either way. |
| L7 order + XDG | Closed | `servicecmds.go:113-114` (stop before `Uninstall`); `desktop.go:42-52` ignores a relative value like `install.sh:508-512`. Test `servicecmds_test.go:169-190`. |
| L8 slot first | Closed | `desktop/tray/src/launcher.rs:93-99`. |
| N2 wording | Closed | `CHANGELOG.md:14`, `:19`; `server/internal/app/app.go:33`. |
| N5 rollback | Closed | `docs/release/README.md:76-78` (see N2 above for the `pkill` pattern). |
| L9 → P26 (2) | Closed | `install.sh:660-663`, called first at `:696`. |

**Left out on purpose:**
- **L2** (SSH, no display: the old tray is stopped and the new one starts at the next login): safe and documented
  (`CHANGELOG.md:14`, README). It falls short of P24's "not wait for the next login" only in that case. PM accept.
- **N1** (a few ms of drift at the 5 s edge): safe.
- **N3, second half** (the "did it start" check): the builder is right that a naive check would misfire because of the lock.
  L2 above is the useful version.

## 2. Tray lock
- **`XDG_RUNTIME_DIR` unset**: no lock. The tray logs one line and starts anyway (`main.rs:31-43`). Acceptable: every
  graphical session, and the terminal the installer runs from, has it.
- **Held for the whole life**: yes (`main.rs:51`, a named binding until `main` returns). Untested, see M2.
- **A crashed tray releases it**: yes, tested with SIGKILL (`tests/instance.rs:132-140`).
- **Installer replace**: the old tray is stopped by its resolved path and waited for until gone. A zombie's `exe` is unreadable,
  and its fds are already closed. Only then does the new tray start, so the lock is free. A tray that ignores SIGTERM gets
  SIGKILL after about 5 s (tested). If it still survives, the installer warns and does not start the new one, so there are
  never two.
- **A second instance exits before any setup**: it returns before the poller, the tray item, D-Bus or notifications
  (`main.rs:51-54`). Tested: exit 0 with empty stdout and stderr.

## 3. Path matching
Both sides use resolved paths: Go `EvalSymlinks` against `/proc/<pid>/exe`, bash `pwd -P` against `find -printf %l`. Both strip
" (deleted)", both take the exact directory (`${exe%/*}` and `filepath.Dir`), and both match the exact program names. The
uninstall order is right. Nothing outside `BIN_DIR` is signalled: the Go test uses a preview dir, other dirs, a sub-dir and
`bin2`; the install test uses a tray and a window from another path, both left alone.

## 4. Licences
- **Inside the gate**: in `--check` mode nothing tracked changes (verified above). `node_modules` and the cargo registry are
  ignored or outside the repo.
- **Network**: needed only when `node_modules` or the crate sources are missing (crates.io and the npm registry). The release
  needs the network anyway (`npm ci` in the desktop gate), so this is acceptable. `npm ci` runs dependency install scripts; npm
  12 here blocks esbuild's postinstall, which does not matter for the notices.
- **Licence texts**: MIT and BSD-3-Clause are identical to SPDX `license-list-data/text` (fetched). Apache-2.0 and MPL-2.0 are
  byte-identical to `/usr/share/licenses/spdx/` (the Arch `licenses` package). The placeholder copyright lines are removed
  (`:30`, `:69`).
- **Unknown licence**: undeclared, no text, or a `WITH` exception gives exit 1 (`:75-86`, `:210-213`; the `licences_test`
  cases).

## 5. Exec quoting
Correct per the spec. Inside the quotes `\ " ` $` are escaped, then the key-file layer doubles every backslash, then `%`
becomes `%%`, which is the right order. The tests assert the exact lines and decode them with a spec-following decoder, so they
are meaningful. The real-parser results are in N1.

## 6. Regressions
None found:
- DF11: the `--version` paths come before any GTK, Tauri or D-Bus setup (`app/src/main.rs:15-20`, `tray/src/main.rs:46-49`).
- Strings: `core/tests/strings.rs:289-307`.
- The release checks are unchanged.
- The scripts gate and the Go tests pass.
- The desktop gate is red only because of H1 and M1.

## Fix round (last): what to do
1. H1: the test-only spawn gate in `launcher::tests`.
2. M1: the `Lock` guard with `LOCK_UN` on drop.
3. M2: a test with a stub server CLI proving that a running tray holds the lock; check the mutant is killed.
4. Verify: the tray unit binary in 6 parallel loops x 25 (0 failures) plus the full desktop gate twice.
5. Optional: L2, N2, N3.
6. PM, at the real install: stop the preview tray by its pid; then do the L1 live check (log out and in, one tray, Open works).
