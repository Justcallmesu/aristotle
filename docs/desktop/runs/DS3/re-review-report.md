# DS3 re-review: DS3 + DS3b + DS3c as one uncommitted package

Reviewer: fresh-context deep review, read-only. I did not edit any repo code and ran no git write command. The tray was not launched and the server service was not touched. I made scratch copies of `desktop/` under `$TMPDIR` to probe the flaky test and to run mutants, and deleted them afterwards.

## Verdict: **CHANGES-REQUIRED (test-only, narrow)**

- **The product code is approved.**
  - All 7 P17 rows are met.
  - Every first-review MAJOR and MINOR is closed.
  - The new code (process-group kill, absolute-path overrides, lost-notice baseline, menu plan) is correct.
  - I found no wrong state, no wrong or lost notice, no leak and no gate that can pass falsely.
- **One MAJOR remains, and it is in the tests only:** `core/tests/runner.rs` is flaky because of ETXTBSY.
  - I measured **25 failures in 150 runs (about 17 %)** of the runner test binary. That is worse than the PM's 1/5 and 1/12.
  - So the gate goes red falsely about once every 6 runs.
  - The fix is about 8 lines in one test file, and it was tried in a scratch copy: **0 failures in 200 runs**.
  - If the PM lands that fix (or orders it as a tiny DS3d), this package can be committed.
- Everything else is MINOR or NIT and goes to the deferred table.

## What I ran (own evidence)
- In `desktop/` with `CARGO_TARGET_DIR=desktop/target`:
  - `cargo fmt --check` passed.
  - `cargo clippy --workspace --all-targets -- -D warnings` was clean.
  - `cargo test --workspace` passed **94** tests: diff 20, fixtures 9, poller_stamp 11, runner 12, state 15, strings 10 and tray 17. The lib unit tests and the doc tests have 0.
- `ldd target/release/aristotle-tray` lists only `linux-vdso`, `libgcc_s`, `libm`, `libc` and `ld-linux`.
- The runner test binary run 150 times as it stands: **25 failed**, every one with `Io("ExecutableFileBusy")`. Six different tests were hit: `success_object_is_parsed_with_exit_code`, `error_object_and_exit_1_is_a_server_error`, `garbage_stdout_is_bad_output_without_content`, `semicolon_argument_is_passed_literally`, `timeout_kills_the_process` and `timeout_kills_grandchildren_too`.
- Scratch copy with a process-wide lock (the fix below): **0 failures in 200 runs**.
- Scratch copy that writes the script to a temp file and then renames it, with no lock: **6 failures in 100 runs**. So temp + rename does **not** fix it.
- My own mutants, run in the scratch copy with the lock, so the flake could not mask a result. All 5 were **KILLED**:

  | Mutant | Killed by |
  |---|---|
  | No stale-stamp deletion (`poller.rs:36`, `stale \|\|` removed) | 3 poller tests |
  | Baseline inheritance off (`poller.rs:51`) | both `a_failed_*_keeps_the_baseline_*` tests |
  | `child.kill()` instead of `killpg` (`runner.rs:193`) | `timeout_kills_grandchildren_too` |
  | `+meta` not stripped in `is_dev` (`snapshot.rs:32`) | `dev_versions_skip_the_check_on_either_side` |
  | Relative env override accepted (`runner.rs:155`) | `default_bin_prefers_the_env_override` |

- String parity: a script compared design.md §5 with `strings.rs` `en()`/`id()`. All 29 keys match exactly in EN and ID, including the 3 Rust rows the PM added (see "Strings" below).

## Findings

### BLOCKER
None.

### MAJOR

**MAJOR-1: `core/tests/runner.rs` fails at random with ETXTBSY. The product code is fine; the gate goes red falsely about 17 % of the time.**
- Where: `desktop/core/tests/runner.rs:19-25` (`Scratch::script`) and every test that execs a script it just wrote (`:37`, `:46`, `:58`, `:72`, `:81`, `:89`, `:112`, `:165`).
- Mechanism (confirmed):
  1. `Scratch::script` opens `fake-server` for writing with `fs::write`, then closes it.
  2. Rust opens the file `O_CLOEXEC`, but CLOEXEC only closes the fd **at exec**.
  3. libtest runs the tests in parallel threads. If another thread's `Command::spawn` forks while this thread has the file open, the child gets a copy of the write fd and holds it until that child execs.
  4. While any process holds a write fd on the inode, `execve` of that inode fails with `ETXTBSY`.
  5. `spawn_error` (`runner.rs:234-239`) then maps it to `CliError::Io("ExecutableFileBusy")`.
  - Every test writes into its own directory, so this is not a file collision. The write fd leaks across threads through fork. It is the classic fork/exec race (Go issue 22315).
- Why temp + rename does not help: ETXTBSY is per inode. The renamed file is the same inode that the other child holds a write fd on. Measured: 6 failures in 100 runs.
- **Fix (smallest robust one, proven 0 failures in 200 runs):** one process-wide lock in the test file. It is held from writing the script until the test ends, so no fork can happen while any script is open for writing:
  ```rust
  static SPAWN_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
  fn spawn_guard() -> std::sync::MutexGuard<'static, ()> {
      SPAWN_LOCK.lock().unwrap_or_else(|e| e.into_inner()) // a failed test must not poison the rest
  }
  struct Scratch(PathBuf, std::sync::MutexGuard<'static, ()>); // the guard drops after Drop removes the dir
  // Scratch::new: take `let guard = spawn_guard();` first, end with `Scratch(dir, guard)`
  // missing_binary_is_not_installed: `let _g = spawn_guard();` (it forks too, before the exec fails)
  ```
  - Cost: the 10 spawning tests run one after another. The binary takes about 2.1 s instead of about 1.1 s.
  - A finer `RwLock` version is possible: writers take `write()`, and each `run` takes `read()`. It is not worth the extra code.
  - No production change is needed.
- **Can the production runner hit ETXTBSY?**
  - Not through this mechanism: the tray and the window app never write executables.
  - The installer replaces `~/.local/bin/aristotle-server` atomically (`scripts/install.sh:108-116`: `cp` to a temp name, then `mv -f`), so the exec'd inode never has a writer.
  - The only way is a user's in-place `cp` over the binary at the moment of a poll. That gives `CliError::Io` (`runner.rs:237`), which becomes `StatusPoll::Failed` (`snapshot.rs:179`). The result is Red "Server not answering" for one poll, **silent** (`diff.rs:37-39`), and retried 5 s later. That is the right behaviour.
  - **Do not add a blind ETXTBSY retry** in `RealCli::run`. It would only hide this test bug.

### MINOR (deferred)

| # | Where | Issue | Fix |
|---|---|---|---|
| m1 | `docs/desktop/gates.sh:52-56` | `MIN_TESTS=94` is a sound floor, but it is a workspace-wide sum. A test binary that silently drops to 0 (for example the tray's 17 unit tests behind a lost `#[cfg(test)]` module) passes, as long as other binaries grew by as much. "> 0 per binary" cannot replace it as is: `unittests src/lib.rs` and the doc tests legitimately run 0. | Keep the floor and add a per-binary check for the named binaries: `diff`, `fixtures`, `poller_stamp`, `runner`, `state`, `strings` and the `aristotle_tray` unittests must each report at least 1. Pair the `Running …` line with its `test result:` line in the log. A missing `Cargo.toml` already FAILs (`gates.sh:44-45`): confirmed. |
| m2 | `docs/desktop/gates.sh:33-42` | P16 and the PM say "ldd libc-only", but the gate only denies `gtk\|webkit`. A new `libdbus-1` or `libssl` dependency would pass. | Use an allowlist: every `ldd` line must match `linux-vdso\|libc\.so\|libm\.so\|libgcc_s\|ld-linux`. |
| m3 | `core/src/runner.rs:206-209` | Normal-exit path: the child exits, but a backgrounded grandchild still holds stdout. After 2 s `run` returns `Timeout`, the group is **not** killed, and the reader thread lives until the grandchild closes the pipe. No `aristotle-server` verb does this today. | On that branch call `kill_group(&child)` before returning. The pgid stays valid while any member lives, and the leader is reaped, so there is no PID-reuse risk once the group is empty. Then join the reader as in the timeout branch. |
| m4 | `core/src/snapshot.rs:182-189` | The CLI `version --json` result is cached until a not-running to running transition. A cached `Mismatch` stays Red after the CLI binary is put back without a server restart. A changed CLI is not noticed while the server keeps running. The running-server check (`:205-213`, every poll) covers the common "installed, not restarted" case. | Re-ask `version` every N polls (for example 12, once a minute) or when `status.result.version` changes. |
| m5 | `core/src/runner.rs:234-239` and design.md §4.1 row 1 | Row 1 is "the binary cannot be run". Only `NotFound` maps to `CliMissing`. `PermissionDenied` (binary not executable) shows "Server not answering" instead of "aristotle-server not found". | Map `PermissionDenied` to `NotInstalled` as well. |
| m6 | DS3w (window app), P15 | If the window app writes the stamp and `stop` then fails (for example P12 `refused:` inside a remote terminal), the stamp stays up to 60 s and silences a real crash in that window. | DS3w brief: delete the stamp when `stop`/`restart` returns an error. |

### NIT (deferred)
- **NIT-a.** `core/src/runner.rs:203`: when `try_wait` returns `Err`, the code returns without killing or waiting for the child, so it stays a zombie. Add `kill_group` and `wait` there too.
- **NIT-b.** `core/src/poller.rs:32-38`: there is a small read/delete race. If the window app writes a fresh stamp between the `read()` (`:32`) and the `delete()` (`:38`) of a stale or consumed one, the fresh stamp is deleted. The window is microseconds. Fix: delete only if the file still holds the value that was read.
- **NIT-c.** `core/src/stamp.rs:40-44`: an unparsable stamp reads as `None`, so the poller never deletes it. It is harmless, because stops then notify. Delete it on a parse failure.
- **NIT-d.** `core/src/strings.rs:3-4` says the "added in DS3" keys "are not in design.md yet". `:60` puts "Added in DS3" on `TipNotInstalled`, which now maps to `st_notinst`. Both are stale: the PM added `NoAppTitle`, `NoAppBody` and `ADevice` to design.md. Also put the design key on each `Key` variant (the rest of first-review NIT-8).
- **NIT-e.** design.md §5: `st_notinst`, `st_nocli`, `st_noanswer` and `mm_title` are tray tooltips too, but their Surface says `UI`, while `st_outside` and `sub_hermunk` say `UI + tray tooltip`. The 3 added Rust rows use code names (`NoAppTitle`) instead of snake keys, and the "189 keys" total does not count them.
- **NIT-f.** design.md §6 says "Start server (stopped) or Stop server… (running)". The code (correctly) follows §4.4 "Which buttons are on", which goes by the service state, not the server state. Point §6 at §4.4.
- **NIT-g.** `tray/src/tray.rs:62-71`: the tray's Start stays enabled while its own `start` runs. §4.4 says "a running command → all off". It is harmless: a second `systemctl start` is a no-op.
- **NIT-h.** `core/src/diff.rs:106-115`: a fast swap from a reachable server to one that leaves out `hermes` sends "Hermes is not reachable", while the tooltip says "does not report Hermes". The current server always probes (`server/internal/app/controlcmds.go` `status()`), so only an older binary triggers it.
- **First-review NITs still open:** NIT-3 (escape `&<>` in names), NIT-5 (`non_empty_env` at `runner.rs:162` plus the inline copy at `stamp.rs:29-31`), NIT-6 (`ErrorBody` unused, `types.rs:194-197`), NIT-7 (`assess` still in `snapshot.rs`) and NIT-9 (10 s timeout on read-only queries; the first tick can take up to 40 s).

## First-review closure

| First-review finding | Status | Evidence |
|---|---|---|
| MAJOR-1 outside service / order | **Closed** | `snapshot.rs:129-150`: mismatch, then not installed, no-answer, not running, `!service_active`, then Hermes (`RunningOutsideService` at `:143`). Table cases at `tests/state.rs:66-94` include "outside + hermes down" and "unknown + absent". PM mutant killed. |
| MAJOR-2 stamp window + deletion | **Closed** | `diff.rs:60-70` `stamp_in_window` is symmetric and inclusive. `poller.rs:35-40` deletes a stale stamp on every tick, before and independent of a stop. Tests at `tests/diff.rs:200-243` and `tests/poller_stamp.rs:133-194`. Mutant M1 killed. |
| MAJOR-3 running-server version | **Closed** | `snapshot.rs:195` and `:205-213` check every poll. `combine_checks` (`:223-229`) lets a mismatch on either side win. Tests at `tests/state.rs:348-401`. PM mutant killed. |
| MINOR-1 hermes absent tooltip | **Closed** | `Reason::HermesUnknown` (`snapshot.rs:148`) maps to `TipHermesUnknown` = `sub_hermunk` (`strings.rs:148-150`, `:184`, `:214`). |
| MINOR-2 "Stop server…" | **Closed** | `strings.rs:138` and `:174`, pinned in `tests/strings.rs:490`. |
| MINOR-3 menu by service state | **Closed** | `menu.rs:36-50` and `:66-75`, after DS3c: other words show neither, `active` shows Stop… (including no-answer recovery), `inactive`/`failed` show Start, and mismatch or not installed show neither. Pair is off under mismatch (`:82`). Matches design.md §4.4. PM mutant killed. |
| MINOR-4 `-dev` skip | **Closed** | `snapshot.rs:30-34` strips `+meta`, then checks for empty, `dev` or a `-dev` suffix. `1.2.3-rc1` is still compared (`tests/state.rs:179-182`). Mutant M5 killed. |
| MINOR-5 P11 logging | **Closed** | `CliError::kind()` (`runner.rs:46-54`). The tray logs only the kind (`tray.rs:69`). No other `eprintln!` prints CLI output. |
| MINOR-6 gate holes | **Closed** (m1 and m2 are hardening) | A missing `Cargo.toml` gives `FAIL desktop-missing` (`gates.sh:44-45`). The `tests < MIN_TESTS` FAIL is at `:52-56`. |
| MINOR-7 group kill | **Closed** (m3 and NIT-a left) | `process_group(0)` (`runner.rs:174`), `killpg` SIGKILL (`:218-232`), then `wait` reaps the leader (`:194`). The reader is joined when the pipe closes within 2 s, else detached (`:197-199`). Grandchildren killed: test at `tests/runner.rs:165` and mutant M3. |
| MINOR-8 absolute overrides | **Closed** | `absolute_env_path` (`runner.rs:153-160`) is used for both overrides (`:145`, `launcher.rs:42`). `window_bin` gives `None` when there is no absolute directory or `current_exe` failed (`launcher.rs:15-21`), and the launcher then reports `Missing` (`:53-55`). Mutant M6 killed. |
| MINOR-9 lost notices | **Closed** | `baseline_for` (`poller.rs:49-56`) keeps the last good lists while the server runs. Tests at `tests/poller_stamp.rs:209-281`. Mutant M2 killed. |
| NIT-1 comment | Closed | `main.rs:68` |
| NIT-2 Open action | Closed | `notifier.rs:34-36` |
| NIT-3 markup escape | Open | deferred |
| NIT-4 `Launch::Failed` | Closed | `tray.rs:58` |
| NIT-5 `non_empty_env` ×3 | Partly closed | 2 copies left: `runner.rs:162` and `stamp.rs:29-31` |
| NIT-6 `ErrorBody` | Open | `types.rs:194-197` |
| NIT-7 `state.rs` | Open | deferred |
| NIT-8 header | Partly closed | The header points at design.md, but its text is stale (NIT-d) and there are no per-key comments. |
| NIT-9 timeouts | Open | deferred |

## P17 checklist

| P17 row | Status | Evidence |
|---|---|---|
| State order per design.md §4.1 rows 0-9 | **Met** | **Row 0:** the first tick runs before the icon exists (`main.rs:31-37`). **Row 1:** `CliMissing` (`snapshot.rs:121`). **Rows 2-9:** `assess_status` (`:129-150`), first match wins. Any other `hermes.state` word gives Amber `HermesDown`, as row 8 says. Mismatch comes first as its own Red reason (P5). |
| Absent `hermes` gives Amber with its own text | **Met** | `snapshot.rs:148` gives `TipHermesUnknown`, which is `sub_hermunk` in EN and ID. |
| `no-answer` gives Red | **Met** | `snapshot.rs:136-138`. It beats `inactive` (`tests/state.rs:96-100`). |
| Running with `service != active` gives Red "running outside the service" | **Met** | `snapshot.rs:142-144` gives `TipOutside`, which is `st_outside`. |
| "Stop server…" | **Met** | `strings.rs:138` and `:174` |
| Version compared with the CLI and with the running server | **Met** | The CLI check is at `snapshot.rs:182-189` and `:215-220`. The running server is checked every poll (`:195`, `:205-213`). Either mismatch gives Red. |
| `-dev` and `+meta` skip | **Met** | `snapshot.rs:30-34`. Covers unset, empty, `dev`, `0.0.0-dev` and `0.0.0-dev+sha` on either side. |
| Stamp counts only inside `now ± 60 s`, inclusive | **Met** | `diff.rs:66-70` |
| A stamp outside the window is ignored and deleted on every poll | **Met** | `poller.rs:35-40`. Deletion runs on every tick, including failed polls. A garbage file is not deleted (NIT-c). |

## Strings: code vs design.md §5 (script-compared, EN and ID)
- **Exact match for all 29 `Key`s:**
  - `m_open`, `m_pair`, `m_start`, `m_stop` (with "…"), `m_quit`.
  - `tip_ok`, `tip_warn`, `tip_err`.
  - The tooltips `st_notinst`, `st_nocli`, `st_noanswer`, `mm_title`, `st_outside` and `sub_hermunk`.
  - `now_t`.
  - `n_paired_t/b`, `n_term_t/b`, `n_herm_t/b`, `n_stop_t/b`, `n_d7_t/b` and `n_open`.
- **The 3 PM-added Rust rows** (`NoAppTitle`, `NoAppBody`, `ADevice`) match the code exactly. Only their naming and the total count are NITs (NIT-e).
- No design.md row with Surface Rust or tray lacks a code key.

## Specific checks from the brief
- **Process-group kill.**
  - Grandchildren are killed (test plus mutant M3).
  - The leader is reaped by `wait`, so there is no zombie on the timeout path. The `try_wait` `Err` path is NIT-a.
  - Orphaned grandchildren are reparented and reaped by init or the session subreaper.
  - The reader is joined when the pipe closes. It is detached only if a process left the group (documented at `runner.rs:195-196`), or on the normal-exit lingering-grandchild path (m3).
- **Absolute-path overrides.** Relative values are ignored with a log line that names the variable only (P11-safe). The launcher reports `Missing` when there is no usable path.
- **Lost-notice baseline.** A failed `devices list` / `terminal status` poll keeps the previous list (`poller.rs:51-54`). A failed `status` poll never replaces `last_good` (`:41-43`).
  - **A double notice is not possible:** an announced id enters the baseline on the same tick. The baseline is only ever the latest successful list or an older one.
  - A device that was removed and then re-added with the same id *during* failed polls is not announced. That is a lost notice, not a double one, and it is not reachable in practice: re-pairing mints a new id.
- **Menu plan vs design.md §4.4 "Which buttons are on".** It matches. The window app (DS3w) must follow the same rule. The tray-only difference is NIT-g.
- **Gate.** The `MIN_TESTS=94` floor is sound and should stay. Add the per-binary non-zero check (m1); a plain "> 0 per binary" would fail on the 0-test lib and doc-test targets. A missing `Cargo.toml` FAILs.

## Summary
- **BLOCKER:** 0.
- **MAJOR:** 1, test-only. It is the ETXTBSY flake in `core/tests/runner.rs`, measured at 25 failures in 150 runs. The fix is a process-wide lock, proven at 0 failures in 200 runs. Temp + rename does not work (6 failures in 100 runs).
- **MINOR:** 6, deferred.
- **NIT:** 8 new plus 5 still open from the first review.
- P17: 9 of 9 rows met. First review: every MAJOR and MINOR is closed.
- Production code: approved. Commit after the test-lock fix.
