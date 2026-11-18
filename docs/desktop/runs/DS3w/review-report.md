# DS3w + DSI review report (deep, fresh context)

Scope: the uncommitted tree under `desktop/**`, `docs/desktop/gates.sh`, `docs/design/desktop/tray-icons*`, as `git status` showed it
on 2026-11-18. Read-only review: no code edited, no git writes, app and tray not launched, server service not touched.

## Verdict: **APPROVED-WITH-FINDINGS**

No BLOCKER. There is one MAJOR (PM's M2, confirmed and reproduced). **Fix MAJOR-1 and MINOR-1 before DS4 is dispatched**, because
DS4's Logs view is the first code that drives `LogStream`. MINOR-2 and MINOR-3 are small and fit the same fix round. P4, P11,
P14/P15/DF6, DF1-DF5, the strings, the fonts and DSI all check out (see "Verified").

## What I ran
- `cargo test -p aristotle-desktop -p aristotle-tray` (CARGO_TARGET_DIR=desktop/target): 30 + 0 + 6 + 19 passed.
- `npx vitest run` in `desktop/ui`: 4 files, 46 tests passed.
- **M2 reproduction, outside the repo.** I put a scratch crate in `$TMPDIR/m2probe` holding a copy of `app/src/logs.rs` with
  `killpg(pgid, 0)`. Under `timeout 45`, `stop_kills_the_child_and_its_group_and_reaps_it` printed `running 1 test` and never
  finished; `timeout` killed it at 45 s. All leftover processes were cleaned up.
- **`make_icons.py` reproducibility.** I re-ran it in a scratch dir. All 6 SVGs and 12 PNGs came out byte-identical to
  `desktop/tray/assets/**`, and identical to the copies in `docs/design/desktop/tray-icons/`.
- **String check.** A script compared every design.md UI string row (key, en, id) with `en.json` / `id.json`: 172 keys,
  0 missing, 0 extra, 0 text differences.
- `desktop/app/src/logs.rs:87` reads `killpg(pgid, SIGKILL);`, so the PM's restore is confirmed.

## Findings

### MAJOR-1: `LogStream::stop()` waits without a bound. A kill that fails hangs the test, the gate and the window's close path
- `desktop/app/src/logs.rs:78-92`: after `killpg`, `stop()` runs `running.child.wait()` (`:90`) and `running.reader.join()` (`:91`)
  with no deadline.
- The test `logs.rs:203-209` measures `elapsed()` only after `stop()` returns. If the group is not killed, the test hangs instead of
  failing. I reproduced this above. `docs/desktop/gates.sh:90` (`cargo test --workspace`) has no timeout, so the whole gate hangs.
- In production, `stop()` runs:
  - on the main event-loop thread at `WindowEvent::Destroyed` (`desktop/app/src/main.rs:44-46`);
  - on `RunEvent::Exit` (`main.rs:56-59`);
  - at the start of every `start()` (`logs.rs:53`).

  If the child is not reaped (kill refused, a D-state child), or a process that left the group keeps the stdout pipe open (then
  `reader.join()` blocks), closing the window freezes the app and it never exits. That breaks D18 "exits when its window closes".
- **Fix:**
  1. In `stop()`, after `killpg`, poll `child.try_wait()` for at most about 2 s in 10 ms steps.
  2. If the child is still alive, call `child.kill()`.
  3. Join the reader only if `reader.is_finished()` within the same deadline.
  4. Otherwise hand `child` and `reader` to a detached reaper thread (`thread::spawn(move || { let _ = child.wait(); let _ = reader.join(); })`)
     and return.

  For the test, run `stream.stop()` on a spawned thread that sends on a channel, then
  `rx.recv_timeout(Duration::from_secs(3)).expect("stop returned")`, so the mutant fails instead of hanging.

  For the gate, wrap the step as `timeout 900 cargo test --workspace` (gates.sh:90) so any future hang becomes `FAIL desktop-test (124)`.
  Re-run PM mutant M2 afterwards; it should be KILLED.

### MINOR-1: two `logs_start` calls at the same time leak a `logs -f` child that keeps streaming
- `logs.rs:53`: `self.stop()` runs and releases the lock, then the code spawns, then `logs.rs:69` assigns `*lock(&self.running) = Some(..)`.
- Two `logs_start` invocations run on separate blocking threads (`ipc.rs:150-168`). Both see nothing to stop, both spawn, and the
  second assignment drops the first `Running`.
- Dropping `Child` does not kill it, and dropping the `JoinHandle` detaches the reader. The result is an orphaned
  `aristotle-server logs -f` (journalctl) that lives until the app exits, plus a second reader whose `stopped` flag is never set.
  That reader keeps emitting `logs-lines`, so the UI shows every line twice.
- Today this only takes a double-invoke, but DS4's effect will call `logs_start` on mount and remount.
- **Fix:** swap atomically, e.g. `let old = lock(&self.running).replace(new); if let Some(old) = old { stop_running(old) }`.
  Alternatively, hold one start mutex across stop, spawn and store. Add a test with two `start()` calls on two threads, then check
  that exactly one pid is alive.

### MINOR-2: gate floor not raised, and the tray's tests are not in the per-binary check
- `docs/desktop/gates.sh:17` still says `MIN_TESTS=94`; the HEAD version has the same value. The brief said "raise it to the new
  total", and the build report says "`MIN_TESTS` raised". The total is now 135, so 41 tests could disappear and the count step
  would still PASS.
- `gates.sh:52-56` (`REQUIRED_TEST_BINARIES`) leaves out `unittests src/main.rs|aristotle_tray` (19 tests: the DSI icon tests plus
  the tray's menu/notifier tests). If that binary lost its tests, DF1 would not notice.
- **Fix:** set `MIN_TESTS=135` and add `"unittests src/main.rs|aristotle_tray"`. Optionally, add a floor for the Vitest count
  (`Tests  N passed` in `gate-ui-test.log`).

### MINOR-3: a `stop`/`restart` that times out deletes the stamp although the outcome is unknown
- `desktop/app/src/service.rs:46-50` deletes the stamp on any `Err`, including `CliError::Timeout`. The test
  `a_stop_that_cannot_run_the_binary_deletes_the_stamp` (`service.rs:128-134`) locks this behaviour in.
- The CLI timeout is 10 s (`desktop/core/src/runner.rs:16`). On timeout the runner kills the `systemctl` client, but the systemd
  stop job keeps running.
- If the server takes more than 10 s to shut down, three things happen:
  - the window reports a failure;
  - the stamp is gone;
  - when the stop completes, the tray announces "stopped unexpectedly" for a stop the app issued. That is the false notice P15 is
    meant to prevent.
- **Fix:** delete the stamp only on a definite failure (`Server`, `BadOutput`, `NotInstalled`, `ok:false`) and keep it on
  `Timeout`; P17's 60 s window makes it expire anyway. Optionally, give the service verbs a longer timeout than the read polls.
  Rename and split the test to match.

### MINOR-4: shared pieces that DS4-DS6 all need are missing, so the parallel tickets will collide or duplicate
DS3w did ship the shared components: `Banner`, `Button`/`ActionButton`, `ConfirmDialog`/`WarnBox`, `CodeBlock`, `CopyButton`,
`EmptyState`, `Progress`, `StateLight`, `Switch`, `Tag`, `ViewHeader` and `SetupState`. It also shipped the hooks `useSnapshot`,
`useActionGuard` and `useStartIntent`, the typed `src/ipc/`, and all 172 strings, so no view ticket has to edit the i18n files.

Three pieces that design.md §4.2 defines for more than one ticket do not exist (grep for `needs_title|act_failed|working_` in
`ui/src` finds nothing outside the JSON files):
- the **"needs the running server"** empty state, used by Pair and Devices (DS5) and Terminal (DS6). It carries a Start button with
  the installed / mismatch / `service != active` rules.
- the **"command failed"** banner (`act_failed_t`/`_b` plus a `CodeBlock` with `error: <text>`), used by DS4, DS5 and DS6.
- a small **action runner** that runs a command, tracks the working state, maps `IpcError` to that banner and clears it on the next
  action or view change.

**Fix:** add `components/NeedsServer.tsx`, `components/ActionFailed.tsx` and `state/useAction.ts` now, with one test each. Each view
ticket then only edits `views/<view>/`.

### MINOR-5: the sidebar runs an extra `terminal status` CLI process on every poll
- `desktop/ui/src/components/Sidebar.tsx:28-46` re-runs `client.terminalStatus()` every time `snapshot` changes: every 5 s event,
  plus every `publish` after an action.
- Core already runs `terminal status` on each poll (`desktop/core/src/snapshot.rs:264-268`) but keeps only `.open` and drops
  `enabled`. So each tick costs one more process than needed, and this sits next to the tray's own poller.
- **Fix:** keep `enabled` in `Snapshot` (for example `terminal: Option<TerminalStatus>`, or `terminal_enabled: Option<bool>`) and pass
  it through `SnapshotDto` (`desktop/app/src/dto.rs:36-45`). Then delete `useTerminalEnabled`. `terminal_status` can stay as a
  read-only command for DS6's explicit refresh.

### NITs
1. **The UI re-implements the design.md §4.1 table.** `desktop/ui/src/state/world.ts:40-55` derives the state itself and ignores
   `snapshot.assessment` from core (`dto.rs:74-91`). The two tables can drift. On a version mismatch they already differ: core gives
   Red / `VersionMismatch` (`core/src/snapshot.rs:130-131`), so the tray is red, while the window shows row 6 (green light) plus the
   banner (`world.test.ts:45-49`). The window follows design.md:249, which makes mismatch a separate flag, so this is a design
   question for the PM rather than a bug: should the tray go red on a mismatch while the window footer is green? Long term, let core's
   `Reason` split Hermes not-running from Hermes error, and map `assessment.reason` to the row in TS.
2. **A hand-off that arrives while the first window is still loading is lost.** `main.rs:20-23` only emits `start-intent`, and the
   listener in `ui/src/state/intent.tsx:47` is registered asynchronously. Example: a double click on "Pair a phone…" while the window
   is starting. Fix: the hand-off also stores the intent in `PendingIntent`, and the UI calls `start_intent` after `listen` resolves.
3. **Snapshot events can arrive out of order.** `ipc::publish` (`ipc.rs:44-48`) runs from the poller and from action threads at the
   same time, so an older snapshot can be emitted after a newer one. The next tick, 5 s later, corrects it. Fix: emit while holding
   the snapshotter lock, or add a sequence number.
4. **The logs child is orphaned if the app crashes or is SIGKILLed.** The child runs in its own process group and has no
   parent-death signal. If you add `PR_SET_PDEATHSIG`, note that it fires when the spawning *thread* exits, and that thread is a
   blocking-pool thread, so spawn the child from a dedicated long-lived thread.
5. **The "confirms once" test does not exercise the double-confirm guard.** In `components.test.tsx:186-197` the harness unmounts
   the dialog on the first confirm, so the guard at `ConfirmDialog.tsx:34,56-60` is never hit. Add a harness that keeps the dialog
   mounted and double-clicks.
6. **The tray decodes 4 PNGs on every `icon_pixmap` call** (`desktop/tray/src/icons.rs:76-78`). It is cheap, but a
   `OnceLock<[Vec<Icon>; 3]>` would do it once.
7. **The single-instance plugin is not pinned.** `desktop/app/Cargo.toml:26` has `tauri-plugin-single-instance = "2"`, unlike the
   P16 pins; the lockfile has 2.5.5. Pin `=2.5.5`.
8. **The UI never sees a newer server's `server` word.** `ServerState::Other` serializes as `"Other"` (`core/src/types.rs:28-29`).
   Behaviour is correct (treated as not running), but the comment at `ui/src/ipc/types.ts:39` and the test case "a word a newer
   server adds" (`world.test.ts:27`) describe input the UI never receives.
9. **`kill_group` runs after the child is reaped** (`core/src/runner.rs:206`). If no grandchild is left, the pgid is free, so in
   theory a reused pid could be signalled. Linux pid allocation makes this practically impossible; noted only for completeness.

## Verified (no finding)
- **P4 security**
  - `capabilities/default.json` holds exactly the 12 `allow-<command>` entries plus `core:event:allow-listen/unlisten`. It applies
    to window `main` only, and it is the only capability file.
  - `build.rs` registers `AppManifest::commands(COMMANDS)`, so app commands are denied unless allowed.
  - CSP (`tauri.conf.json:23`) has `default-src 'none'`, no `unsafe-*` and no remote sources except Tauri's `http://ipc.localhost`;
    `img-src data:` is there for the QR. `withGlobalTauri: false`.
  - No shell/fs/http/opener/notification plugin in `app/Cargo.toml`, `Cargo.lock` (only `tauri-plugin-single-instance`) or
    `ui/package.json`; ESLint forbids `@tauri-apps/*` outside `src/ipc`.
  - The two extra commands are read-only. `start_intent` takes a one-shot enum. `terminal_status` is a read, deliberately unguarded so
    it works under a mismatch.
  - `plain_arg` refuses ids and names that start with `-`, and argv is fixed with no shell.
  - Tests `app/tests/capabilities.rs` parse the files; PM mutant M3 was killed.
- **P11:** `pair` goes straight from the CLI to the reply (`pairing.rs:11-20`). It is not stored in `Backend`, not emitted as an
  event, and `Pair`'s `Debug` hides the code and the payload (`core/src/types.rs:175-183`). The only `eprintln!` in app/core prints an
  env var name (`runner.rs:158`). No `console.*` or storage in `ui/src`. Log lines are not logged.
- **D18:** there is no `prevent_exit` and no hide-to-tray, so closing the last window ends the process. Logs are stopped on
  `Destroyed` and on `Exit`. The poller thread lives exactly as long as the process. The single-instance callback focuses the
  window and emits the parsed intent (`main.rs:20-23`). The tray passes `--pair` and `--confirm stop` as separate tokens
  (`tray/src/tray.rs:86,96`), matching `parse_args`.
- **P14/P15/DF6:** the stamp is written before `stop`/`restart` and deleted on failure (subject to MINOR-3). `start` never writes
  it. `guard_action` refuses on a mismatch, not-installed or missing CLI before any stamp or command (`backend.rs:83-100`). Pair,
  revoke and terminal writes are guarded too; reads are not. The UI's `ActionButton` disables actions under a mismatch as a second
  layer.
- **DF1-DF5**
  - DF1: per-binary check present (gaps in MINOR-2).
  - DF2: the `ldd` allowlist is correct, and the PM gate log shows only vdso, libgcc_s, libm, libc and ld-linux.
  - DF3: the group is killed and the reader joined after a normal exit.
  - DF4: version re-asked every 12 answered polls or when `status.result.version` changes.
  - DF5: `PermissionDenied` maps to `NotInstalled`.
  - Each has a core test with RED evidence in `red-core.txt`.
- **Gate:** each npm step is its own `run_step` and fails on a non-zero exit; `ui-build` runs before cargo (the app embeds
  `ui/dist`); `tauri build --no-bundle` runs before the `-x` check on the app binary. The steps cannot pass falsely, apart from the
  floors in MINOR-2 and the missing timeout in MAJOR-1.
- **UI vs design.md**
  - Tokens are a verbatim copy, with dark mode via `prefers-color-scheme` (P7, as the brief asked).
  - Fonts are local `@font-face` with `font-display:block`. `Literata-variable.ttf` is 955,132 B (a sane size for a variable roman
    TTF) and has `OFL-Literata.txt` (2017 Literata Project Authors) beside it, plus the Atkinson `OFL.txt`.
  - Shell 2 has the grouped nav, `aria-current`, counts only while running, and the footer (`role=status`, shape + word,
    `<version> · Hermes <word>`).
  - The mismatch banner is `role=alert`. Not-installed shows `./install.sh` with no install button; the no-CLI full state is present.
  - `ConfirmDialog` focuses Cancel, closes on Esc and scrim click, traps Tab, returns focus to the opener, and is an `alertdialog`
    with `aria-modal`.
  - EN/ID parity is exact against design.md, and covered by tests.
  - The IPC client equals `command_list.rs`; the test parses the Rust file.
- **DSI**
  - RGBA→ARGB32 in network order is `[a, r, g, b]` (`icons.rs:66`), which is correct.
  - The DS3w worker's `as_chunks::<4>().0` (stable since 1.88; rustc here is 1.100) behaves exactly like `chunks_exact(4)` for
    length-multiple-of-4 buffers. The buffer length is asserted by `every_state_serves_every_declared_size_with_matching_data`.
  - The tray stays libc-only (`png` is pure Rust, `default-features = false`).
  - `make_icons.py` is byte-reproducible with the installed `rsvg-convert`.
  - The preview sheet has no remote URLs.
- **Code shape:** one module per concern, `ipc.rs` holds no logic, and the files are small (largest: `logs.rs` at 274 lines
  including tests).
- **App tests without recorded RED:** most would fail if the guarded code broke. M1 and M3 were killed, and `guard_*`, `plain_arg`,
  `each_command_maps_to_its_cli_call` and the capability tests assert exact behaviour. The weak spots are the logs `stop` test
  (MAJOR-1) and the dialog's once-guard (NIT 5).

## User live checks still needed
1. Close the window by its title-bar button and check `pgrep -a aristotle-desktop` is empty. Once DS4 exists, repeat with the Logs
   view streaming: no `aristotle-server logs` child should remain.
2. With the window open, use tray "Pair a phone…" and "Stop server…": the window should come to the front and switch to Pair /
   Service. On Wayland, check whether the window is actually raised or only flagged.
3. Language: with `LANG=id_ID.UTF-8`, the window (`navigator.language` under WebKitGTK) and the tray/notifications should both be in
   Indonesian.
4. Tray icons on the real panel at 16 and 22 px, on light and dark Breeze: can you tell the badge shapes apart? Also the real left
   click (D17), which has never been run.
5. The window in light and dark: Literata titles, Atkinson body, footer state.
