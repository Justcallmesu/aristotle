# DS3b fix brief — DS3 review round (fix round 1 of 2)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Input: the review
`docs/desktop/runs/DS3/review-report.md` (read in full: file:line + fixes), spec `docs/desktop/spec.md` **P17** (binding),
P5, P11, P14, P15, and `docs/desktop/design.md` §4.1 (status table rows 0-9) and its string table. DS3's code is uncommitted in
`desktop/**`; you continue on it.

## Fix (every item gets a test that fails on the current code; see it RED first against a scratch copy, never git)
1. **MAJOR P17 state order** (`core/src/snapshot.rs:118-140`): follow design.md §4.1 rows 0-9 exactly, in order: CLI missing ->
   not installed -> no-answer -> not-running -> running but `service != "active"` (new reason `RunningOutsideService`, Red) ->
   Hermes reachable (Green) / not-running or error (Amber, `HermesDown`) / absent (Amber, new reason `HermesUnknown`). Version
   mismatch stays its own Red reason first. Table test covering every row incl. "outside the service + Hermes down".
2. **MAJOR P17 stamp window** (`core/src/diff.rs:60-67`, `core/src/poller.rs:30-44`): fresh only while
   `now - 60 s <= stamp <= now + 60 s`; the poller deletes a stamp that is outside the window on every poll (not only after a
   stop), so a stamp left by a fast restart cannot silence a later crash. Tests: future +61 s ignored, old 61 s ignored + deleted,
   restart-without-observed-stop leaves no stamp after 60 s.
3. **MAJOR P5/P17 version** (`core/src/snapshot.rs:192-196`): mismatch when the build version differs from `version --json`
   OR from `status.result.version` (when present). Dev-build skip covers unset, empty, `dev`, and any version ending in `-dev`
   (the server's dev build is `0.0.0-dev`; read `scripts/build-server.sh` / `server/internal/version` to confirm the format).
4. **Strings**: tray label "Stop server…" (EN) and the ID equivalent with "…"; add `st_outside`, `sub_hermunk` (tooltip text
   from design.md) and any key the review lists as missing (`now_t` if the tray uses it); align the not-installed and
   version-mismatch tooltip wording with design.md (design.md wins); keep the code-only keys (`NoApp*`, `ADevice`) and report
   their texts so the PM adds them to design.md.
5. **Menu by service state** (MINOR): which of Start / Stop… shows follows `service` (active -> Stop…, inactive/failed -> Start,
   anything else or no-answer with service active -> Stop… for recovery, as design.md §4.1 "Which buttons are on" says for the
   window); on version mismatch or not installed neither Start nor Stop… (Open stays). Pure `menu_plan` tests.
6. **P11** (`tray/src/tray.rs:71-72`): never log CLI error text or stdout; log a fixed reason code only.
7. **Gate** (`docs/desktop/gates.sh:42-43,49-52`): missing `desktop/Cargo.toml` = FAIL, not SKIP; 0 tests run = FAIL.
8. **Runner timeout** (`core/src/runner.rs:166-169`): spawn the child in its own process group and kill the group on timeout;
   join or detach the reader thread without leaking it. Test with a fake binary that forks a sleeping child.
9. **Env overrides**: `ARISTOTLE_SERVER_BIN` / `ARISTOTLE_DESKTOP_BIN` must be absolute paths, else ignored (with a fixed log
   reason). Test.
10. **Lost notices** (MINOR): a failed `devices list` / `terminal status` poll keeps the previous list as the baseline for the next
    diff (it must not reset it), so a device paired during the failed poll still notifies once. Test.
11. NITs from the review: fix those that are one-liners in files you already touch; list the rest in the report.

## Do NOT change
Anything in `server/**` or `docs/protocol.md` (the PM fixes the §22 nit). The D-rows in spec.md.

## Files you may create/edit
`desktop/**` (except `desktop/ui/**`, `desktop/app/**`), `docs/desktop/gates.sh`.

## Verify
`bash docs/desktop/gates.sh docs/desktop/runs/DS3b/self-gate` (all PASS) — report the summary file verbatim.

## Hard rules
Never `git add/commit/push/checkout/restore/stash/reset`. No system installs. Do not launch the tray on the desktop this round.
Never run any `aristotle-server` command against the real service. No skill/memory edits.

## Report
`docs/desktop/runs/DS3b/build-report.md`: per item: change (file:line), test name, RED evidence; gate summary; code-only strings.
This is fix round 1 of 2.
