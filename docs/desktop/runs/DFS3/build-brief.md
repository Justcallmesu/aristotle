# DFS3 brief — DFS2 review findings (last cleanup before DS7)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Input: `docs/desktop/runs/DFS2/review-report.md` (read in
full). Spec P23 (amended: `unknown` is row 4, not 4b), design.md §4.1 rows 4/4b.

1. **MINOR-1**: a test proving the poll's `status` read keeps the runner's 10 s limit while the three reads use 3 s (FakeCli
   recording limits, or a fake `status` sleeping 4 s that still counts as answered). Must fail if `core/src/snapshot.rs` `status`
   used the 3 s limit.
2. **MINOR-2**: `unknown` maps to row 4 (`st_stopped`), not 4b (`desktop/ui/src/state/world.ts`). Test.
3. NIT-1 `getppid()` check after `prctl` in the log child setup (`app/src/logs.rs`); NIT-2 spawner thread via `thread::Builder`,
   failure = `io` error, not a panic; NIT-3 runner comment; NIT-4 stamp `delete_if` comment states the remaining race honestly (no
   rename/hard_link rework); NIT-5 tray tests: Start menu item disabled while a start runs, token released after success and error;
   NIT-6 runner `limit.min(self.timeout)` test, hung-read tests assert the read was empty AND took >= ~3 s, and `orphan_helper` not
   counted as a test (make it a non-test helper binary or `#[ignore]` with the gate's count adjusted).

Behaviour changes with a test seen failing first (scratch copy or reverted hunk, never git).
Files: `desktop/**` (not target/node_modules), `docs/desktop/gates.sh` (floors only).
Verify: `timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DFS3/self-gate` all PASS; paste the summary verbatim.
Hard rules: never git write commands; no installs/new deps; no app/tray launch against the real server; never run aristotle-server
against the real service; no skill/memory edits; kill every process you start.
Report: `docs/desktop/runs/DFS3/build-report.md`.
