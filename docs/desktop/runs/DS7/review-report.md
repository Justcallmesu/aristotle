# DS7 + DF11 review report (fresh context)

Scope: uncommitted tree on 75461e3. Read-only review. The only file written is this report, plus scratch probes, which have been deleted.

## Verdict
**CHANGES REQUIRED: the P25 fix round plus two new Medium findings (M1 Rust std notice, M2 symlinked bin dir). No
blocker in the DS7/DF11 code itself.** The installer step is careful. Checksums run before any change, a failure in the step
only warns, the binary replace is atomic with `.prev`, the tray is matched by exe path including "(deleted)", and the new tray
is detached properly. The three P25 items are confirmed with locations below. Two new Medium findings (M1, M2) should go into
the same round, and M3 needs a live check by the PM. The others are Low or Nit.

What I ran myself (all exit 0): `scripts/test/install_test.sh` (302 ok, "all passed"), `scripts/test/release_test.sh`
("all passed"), `go test -count=1 ./internal/service/ ./internal/app/`, and `go vet` on both packages. After the install test
only the user's preview tray (pid 242035) was running; it was left untouched.

PM gate note: `runs/DS7/pm-gate-full/gate-summary.txt` records **`FAIL android (1)`**
(`RunningViewModelTest.kt:78`, 1/1490). The diff does not touch `android/`, and AF1 is triaging it. The "full project gate"
is therefore not green as recorded, and `release.sh` will refuse until it is.

## P25 (decided, not built): what the fix round must do
| # | Sev | Where | Finding / fix |
|---|---|---|---|
| P25-1 | High | `scripts/licences_desktop.py:46-58`, `:127-134`; `scripts/licences.sh:88` | Gaps are only listed. Exit is 0 in both modes. Need: vendored standard texts (MIT, BSD-3-Clause, Apache-2.0, MPL-2.0) inserted under the marker "standard text of the declared licence; the crate ships none". For `A OR B`, include every alternative (or the first, if the PM picks that), never a guess. A missing or unknown expression must make `--check` (and generation) exit non-zero. MIT and BSD-3 require the **copyright notice**, and a standard text has only a placeholder. Add the manifest `authors` as the holders line, or say plainly that none is declared. Current gaps: alloc-stdlib (BSD-3-Clause), dlopen2 and dlopen2_derive (MIT), selectors (MPL-2.0), libappindicator-sys (see L6: not actually linked). |
| P25-2 | High | `server/internal/service/desktop.go:85-110` (match at `:99-100`); caller `server/internal/app/servicecmds.go:145` | `StopProcesses` matches the exe **basename anywhere**. Confirmed live: the user's preview tray `…/cache/scratch/preview/aristotle-tray` (pid 242035) would get SIGTERM from a real `uninstall` today. Fix: add a `Dir` field and match `filepath.Dir(strings.TrimSuffix(exe," (deleted)")) == dir`, where `dir` = `filepath.EvalSymlinks($HOME/.local/bin)`. That is the installer's BIN_DIR (`scripts/install.sh:27`), resolved because `/proc/<pid>/exe` is resolved (see M2). Do not use `executable()`'s dir: a dev-built `aristotle-server uninstall` would then miss the installed tray. Tests: add the preview-path and other-dir cases to `desktop_test.go:72-91` and a symlinked-dir case. |
| P25-3 | Medium | `scripts/install.sh:551-557` (`put_entry`), templates `desktop/packaging/aristotle-{tray,desktop}.desktop:6`, test `scripts/test/install_test.sh:634,636` | `Exec=@BIN_DIR@/…` is substituted unquoted. With a home such as `/home/a b`, the Desktop Entry spec splits the value at the space, and `$`, `"`, `` ` ``, `\` and `%` also break it. Fix: substitute the **whole** Exec argument as `"<path>"`. Inside the quotes, backslash-escape `"`, `` ` ``, `$` and `\`. Then apply the key-file escaping (every `\` becomes `\\`) and turn `%` into `%%`. Test with a T_HOME that contains a space and a `$`, and assert the exact Exec line. Go needs no change (it never reads Exec). |

## New findings
| # | Sev | Where | Finding | Fix |
|---|---|---|---|---|
| M1 | Medium | `desktop/THIRD_PARTY_NOTICES.txt` (generator `scripts/licences_desktop.py:120-134`) | The **Rust standard library** (MIT OR Apache-2.0) is statically linked into both binaries but has no notice. The server file has the equivalent entry for Go (`server/THIRD_PARTY_NOTICES.txt:5`, "Go standard library and runtime"). Same class of problem as P25-1. | Add a fixed "Rust standard library and runtime (the toolchain that builds the desktop programs) - MIT OR Apache-2.0" section with both texts, like the server's Go entry. Also optional: Vite's modulepreload polyfill is in `ui/dist` (MIT, a few lines). |
| M2 | Medium | `scripts/install.sh:563-569` (`tray_pids`) vs `:27` | `BIN_DIR` is compared as written, but `/proc/<pid>/exe` is the **resolved** path. Probe (a scratch script that ran a copy of `sleep` through a symlinked dir; since deleted): started as `…/link/bin/aristotle-tray`, exe shows `…/real/bin/aristotle-tray`. With a symlinked home or `~/.local/bin` (Fedora Atomic/Kinoite `/home -> /var/home`, or a dotfiles symlink), the old tray is not found and a **second tray** starts. The tray has no single-instance guard (nothing in `desktop/tray/src/`), so the user gets a duplicate icon and duplicate notifications. | Compare against `BIN_REAL="$(cd "$BIN_DIR" && pwd -P)"` (after `mkdir -p`). Add a symlinked-BIN_DIR case to the update test (`install_test.sh:645-659`). P25-2 needs the same resolved dir. |
| M3 | Medium (reasoned, not live-verified) | `scripts/install.sh:599-607` (`start_tray`) | The installer-started tray belongs to the terminal's scope, not the graphical session. It is `setsid`, so it gets no SIGHUP. This PC has `Linger=yes` (the installer turns it on) and logind `KillUserProcesses` at its default `no`, so `user@1000.service` and that scope survive logout. At the next login the autostart entry starts **another** tray, and there is no single-instance guard. Expected result: duplicate notifications, maybe two icons. | PM: after the real install, log out and back in, then `pgrep -fa aristotle-tray` (want 1). Robust fix, which also covers M2: a tray single-instance guard (e.g. `flock` on `$XDG_RUNTIME_DIR/aristotle-tray.lock`, or own a D-Bus name; the newest instance should win so an update can replace the old one). Alternative: start it as a transient user unit bound to `graphical-session.target`. |
| L1 | Low | `scripts/install.sh:563-569, 577-594` | `tray_pids` forks a subshell plus `readlink` for each /proc entry. Measured 2.5–3.2 s per scan with ~306 processes under load (gradle running, load 18; the `tray_pids` function copied into a scratch script, since deleted). The "SIGKILL after 5 s" is really 50 scans, about 2.5 min worst case, and the final wait 20 more scans. A tray that exits on SIGTERM costs one or two scans, so normal runs are fine. | Read all links in one process, e.g. `find /proc -mindepth 2 -maxdepth 2 -name exe -printf '%h %l\n' 2>/dev/null`, or loop on wall-clock `SECONDS`. |
| L2 | Low | `scripts/install.sh:610-622` | Over SSH (no `DISPLAY`/`WAYLAND_DISPLAY`) a running session tray is **stopped and not restarted** until the next login. P24 (2) says "a running tray is replaced… not wait for the next login". The message at `:620` says so, but the spec is not met there. With `ssh -X`, the new tray gets the forwarded `DISPLAY` and its window opens on the SSH client. | Before stopping, read `DISPLAY`, `WAYLAND_DISPLAY`, `XDG_RUNTIME_DIR` and `DBUS_SESSION_BUS_ADDRESS` from the old tray's `/proc/<pid>/environ` and start the new one with those. Otherwise the PM accepts this and the CHANGELOG line is softened (N2). |
| L3 | Low | `scripts/install.sh:109` (`cd "$HERE"` at top level) → `:604` | The detached tray inherits the installer's cwd, which is the bundle folder. For the second-PC flow from a USB stick or mounted share, the tray keeps the mount busy until logout. | `(cd / && setsid …)` or `cd "$HOME"` in `start_tray`. |
| L4 | Low | `scripts/release.sh:72-77` with `docs/v1-tickets/gates.sh:67` and `scripts/licences_desktop.py:108-109` | The v1 gate's `licences --check` now needs `desktop/ui/node_modules` and the cargo registry sources, but only the desktop gate, which runs **after** it (`npm ci`, cargo build), creates them. Release works on this PC (both exist already), but fails on a fresh clone or after `rm -rf node_modules`. `cargo metadata --offline` also fails if the crates were never fetched. | Run the desktop gate first, or `npm ci` / `cargo fetch --locked` in `licences.sh` before the desktop part. |
| L5 | Low | `scripts/build-desktop.sh:37` | `cargo build` and `tauri build` run without `--locked`. A stale `Cargo.lock` is rewritten, and only the final `check_clean_tree` (`release.sh:163`) catches it, after a ~5 min build. Not a correctness hole. | `cargo build --locked …` and `tauri build --no-bundle -- --locked`. |
| L6 | Low | `scripts/licences_desktop.py:73-92` | `cargo metadata` resolves features across the whole workspace, so it over-includes. Compared with `cargo tree -e normal --target x86_64-unknown-linux-gnu -p aristotle-desktop -p aristotle-tray` (with and without `tauri/custom-protocol`: the same 301 crates), the notices list 17 crates that are not linked (tray-icon, libappindicator, **libappindicator-sys (a listed gap)**, chrono, schemars, jiff, …). Nothing linked is missing (checked). Over-inclusion is harmless, but it creates a false gap. | Optional: use the `cargo tree` crate set, which drops one P25-1 gap. |
| L7 | Low | `server/internal/app/servicecmds.go:114-125` | The service is removed (stopped) **before** the tray is stopped. A tray poll during the stop can show a spurious "server stopped" notice just before the tray gets SIGTERM. A relative `XDG_DATA_HOME` makes uninstall exit 1 after "Service removed." (`desktop.go:44`), while the installer falls back to `~/.local/share` for the same value (`install.sh:506-510`). | Call `desktop.StopProcesses` before `m.Uninstall()`. Make the XDG handling consistent (fall back, as the XDG spec says, or refuse in both). |
| L8 | Low | `desktop/tray/src/launcher.rs:93-98` | The hook runs **before** the `running` slot is cleared. `notification.show()` is a blocking D-Bus call (up to the 25 s default timeout if the daemon hangs), and during it an "Open" click returns `AlreadyRunning` for a dead child. | Clear the slot first, then call `on_early_failure`. |
| L9 | Low | `scripts/install.sh:610-622` | An open old `aristotle-desktop` window keeps running after the update. Its own version is old and the server is new, so P5 shows the "re-run the installer" banner right after the user re-ran it. | Also stop the window app at the installed path (it is stateless), or say "close the window" in the output and README. |
| N1 | Nit | `desktop/tray/src/launcher.rs:88` | `started` is taken on the reaper thread, not at `spawn`. That is a few ms of drift at the 5 s edge. | Pass `Instant` from `open()`. |
| N2 | Nit | `CHANGELOG.md:14` | "replaces a running tray with the new one, so an update takes effect at once" is not true without a graphical session (L2) or for an open window (L9). `server/internal/app/app.go:33` says "stop a running tray", but the window is stopped too. | Reword. |
| N3 | Nit | `scripts/install.sh:604-606`, `/proc/<pid>/status` probe | The background job inherits SigIgn `0x6` (SIGINT and SIGQUIT ignored) from the non-interactive bash `&`. This passes to the tray and to every window app it launches. "Started the tray" is printed even when `setsid`/exec fails. | Optional: `trap - INT QUIT` in a subshell before `exec setsid`, and check `kill -0 $!` after a short wait. |
| N4 | Nit | `docs/desktop/runs/DS7/build-report.md` item 4 | The report says "319 Rust crates". The generated file has 314 `(Rust crate)` sections. | Correct the report. |
| N5 | Nit | `docs/release/README.md:73` | The rollback line covers only the server. The desktop `.prev` copies have no rollback recipe. | Add `mv -f ~/.local/bin/aristotle-tray.prev …` (and for the window app), then re-run the tray. |

## Checked and fine (by brief item)
1. **install.sh**
   - `verify_desktop_listed` (`:99-105`) runs inside `verify_bundle` (`:648`), before any change. A tampered file and an unlisted file are both refused, and the tests assert there is no `~/.local` or `~/.config` and no calls.
   - An older bundle is skipped silently (`:521`).
   - Atomic replace: temp file in the same directory, then `mv`; `.prev` only when the content differs (`:525-541`).
   - Every desktop helper is reached only through `if`, `||` or `&&` and ends with an explicit `|| return 1`, so a failing `readlink`, `grep` or `ldconfig` cannot abort the installer.
   - Any failure ends in `warn`, and the run reaches "Running:" (test `dtfail`).
   - Detach is verified: the probe shows a new SID and PGID, no tty, and output redirected.
   - Headless runs install the files but do not start the tray.
   - The remote-terminal refusal runs first (`:646`).
   - Fresh HOME: `mkdir -p` covers a missing autostart directory (`dtfresh`).
   - The paths written match the documented ones and the Go removal paths exactly.
2. **Uninstall**
   - Removes exactly the three files (test with a neighbouring `other.desktop`) and tolerates missing ones.
   - Keeps the binaries and says so.
   - The fake `/proc` tests are meaningful: real symlinks, "(deleted)", non-pid entries, an unreadable exe, and decoys `-old` and `not-aristotle-desktop`.
   - `useFakes` stops every app test from reaching the real `/proc`.
3. **release.sh**
   - The desktop gate runs after the full gate, both before any build.
   - Version stamping cannot ship stale: I checked that cargo 1.100 rebuilds on an `option_env!` change alone (scratch crate: none → 1.2.3 → 4.5.6). The gate builds without the variable, so every release build flips it. `verify_desktop_versions` (`:117-124`) is the backstop, and its mutant is killed.
   - `SHA256SUMS` covers the 11 files; modes are 755 and 644.
   - The final `check_clean_tree` catches tracked writes: `desktop/app/gen/schemas/*` are tracked and regenerated by `tauri build`, but stayed unchanged across the builds, and `ui/dist` and `target/` are ignored.
4. **Licences**: every crate in the real linked set is present. The notices also cover the 4 npm runtime packages (no QR library: the grid comes from Go) and both OFL texts. Output is deterministic (sorted, read verbatim), and `--check` catches drift (builder evidence plus cmp).
5. **DF11**
   - Notice only for a non-zero exit or a signal within 5 s. Exit 0, or a run longer than 5 s, is silent.
   - The reaper thread always `wait()`s, so no zombie. The text is fixed strings only (P11).
   - Both `--version` checks run before any GTK, Tauri or D-Bus setup (`app/src/main.rs:15-20`, `tray/src/main.rs:29-32`).
   - Strings match design §5 exactly (test `core/tests/strings.rs:289-307`).
6. **Docs**
   - The README install and remove text matches the code today. After P25-2, update the line "stops a running `aristotle-tray` and `aristotle-desktop`" to "from `~/.local/bin`".
   - CHANGELOG lines are user-facing and accurate, apart from N2.
