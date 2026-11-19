# DFS + DFS2 review (deep, fresh context)

Scope: the whole uncommitted tree on top of `b611b6f` (45 modified files, 2 new source files `core/src/state.rs` and
`tray/src/starting.rs`, the docs). Read: PROJECT.md, spec P11/P15/P17/P18/P20-P23, design.md §4.1 rows 4/4b, §5, §6, tickets
DF7-DF9, the source reviews (DS3 review + re-review, DS3w, DS8b), and the DFS/DFS2 briefs, plans, reports and PM logs.

## Verdict: **APPROVED-WITH-FINDINGS** (0 BLOCKER, 0 MAJOR, 2 MINOR, 6 NIT)

Nothing blocks the commit. MINOR-1 is a missing test for the part of P23 (c) that keeps a slow machine from going red.
MINOR-2 needs a PM decision on design wording.

## What I ran (read-only on the repo)
- `cargo test --workspace` (CARGO_TARGET_DIR=desktop/target, `timeout 900`): all green. Totals match the PM gate (184 Rust).
  I did not re-run the UI suite: the PM gate `runs/DFS2/pm-gate.log` has 300/300 and mutant H3 covers row 4b.
- Reviewer mutants on a scratch copy (`$TMPDIR/dfs2-review`, with the server's `testdata/json` fixtures copied in; the
  originals restored and sha-checked by the script):

| # | Mutant | Result |
|---|---|---|
| R1 | `LogStream::start` spawns from the calling thread (no spawner thread) | KILLED by `two_starts_at_once_leave_exactly_one_stream_running` (the starter threads exit, so PDEATHSIG kills the stream) |
| R2 | no `prctl(PR_SET_PDEATHSIG)` | KILLED by `a_killed_app_does_not_leave_the_log_child` |
| R3 | `keeping_last_good` also keeps lists when the server is stopped | KILLED by `a_failed_read_keeps_the_last_good_lists_only_while_the_server_runs` |
| R4 | `delete_if` deletes unconditionally | KILLED by `delete_if_leaves_a_stamp_written_after_the_read` |
| R5 | `RealCli::run_within` ignores the runner's own shorter timeout (`limit` instead of `limit.min(self.timeout)`) | SURVIVED (NIT-6) |
| R6 | `try_wait` `Err` path goes back to "no kill, no wait" | SURVIVED (cannot be tested, as the build report says) |
| R7 | tray Start item always `enabled: true` | SURVIVED (NIT-5) |
| R8 | tray start thread `mem::forget(token)` (Start disabled for good) | SURVIVED (NIT-5) |
| R9 | an unparsable stamp is not deleted | KILLED by `an_unparsable_stamp_is_deleted_by_the_next_tick` |
| R10 | name lookup drops the `prev.devices` fallback | KILLED by both DF9-MINOR-1 poller tests |
| R11 | `Snapshotter::poll` runs `status` through `query_read` (3 s) | **SURVIVED** (`--workspace --no-fail-fast`): MINOR-1 |

No test or mutant process was left running (`pgrep` empty). The repo tree is unchanged apart from this file.

## Check 1: process safety
- **Spawner thread** (`app/src/logs.rs:98-116`): it is started lazily on the first `start` and never exits, because the
  `Sender` lives in a `static OnceLock`. `PR_SET_PDEATHSIG` is per thread, so tying it to this thread is right. R1 proves
  that spawning from a short-lived thread would kill the stream. `stop`, the detached reaper (`:146-172`) and `killpg`
  are unchanged, and the child is still reaped through `Child` from the thread that holds it (`waitpid` works from any
  thread). The only new thread is the one spawner; no zombie is added.
- If the spawner thread dies, `SIGKILL` goes to every running log child, and every later `start` returns `io`
  ("log spawner is gone", `:112-115`) until the app restarts. It cannot realistically panic: `spawn_logs` returns `Result`,
  and `reply.send` ignores errors. If `thread::spawn` fails inside `get_or_init` (`:102`), the `OnceLock` stays empty and
  the panic goes to the IPC caller (NIT-2).
- **Fork-to-prctl race**: if the app dies between `fork` and `prctl` (`:129`), the child never gets the signal. That is
  the standard PDEATHSIG gap (NIT-1). Backstop: the orphan dies on its next write to the closed pipe. A Go-side journalctl
  grandchild has the same limit, which the report states.
- **Runner `try_wait` Err** (`core/src/runner.rs:233-238`): kill the group, then `wait`. That is correct when the child
  is unreaped. The comment "Not reaped yet" is optimistic: in practice `waitpid(WNOHANG)` only fails with `ECHILD`, which
  means the child is already gone (NIT-3). The risk is the same pid-reuse case P23 (d) accepted.
- **Stamp compare-and-delete** (`core/src/stamp.rs:63-68`, `core/src/poller.rs:35-40`): only the value that was read is
  deleted. The poller now deletes only when it read a stamp, which matches the old `stale || saw_stop` logic. Seconds
  granularity cannot cause an ABA in practice: a consumed stamp is at least one poll (5 s) old. But `delete_if` is
  read-then-`remove_file`, not atomic. A window-app `rename` between those two syscalls is still lost. The window is
  microseconds, the same size as the one in DS3 NIT-b, so the doc comment at `:61-62` overclaims (NIT-4). Impact: one
  false "stopped unexpectedly" notice. The `read()` side effect (`:39-46`) is safe because `write` is tmp+rename, so the
  tray never sees half a number.

## Check 2: `Snapshot::keeping_last_good`
- One implementation (`core/src/snapshot.rs:76-87`), used by `Poller::tick` (`poller.rs:43`) and `Backend::refresh`
  (`app/src/backend.rs:78-86`). The Devices `useRef` is gone (`DevicesView.tsx:21`).
- Stale data is never shown as fresh after a stop. It keeps nothing unless *this* snapshot is `running`, and a stopped
  answer replaces last_good with empty lists. So a later running poll whose reads fail has nothing old to fall back on.
  R3 and `backend::tests::a_stopped_server_has_no_grants_not_old_ones` cover this.
- P20/P22 diff semantics are unchanged: `diff` still compares the raw snapshot (`None` = silent) against the kept
  baseline, so a later good read notifies once. The removed `baseline_for` was identical to the new method; mutant F1 in
  the PM log kills it. The new name fallback (`diff.rs:58-66`) only adds a name, never a notice.
- Remaining case (not a finding): after a `Failed` (no-answer) gap, the window still shows the pre-gap lists until a read
  succeeds. That is design.md 4.2 as written.

## Check 3: P23 (c) timeouts
- Only `version`, `devices list` and `terminal status` use `query_read` (`snapshot.rs:194,211,217`; `app/src/terminal.rs:10`).
  `status` (`snapshot.rs:134`) and every write (`service.rs:56`, `devices.rs:11`, `pairing.rs:19`, `terminal.rs:31`,
  `tray.rs:74`) keep 10 s (grep).
- These reads cannot turn anything red:
  - `assess` (`core/src/state.rs:36-67`) uses only `status` and `VersionCheck::Mismatch`, and a mismatch needs an
    `Ok` version reply. A timed-out version read leaves `self.version` as it was (`snapshot.rs:193-198`).
  - A timed-out devices or terminal read is `None`, filled from last good.
  - The Terminal view's own read keeps its last data on error (`useTerminal.ts:69-80`).
- Worst-case first tick drops from 40 s to 19 s.
- The gap is MINOR-1: nothing pins `status` to the 10 s limit through the snapshotter.

## Check 4: P23 (a)/(b)
- Row 4b: `world.ts:43-46,57,75`. It reaches the Status headline (`StatusView.tsx:36-37`), the Service header
  (`ServiceView.tsx:42-43`) and the footer (`StatusFooter.tsx` via `footerState`, `world.ts:85-88`). `SetupState` passes
  `subParams` too, but only renders rows 1/2. `NeedsServer` shows no state headline. The tray is unchanged: `assess` still
  gives `ServerStopped`/red with the same tooltip.
- EN/ID `st_changing` / `sub_changing` (`en.json`/`id.json:162-163`) match design.md §5 character for character.
- (b): `hermes_went_down` requires `after.hermes_reported()` (`diff.rs:152-161`, `types.rs:97-100`). The tests cover
  both directions (`tests/diff.rs` `hermes_going_absent_is_silent`, `hermes_going_not_running_notifies`), and PM mutant
  H2 kills it.

## Check 5: tray Start (`tray/src/starting.rs`, `tray/src/tray.rs:67-81,100`)
- The token is released on every path:
  - Ok and Err: explicit `drop` at `:79`.
  - Panic in the thread: unwinding drops it (no `panic = "abort"` profile).
  - `thread::spawn` failing: the closure, and with it the token, is dropped before the panic.
- ksni re-reads the menu after a click (ksni 0.3.7 `service.rs:562-563`), so Start greys out at once. Nothing pokes ksni
  when the thread ends, but `poll_forever` calls `handle.update` every 5 s (`main.rs:57-60`), so the item comes back within
  one poll. That is acceptable.
- The wiring in `tray.rs` is untested (R7/R8 survive): NIT-5.

## Check 6: DF items
Spot-checked 12 DONE items against their source NITs (DF7-a, b, c, g, NIT-3; DF8-4, 5, 6, 8; DF9 MINOR-1, `{ok:false}`,
NIT-1, NIT-5): all done as asked, each with a test that fails when reverted. The exceptions are DF7-a (untestable) and
DF7-g (see NIT-5).

The "not doing" items all landed somewhere:
- DF7-e and DF7-f: PM design.md edits in §5 totals and §6.
- DF7-h, NIT-9, EXTRA and DF8-9: P23 (b), (c), (a) and (d).
- DF8-1: DL4.
- DS8b NIT-3: §6 "none on the D7 and grant notices".

No regression: full Rust suite green, PM gate green. Code shape follows PROJECT.md: `state.rs` is a move only, small
named functions, no new duplication (`non_empty_env` is now single).

## Findings

### MINOR-1: "`status` keeps 10 s" (P23 c) has no test
- `core/src/snapshot.rs:134`. Mutant R11 (`query` → `query_read` for `status`) survives the whole workspace
  (`--no-fail-fast`). `a_hung_status_keeps_the_runner_limit` (`core/tests/runner.rs:307-314`) calls `runner::query`
  directly, so it tests the runner, not the snapshotter. This line is what stops a slow but healthy machine flapping to red
  "no answer" (the server's `status` includes a 2 s Hermes probe), and nothing protects it.
- **Fix:** a Backend/FakeCli test that, after one running poll, asserts
  `cli.limits() == [3 s; 3]` (version, devices, terminal: `status` records none). Alternatively, a `core/tests/runner.rs`
  test whose fake `status` sleeps 4 s, then `Snapshotter::poll(&cli(&bin, 10))` must give `StatusPoll::Answered`.

### MINOR-2 (PM decision): row 4b also catches `unknown` and `active`
- `ui/src/state/world.ts:45` sends every service word except `inactive`, `failed` and `""` to 4b. That follows P23 and
  design.md:241 literally ("any other `service` word").
- But the server prints `unknown` when systemctl printed nothing (`server/internal/service/service.go:266-273`, e.g. no
  user bus). With `server == not-running` the window then says "Server is changing state / systemd reports the service as
  unknown." `unknown` is not a transition.
- `active` + `not-running` (the server not answering yet after a start) fits 4b fine.
- **Fix:** PM decides whether `unknown` belongs in row 4 or keeps 4b, then states it in design.md §4.1. If it belongs in
  row 4, add it to `isChanging`'s exclusions plus one `world.test.ts` row.

### NIT-1: PDEATHSIG set-up race not closed
`app/src/logs.rs:128-134`. Capture `std::process::id()` before `spawn`. In `pre_exec`, after `prctl`, return an error
(or `_exit`) when `getppid()` differs, so a parent that died between fork and prctl does not leave the child.

### NIT-2: the spawner panics instead of failing the IPC call when the OS refuses a thread
`app/src/logs.rs:102`. Use `thread::Builder::new().name("log-spawner").spawn(...)` and turn the error into the `io`
`IpcError`. The thread name also helps when debugging. Optional: `mpsc::Sender` is `Sync`, so the `Mutex` at `:99` is not
needed.

### NIT-3: misleading comment on the `try_wait` error path
`core/src/runner.rs:234`: "Not reaped yet". The only realistic `try_wait` error is `ECHILD` (already reaped elsewhere).
Then `kill_group` aims at a pgid that is gone, the case P23 (d) accepts, and `wait` returns at once. Reword it as
"best effort: if the child is still ours, kill and reap it".

### NIT-4: `delete_if` is narrower than its doc comment says
- `core/src/stamp.rs:61-68`: re-read, then `remove_file`. A stamp renamed in between is still deleted, so the race window
  is about the same size as before (DS3 NIT-b).
- **Fix:** soften the comment ("a stamp written before this re-read"). Or, for a real compare-and-delete: `rename` the
  stamp to a private claim file, compare, and if the value differs `hard_link` it back (which fails when a newer stamp
  exists), then unlink the claim.

### NIT-5: the tray's Start guard is only unit-tested
`tray/src/tray.rs:69-80,100`: R7 (always enabled) and R8 (token leaked: Start disabled for good) survive. Add a tray test:
use a `ServerCli` fake whose `run` blocks on a channel, call `start_server`, and assert `item(Entry::Start)` is disabled and
a second `start_server` makes no second call. Release the fake and assert the item is enabled again.

### NIT-6: smaller test gaps
- R5 (`core/src/runner.rs:192`, `limit.min(self.timeout)`) survives. A one-line test: a hanging read through a 1 s
  `RealCli` gives up in under 3 s.
- `a_hung_*` (`core/tests/runner.rs:279,288,298`) check only `took < 6 s`, so a read limit between 3 and 6 s passes in
  core. The app test `terminal.rs:68-72` pins the constant, so this is cosmetic.
- `logs::tests::orphan_helper` (`app/src/logs.rs:277-287`) is a test that returns at once, which inflates the count floor
  by 1. Mark it `#[ignore]` and start it with `--ignored --exact`.

## Notes (no action)
- Markup escaping (`core/src/strings.rs:262-266`) is right for KDE, which parses body markup. A server without
  `body-markup` may show `&amp;` literally. The user is on KDE.
- `sub_stopped` says "The service is inactive" also for `failed` (row 4). That is design.md text, outside this sweep.
- P23 (b): reachable → absent → not-running never notifies, because absent is not "reachable". This needs two binary swaps
  with an older server, so it is accepted.
