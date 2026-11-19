# DS7b build report (fix round 1 of 2, DS7 review findings)

Both gates are green. Everything is uncommitted (no git write command was run). Nothing was installed, and no `install.sh`,
`release.sh` or `aristotle-server install/uninstall/start/stop` was run for real. The preview tray (pid 242035) was untouched.
Every test process I started was a stub copy of `sleep` in a scratch directory, and none was left running.

## Per item (file:line, tests, RED)
RED means the new test failed against the DS7 code. Where DS7 code was overwritten, I saved the DS7 version in scratch and
re-ran the new test against it.

| # | Change | Tests | RED seen |
|---|---|---|---|
| 1 P25-1 | `scripts/licences_desktop.py:55-103` (`spdx_ids`, `standard_text`, `declared_texts`, `section`). A crate with no licence file gets the standard text of every licence in its declared SPDX expression (all alternatives of `A OR B`), marked "standard text of the declared licence; the crate ships none", plus a line `Copyright (c) <authors>` or `Copyright (c) the <crate> authors`. The `<year> <holders>` placeholder line is dropped. No licence declared, an id with no text, or a `WITH` exception makes the generator exit 1 and names the crate on stderr (`:202-216`). The "Gaps" tail is gone. `scripts/licences.sh` runs the generator for write and `--check`, so both exit non-zero. Texts are in `scripts/licence-texts/{MIT,BSD-3-Clause,Apache-2.0,MPL-2.0}.txt`. MIT and BSD-3 come from the SPDX `license-list-data` repo and Apache and MPL from `/usr/share/licenses/spdx`. | `scripts/test/licences_test.sh` (new, 37 checks): scratch cargo workspace with path crates (MIT with authors, BSD-3 without, MPL, `MIT OR Apache-2.0`, a shipped file, undeclared, `WTFPL`, `WITH`) | DS7 generator: 25 failures |
| 2 M1 / P26 (5) | `licences_desktop.py:150-163` (`rust_version`, `rust_std_notice`): first entry "Rust standard library and runtime (rustc 1.100.0, the toolchain that builds the desktop programs) - MIT OR Apache-2.0", with both texts and `Copyright (c) The Rust Project Contributors`. The version comes from `rustc -V`. | same file | same run |
| 3 L6 | `licences_desktop.py:126-148`. The crate set is `cargo tree --locked --offline -e normal --target x86_64-unknown-linux-gnu -p aristotle-tray -p aristotle-desktop`, and `cargo metadata` supplies the manifest, licence and authors. It stops if `cargo tree` lists a crate that `cargo metadata` lacks. | the Windows-only, dev-only and build-only dependency crates must not appear | same run |
| 4 L4 | I picked "licences.sh needs nothing prepared": `licences_desktop.py:105-124,174-180` runs `npm ci` when `desktop/ui/node_modules` is missing, and `cargo fetch --locked` once when an offline cargo call fails, then retries. It is simpler than reordering the gates, and `gates.sh --check` alone works on a fresh clone too. | fresh clone (stub npm creates `node_modules`), unfetched crates (stub cargo fails the first offline call) | same run |
| 5 P25-2 + P26 (3)(4) | `server/internal/service/desktop.go:70-125`: `Desktop.Dir` (the installer's `$HOME/.local/bin`, `InstalledBinDir`/`NewDesktop`), resolved with `EvalSymlinks` (`resolvedDir`). `StopProcesses` signals only when `filepath.Dir(exe without " (deleted)") == Dir`. An empty Dir matches nothing. `servicecmds.go:114`: the tray and window are stopped before `Uninstall()`, and `removeDesktop` only removes files. `desktop.go:42` `xdgBase`: a relative `XDG_*` value falls back to the default, as `install.sh` does (`xdg_dir`), so uninstall no longer exits 1 after "Service removed." `app.go:33` usage text. | `desktop_test.go`: preview, other-dir, sub-dir and `bin2` cases, empty Dir, symlinked dir, `NewDesktop` Dir, relative-XDG fallback. `servicecmds_test.go`: tray signalled before any systemctl call and only the installed-dir pid, uninstall with a relative `XDG_DATA_HOME`. The old uninstall test now passes `Dir`. | 6 failures before the code change (Dir field present, behaviour absent) |
| 6 P25-3 | `scripts/install.sh:555-588` (`desktop_exec_arg`, `replace_token`, `put_entry`). The template's `@BIN_DIR@/<program>` becomes the whole Exec argument as `"<path>"`: `\ " ` $` get a backslash, then the key-file escape doubles every `\` and `%` becomes `%%`. `replace_token` replaces literally (no `&` handling). The template comments no longer contain the placeholder. | `install_test.sh`: HOME `home a b$c` (exact Exec line) and HOME `h"q%r\s`t` (exact line). A Python decoder follows the spec (key-file unescape, then quoted-argument unescape, then `%%`) and checks both entries decode to the one program path. The existing Exec assertions now expect quotes. | 8 failures against the DS7 installer |
| 7 P26 (1) | New `desktop/tray/src/instance.rs`: `flock(LOCK_EX\|LOCK_NB)` on `$XDG_RUNTIME_DIR/aristotle-desktop/tray.lock`, declared with `extern "C"` (no new dependency; Rust's `File` is close-on-exec, so the window child does not inherit the lock). `main.rs:4,31-47,51`: a second instance returns from `main` (exit 0, silent) before the poller, the tray item or any D-Bus call. If there is no runtime dir or the lock cannot be created, it logs one line and starts anyway. | `tray/tests/instance.rs` (new, 3 tests + 1 helper): a helper process (this test binary, `--ignored --exact`, like the logs orphan test) holds the lock and the real `aristotle-tray` binary must exit 0 with empty stdout and stderr. The lock is freed when the holder is killed. A lone tray proceeds. Plus 3 unit tests in `instance.rs`. The gate lists `tests/instance.rs\|instance` in `REQUIRED_TEST_BINARIES`. | the second tray exited 1 ("could not start the tray: D-Bus connection error"), before `instance.rs` existed |
| 8 M2 | `install.sh:591-596` (`resolve_bin_dir`: `BIN_REAL=$(cd BIN_DIR && pwd -P)`) and `:600-612` (`desktop_procs` compares against `BIN_REAL`). | `install_test.sh` "symlinked HOME" case | 1 failure: old tray not found, "Stopped the old tray" missing |
| 8 P26 (2) | `install.sh:660-664` `close_window_app` runs before the files are replaced (`:690` `install_desktop`): an `aristotle-desktop` from the installed path is closed, one elsewhere is left alone. Message "Closed the open window (aristotle-desktop)". | "open window" case | 2 failures |
| 8 L1 | `install.sh:600-645`: `desktop_procs` does one `find /proc -mindepth 2 -maxdepth 2 -name exe -printf '%h %l\n'` instead of a subshell and `readlink` per entry. `wait_desktop_gone` does one scan per round, bounded at 5 s by `$SECONDS`. After that, SIGKILL, then at most 2 s more. | a tray that ignores SIGTERM (ignored disposition survives `exec`) must be gone and the install must take at most 9 s | DS7 installer: 34 s (limit 9) |
| 8 L3 / N3 | `install.sh:648-658` `start_tray`: `( cd / && trap - INT QUIT && exec setsid … )`. The stub tray (`scripts/test/stubs/aristotle-tray`) records its cwd and `SigIgn`. | "detach" case: cwd `/`, `SigIgn & 6 == 0` | 2 failures (`SigIgn=…1006`, cwd = bundle dir) |
| 8 L5 | `scripts/build-desktop.sh:36-38`: `cargo build --release --locked …` and `tauri build --no-bundle -- --locked`. | `scripts/test/build_desktop_test.sh` (new): real `build-desktop.sh` on a scratch tree with stub npm, cargo and tauri, asserting the arguments | 2 failures |
| 8 L8 | `desktop/tray/src/launcher.rs:87-103` (`reap`): the `running` slot is cleared before `on_early_failure`. | `the_running_slot_is_clear_while_the_early_failure_notice_is_shown` | failed: "the slot was still set during the notice" |
| 8 N5, N2 | `docs/release/README.md` (the rollback line names both desktop `.prev` files, plus the install, uninstall and single-instance text), `CHANGELOG.md` (Added lines: the notices wording, single-instance, "closes an open window … starts at once in a desktop session, otherwise at your next login", "from `~/.local/bin`"). | n/a | n/a |
| 9 | `docs/desktop/gates.sh`: `MIN_TESTS=203` (was 194), `tests/instance.rs\|instance` added to the required test binaries. The UI floor is unchanged at 301. | n/a | the gate reports `rust-tests passed=203` |

`desktop/THIRD_PARTY_NOTICES.txt` was regenerated with the generator. It now has 297 Rust crate sections (the DS7 report said 319 and
the review counted 314), plus the Rust std entry. It carries 4 standard-text entries: alloc-stdlib (BSD-3-Clause), dlopen2
and dlopen2_derive (MIT), and selectors (MPL-2.0). `libappindicator-sys` and 16 other unlinked crates dropped out.
`licences.sh --check` reports "desktop notices: up to date".

## Gates (summaries verbatim)

`timeout 3000 bash docs/v1-tickets/gates.sh docs/desktop/runs/DS7b/self-gate-full` (exit 0):
```
PASS server-gofmt (0)
PASS server-vet (0)
PASS server-test (0)
go-tests passed=904 failed=0
PASS android (0)
PASS licences (0)
PASS scripts (0)
```
The scripts gate ran `build_desktop_test`, `install_test`, `licences_test`, `release_test` and `root_install_test`, all PASS.

`timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DS7b/self-gate-desktop` (exit 0):
```
PASS ui-ci (0)
PASS ui-lint (0)
PASS ui-typecheck (0)
PASS ui-test (0)
ui-tests passed=301
PASS ui-test-count (301)
PASS ui-build (0)
PASS desktop-fmt (0)
PASS desktop-clippy (0)
PASS desktop-test (0)
rust-tests passed=203 failed=0
PASS desktop-test-count (203)
PASS desktop-test-binaries (0)
PASS desktop-tray-build (0)
PASS desktop-tray-ldd (0)
PASS desktop-app-build (0)
PASS desktop-app-binary (0)
```
The first desktop run had `FAIL desktop-fmt`, because my new Rust files were not formatted. I ran `cargo fmt` and re-ran, and the
summary above is the second run. The Go gate floor needs no change: the v1 gate has none.

## Left / notes
- Not done, on purpose: L2 (an SSH session with no display does not restart the tray), N1 (`started` taken on the reaper
  thread) and the second half of N3 (a "did the tray really start" check). A tray that exits 0 at once because of the new lock
  would look like a failed start, so I did not add one. L2's wording is softened in the CHANGELOG.
- M3 (the installer-started tray surviving logout and autostart then starting a second one) is now harmless because of the
  lock. The PM can still do the live check (log out, log in, `pgrep -fa aristotle-tray`, expect 1).
- Not run for real: `build-desktop.sh` with the new `-- --locked` for `tauri build`. The tests use stubs. The PM's release run is the first real use, so if
  the Tauri CLI rejected the argument it would fail there, at once.
- The notices file records the rustc version (`rustc 1.100.0`). After a toolchain update `licences.sh --check` reports it
  stale until regenerated.
- The DS7 versions of `licences_desktop.py` and `install.sh` used for the RED runs are in
  `~/.hermes/profiles/pm/cache/scratch/ds7b/` (`old_*`), not in the repo.
- Files touched: `scripts/{install.sh,licences.sh,licences_desktop.py,build-desktop.sh}`, new `scripts/licence-texts/*`,
  `scripts/test/{install_test.sh,licences_test.sh,build_desktop_test.sh}` and `stubs/aristotle-tray`,
  `server/internal/{service/desktop.go,service/desktop_test.go,app/servicecmds.go,app/servicecmds_test.go,app/app.go}`,
  `desktop/tray/{src/instance.rs,src/main.rs,src/launcher.rs,tests/instance.rs}`, `desktop/packaging/*.desktop` (comment only),
  `desktop/THIRD_PARTY_NOTICES.txt`, `docs/desktop/gates.sh`, `docs/release/README.md`, `CHANGELOG.md`.
