# DS3 review report: desktop core + tray, plus a re-check of the DS1b/DS1c server diffs

Reviewer: fresh-context deep review. Read-only. No code edited, no git write command, the tray was not launched, and the server
service was not touched.

## Verdict: **CHANGES-REQUIRED**

There is no BLOCKER. The DS3 build is well shaped and its tests are honest, but it does not meet the binding P17 rows yet:
- 5 of the 7 P17 items are not met; a 6th (absent `hermes`) is only partly met.
- 3 of the unmet items are MAJOR.

The DS1b and DS1c server diffs are **APPROVED**. All DS1 review findings are closed, and I found one NIT.

## What I ran (own evidence)
- `desktop/`, with `CARGO_TARGET_DIR=desktop/target`:
  - `cargo fmt --check` passed.
  - `cargo clippy --workspace --all-targets -- -D warnings` printed no warnings.
  - `cargo test --workspace`: 67 passed, 0 failed (16+9+5+10+10+8+9 core tests and 0 in doc tests).
- `cargo tree -p aristotle-tray -e normal` has no gtk, webkit, tauri, glib, gdk, appindicator, wry or tao.
- `server/`:
  - `gofmt -l .` printed nothing.
  - `go vet ./...` exited 0.
  - `go test -count=1 ./...` passed for every package.
- I read the ksni 0.3.7 source (`src/service.rs:247-326`). `update_properties` compares each property itself and sends
  `NewIcon` only when `icon_pixmap_changed()`. So "icon updated only on change" holds, whatever `apply()` returns.

## P17 checklist (binding for the fix round)

| # | P17 row | Status | Evidence |
|---|---|---|---|
| 1 | Absent `hermes` means **Amber**, "Hermes state unknown" | **Partly met** | The state is Amber: `types.rs:97-102` returns false when `hermes` is absent, and `snapshot.rs:132-134` then gives Amber. But the reason is `HermesDown` and the tooltip is "Server running · Hermes not reachable". design.md row 9 asks for its own text (`sub_hermunk`, "UI + tray tooltip"). The test `state.rs` "hermes absent" also expects `Reason::HermesDown`. |
| 2 | `server:"no-answer"` means Red | **Met** | `snapshot.rs:125-130` gives Red with reason `NoAnswer`, and the table test covers it. |
| 3 | Server running but `service` not `active` means Red with its own reason ("running outside the service") | **Not met** | `snapshot.rs:132-139` checks Hermes **before** the service. Running with service `inactive`/`unknown` and Hermes down or absent gives **Amber**. With Hermes reachable it gives Red with reason `ServerStopped` (tooltip "Server stopped"). There is no `Outside` reason and no `st_outside` text. |
| 4 | Menu label "Stop server…" | **Not met** | `strings.rs:129` is "Stop server" and `strings.rs:160` is "Hentikan server", both without the ellipsis. |
| 5 | P5 compares the build version with both `version --json` and `status.result.version` | **Not met** | `snapshot.rs:192-196` compares only the `version` reply. `StatusResult.version` (`types.rs:47`) is never read. |
| 6 | The stamp counts only when `now - 60 s <= stamp <= now + 60 s` | **Not met** | `diff.rs:64-66`: `duration_since(stamp)` fails for a future stamp, and that failure is treated as fresh, so a future stamp is fresh forever. The test `diff.rs:180-183` (`stop_with_future_stamp_counts_as_fresh`, +30 s) still holds under P17, but no test checks +61 s. |
| 7 | A stamp outside the window is ignored **and deleted** | **Not met** | `poller.rs:32-39` deletes the stamp only after a stop has been seen. If a stamp is never consumed (for example a restart faster than one poll), it stays on disk forever. |

## Findings

### MAJOR

**MAJOR-1: a hand-started server or a `service:"unknown"` server shows Amber or "Server stopped" (P17 row 3).**
- Where: `desktop/core/src/snapshot.rs:118-140`.
- Fix:
  1. Add `Reason::OutsideService` and order the checks as design.md §4.1 does: mismatch, then `!installed`, `no-answer`, `!running`, `running && !service_active` (Red, `OutsideService`), then Hermes reachable / not reachable / absent.
  2. Add the string key `TipOutside` = `st_outside` ("Running outside the service" / "Berjalan di luar layanan").
  3. Add table cases for "service inactive + hermes unreachable" and "service unknown + hermes absent" (Red, `OutsideService`). Neither combination is in `tests/state.rs` today, which is why the ordering bug passed.

**MAJOR-2: the expected-stop window is one-sided, and a stamp outside it is never deleted (P17 rows 6 and 7).**
- Where: `desktop/core/src/diff.rs:60-67` and `desktop/core/src/poller.rs:30-44`.
- Effect: after a backwards clock step, a stamp in the future silences the next real crash. A stamp left over from a fast restart stays on disk forever.
- Fix:
  1. Add `fn stamp_in_window(stamp, now) -> bool` that checks `stamp <= now + 60 s && now <= stamp + 60 s`, and use it in `stop_expected`.
  2. In `Poller::tick`, when the stamp read is `Some` and not in the window, delete it, whether or not a stop was seen.
  3. Tests: a stamp at +61 s still notifies; a stale stamp is deleted on a tick with no stop; and a poller test that writes a stamp 120 s old and checks the file is gone after one tick.

**MAJOR-3: P5 does not compare the running server's version (P17 row 5).**
- Where: `desktop/core/src/snapshot.rs:163-197`.
- Effect: an installed but not restarted binary is not flagged.
- Fix: in `poll`, when the status answered with `result`, run `compare_versions(&result.version, desktop)` on every poll (it costs nothing). Combine it with the CLI check: a mismatch on either side gives `Mismatch`.
- Note: `VersionCheck::Mismatch` holds only one server string; keep the first mismatching one, which is what design.md `mm_body {srv}` shows.
- Tests:
  - CLI matches but `result.version` differs gives Red.
  - `result.version` = dev skips the check.

### MINOR

**MINOR-1: no own tooltip for an absent `hermes` (P17 row 1).**
- Where: `snapshot.rs:132-134` and `strings.rs:193-204`.
- Fix: add `Reason::HermesUnknown` when `result.hermes` is `None`, still Amber. Its tooltip key is `TipHermesUnknown` = design `sub_hermunk` (or a short form in the `tip_` style, decided with design.md). Update the "hermes absent" table case.

**MINOR-2: the menu label is missing the ellipsis (P17 row 4, P14, design.md `m_stop ⚠`).**
- Where: `strings.rs:129` and `:160`.
- Fix: change them to "Stop server…" and "Hentikan server…", and pin both in `board_texts_are_exact`.

**MINOR-3: the tray menu follows `server_running` instead of the service state and the version mismatch.**
- Where: `desktop/tray/src/menu.rs:13-25` and `tray.rs:139-150`.
- Effects:
  - `no-answer` with the service active shows **Start**, which does nothing. design.md §4.4 keeps Stop for recovery.
  - A hand-started server shows **Stop…**, which cannot reach it.
  - A version mismatch leaves Start enabled; P5 says "disables actions".
- Fix: drive `plan` from the `Assessment` and `service_active`:
  - Show Stop… when `service == active`.
  - Show Start when the unit is installed and the service is inactive or failed.
  - Disable Start under `VersionMismatch`.
  - Add a test for each case.

**MINOR-4: the dev-build skip misses the server's real dev version.**
- Where: `snapshot.rs:25-28`. The check treats only `""` and `"dev"` as dev, but an unstamped server prints `0.0.0-dev` (`server/internal/version/version.go:9`; the build report smoke saw the same).
- Effect: a release desktop next to a dev-built server goes Red with "versions don't match".
- Fix: also treat `0.0.0-dev` (or any `-dev` suffix) as dev, and add one test to `compare_versions_rules`.

**MINOR-5: the tray logs CLI output (P11: "the app never logs CLI output").**
- Where: `desktop/tray/src/tray.rs:71-72`. It prints `reply.value.service` and `{e}`. For `CliError::Server` the Display (`runner.rs:55-57`) includes the server's `{"error"}` text, which carries systemctl's stderr.
- Fix: log only the variant and exit code. One way: a `CliError::kind()` that returns a static `&str`, with `Display` used only for UI text. Or document in P11 that `{"error"}` text is allowed in the log. Today the code and the spec disagree.

**MINOR-6: the gate can pass with a step missing.**
- Where: `docs/desktop/gates.sh:42-43` and `:49-52`.
- Effects:
  - Without `desktop/Cargo.toml` it records `SKIP desktop` and exits 0.
  - `cargo test` that runs 0 tests passes. The count is printed but never checked.
- Fix:
  - Make a missing `desktop/Cargo.toml` a FAIL (`record FAIL desktop-missing 1`).
  - Fail when `tests < MIN_TESTS`, with `MIN_TESTS=67` kept in the script and raised by each ticket.
- Checked and fine: a missing release binary already fails, because `ldd` returns non-zero (`gates.sh:33`), and a failed build already sets FAILED.

**MINOR-7: the timeout kills only the direct child (check 2).**
- Where: `desktop/core/src/runner.rs:166-169`. `child.kill()` sends SIGKILL to the PID only. A grandchild (systemctl under `start`/`stop`) keeps running, and holds the stdout pipe, so the reader thread (`runner.rs:156-160`) leaks until that grandchild exits.
- Also: the timeout test (`tests/runner.rs:89-103`) uses `exec sleep 30`, so the grandchild case is never tested.
- Fix: spawn with `CommandExt::process_group(0)` and kill the whole group on timeout (`kill(-pgid, SIGKILL)` via `libc`, or `/bin/kill -KILL -- -<pgid>` by absolute path). Add a test whose script runs `sleep 30 &` before `exec sleep 30`.
- If a group kill is not wanted, record that systemctl may outlive the timeout.

**MINOR-8: the env overrides accept a relative path, so the binary is looked up on `PATH` (P4: "by absolute path").**
- Where: `runner.rs:131-132` (`ARISTOTLE_SERVER_BIN`) and `tray/src/launcher.rs:15-16` (`ARISTOTLE_DESKTOP_BIN`). `Command::new("aristotle-server")` with no slash searches `PATH`.
- Also: `launcher.rs:41` uses `current_exe().unwrap_or_default()`, so an empty path becomes `./aristotle-desktop` relative to the current directory.
- Fix: ignore an override that is not `is_absolute()`. If `current_exe()` fails, report the window app as `Missing`.

**MINOR-9: a devices or terminals fetch failure loses notices.**
- Where: `diff.rs:69-72` and `:83-86` return nothing when either side is `None`.
- Effect: one failed `devices list` poll, followed by a pairing, gives no notice, because the next poll compares against `None`.
- Fix: in `Poller`, keep the last known `devices` and `terminals` lists in the baseline when the new fetch failed but the status answered. Add a test: devices `None` in one poll, then a new id, gives one `DevicePaired`.

### NIT
- **NIT-1:** `tray/src/main.rs:68` says "ksni only re-emits the icon when `apply` says it changed". In fact ksni compares properties itself (`service.rs:290-292`), and `apply`'s bool only gates the log line. Reword the comment.
- **NIT-2:** `notifier.rs:26-33` adds the "Open" action to the window-missing notice too, but no handler listens for it. Add the action only when `open_on_click` is set.
- **NIT-3:** notification bodies insert the phone-sent device name raw (`strings.rs:211` and `:221`). KDE renders the body markup subset. Escape `&`, `<` and `>` in names. Paired phones are trusted, so this is a NIT.
- **NIT-4:** `tray.rs:62` ignores `Launch::Failed`, so a spawn that fails is silent. Log the failure, or reuse the window-missing notice.
- **NIT-5:** the "non-empty env var" helper exists three times: `runner.rs:138-140`, `stamp.rs:29-31` and `launcher.rs:15`. Move one `pub fn non_empty_env` into core.
- **NIT-6:** `types::ErrorBody` (`types.rs:194-197`) is not used by the parser; `runner::error_text` (`runner.rs:107-110`) does the same job. Parse into `ErrorBody` or drop the type.
- **NIT-7:** `snapshot.rs` holds three jobs: polling (`Snapshotter`), the tray state (`assess`, `Reason`) and the version rule. P16 lists "tray state" separately. Move `assess`, `TrayState`, `Reason` and `Assessment` to `state.rs`. This is also where MAJOR-1 lands.
- **NIT-8:** the `strings.rs:3` header still names `mockups.html` as the source. Point it at `docs/desktop/design.md` §5, and put the design key on each `Key` variant (for example `/// m_stop`) so parity can be checked mechanically.
- **NIT-9:** the start of a tick can block for 40 s: status, then version, devices and terminals, each with a 10 s timeout (`snapshot.rs:163-190`). That includes the first poll before the icon exists (`main.rs:31`). Consider a shorter timeout for the read-only queries (for example 3 s), keeping 10 s for `start`.

### Checked and fine
- **§22 types:**
  - Every `?` field is `Option` or `#[serde(default)]`, and the arrays have defaults.
  - Unknown fields are ignored; an unknown `server` word maps to `ServerState::Other` (`types.rs:27-29`), which is tested.
  - `no_fixture_is_left_uncovered` (`tests/fixtures.rs:67-82`) compares the files on disk with `COVERAGE` in both directions, so adding a fixture fails it.
  - `Pair`'s Debug hides the code and the payload.
- **Runner:**
  - It uses `Command::new(bin).args(..)` with no shell, `stdin` and `stderr` null, and stdout capped at 4 MB.
  - Parse errors keep no content (`runner.rs:89-99`), and no panic path formats stdout.
  - A `;` argument is passed literally, and that is tested.
- **Diff:**
  - The first poll is silent.
  - A failed or CLI-missing poll on either side is silent and keeps the baseline (`poller.rs:40-42`).
  - Hermes notifies once per transition, and only while the server stays up.
  - The stamp is consumed after an observed stop.
  - CLI missing never notifies.
- **Tray:**
  - It depends only on core, ksni and notify-rust.
  - Start runs `start --json` directly in a thread. Stop… launches `--confirm stop`, Pair launches `--pair`, and left click or Open launches with no args.
  - It holds one tracked child, with a reaper thread per child and the `XDG_ACTIVATION_TOKEN` hand-off.
  - Quit exits the tray only; the window app and the server live on.
  - The poll loop ends when `Handle::update` returns `None`.
- **Strings:**
  - The locale order is `LC_ALL`, then `LC_MESSAGES`, then `LANG`, and the first non-empty one wins (tested).
  - `id`, `id_*`, `id.*` and `id@*` select Indonesian; `ida` does not.
  - Every `Key` has EN and ID text with the same placeholders.
  - Notices carry only names and listener words.
- **Packaging:** the `.desktop` files use an absolute `@BIN_DIR@` placeholder, and the autostart flags are set.

## design.md vs code: Rust-side strings

I compared every row of design.md §5 whose surface is "Rust" or "tray" with `strings.rs` `en()`/`id()` by script.

| design.md key | design EN / ID | code | Status |
|---|---|---|---|
| `m_stop` ⚠ | "Stop server…" / "Hentikan server…" | `MenuStop` "Stop server" / "Hentikan server" | **Mismatch** (MINOR-2) |
| `st_outside` ★ (UI + tray tooltip) | "Running outside the service" / "Berjalan di luar layanan" | none | **Missing** (MAJOR-1) |
| `sub_hermunk` ★ (UI + tray tooltip) | "This server does not report Hermes. Update it with the installer." / "Server ini tidak melaporkan Hermes. Perbarui dengan installer." | none (tray shows `tip_warn`) | **Missing** (MINOR-1) |
| `now_t` (Rust) | "now" / "baru saja" | none | **Missing**: not used by the tray yet; add it or move it to the UI surface |
| `st_notinst` (closest to `TipNotInstalled`) | "Service not installed" / "Layanan belum terpasang" | "Server not installed" / "Server belum terpasang" | **Differs**: use the design wording |
| `mm_title` (closest to `TipMismatch`) | "Server and app don’t match" / "Server dan aplikasi tidak cocok" | "Server and app versions don’t match" / "Versi server dan aplikasi tidak cocok" | **Differs**: align one to the other |
| `st_nocli`, `st_noanswer` | "aristotle-server not found", "Server not answering" (+ ID) | `TipNoCli`, `TipNoAnswer` | Match |
| `m_open`, `m_pair`, `m_start`, `m_quit`, `tip_ok`, `tip_warn`, `tip_err`, `n_paired_t/b`, `n_term_t/b`, `n_herm_t/b`, `n_stop_t/b`, `n_d7_t/b`, `n_open` | – | `Menu*`, `Tip*`, `*Title/*Body`, `TermOn*`, `OpenAction` | Match, EN and ID exact |
| none | – | `NoAppTitle`, `NoAppBody`, `ADevice` | Code only: add them to design.md §5 as Rust rows |

## Re-check of the server diffs: `git diff 167aa4a aa9c5a7 -- server docs/protocol.md`

**Verdict: APPROVED.** Every DS1 review finding is closed.

| DS1 finding | Closed by |
|---|---|
| MINOR-1 (two spellings of the unit name) | `service.go:146-153` uses `UnitName`. The tests (`lifecycle_test.go:18,26`, `json_test.go`) and §22 say `aristotle-server.service`. |
| MINOR-2 (optional status fields not marked) | §22 marks `port?`, `listeners?`, `subnet?`, `hermes?` and `reason?`, and states "never `null`". The new `status_sparse.json` is asserted against the real output (`TestJSONStatusWithOptionalFieldsAbsent`). |
| MINOR-3 (second copy of the marker name) | `terminal.MarkerEnv` is exported and passed into `service.InRemoteTerminal(markerEnv, …)`, so `service` still does not import `terminal`. Two tests cover it: the guard follows the given name, and app uses the terminal constant. |
| NIT-1 | `addrURLs` |
| NIT-2 | the one helper `runStatusCmd` / `runStatusUnit` |
| NIT-3 | `pair` uses `stripJSON`; `--name -x`, `--name --json`, `-json` and `--json=true` are usage errors (tested) |
| NIT-4 | the usage lines mention `[--json]` (tested) |
| NIT-5 | `"unknown"` is documented in §22 and tested |
| NIT-6 | the dead `_ = srv` line is gone |

DS1c `installed` (P13):
- `Manager.Installed()` (`service.go:136-139`) is `os.Stat` (which follows symlinks) and `!IsDir`. It never calls systemctl, which the test checks (no runner calls).
- A dangling symlink reads as false and a symlinked unit as true, tested in both `service_test.go:384-414` and `TestJSONStatusInstalled`, each with the server up and down.
- `status --porcelain` and plain `status` are unchanged (tested).
- The new `status_not_installed.json` matches the real output with exit 1.

- **NIT-S1:** §22 still says "Plain (no `--json`) output is unchanged" (`docs/protocol.md:1687`). DS1b changed three plain behaviours:
  - The usage lines now include `[--json]`.
  - `pair -json` and `pair --json=true` became usage errors.
  - `pair --name -x` is refused.

  All three are documented elsewhere in the same paragraph, but the "unchanged" sentence should say "except the usage lines and the `pair` flag rules above".

## Summary
- **DS3:** CHANGES-REQUIRED.
  - MAJOR: 3, for P17 rows 3, 6/7 and 5.
  - MINOR: 9, including P17 rows 1 and 4, gate holes, process-group kill and P11 logging.
  - NIT: 9.
- **Server DS1b/DS1c:** APPROVED, 1 NIT.
