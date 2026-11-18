# DS3 review brief (deep, fresh context)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/desktop/spec.md` (D4, D5, D17-D19, P1-P17;
**P17 was added by the PM after the build** and is binding for the fix round), the build brief
`docs/desktop/runs/DS3/build-brief.md`, the report `docs/desktop/runs/DS3/build-report.md`, `docs/protocol.md` §22, and
`docs/desktop/design.md` (written in parallel by another worker; its report `docs/desktop/runs/DSDc/build-report.md` lists 11
board-vs-spec conflicts).

## Scope
1. The uncommitted `desktop/**` and `docs/desktop/gates.sh` (DS3).
2. **Re-check the two server diffs that had no review of their own:** `git diff 167aa4a aa9c5a7 -- server docs/protocol.md`
   (DS1b review minors + DS1c `installed`). Same bar as the DS1 review (`docs/desktop/runs/DS1/review-report.md`).

## Already proven by the PM (hunt elsewhere)
Desktop gate GREEN (fmt, clippy -D warnings, 67 tests, release tray build, `ldd` without gtk/webkit). PM mutants: see
`docs/desktop/runs/DS3/pm-mutants.log` (stale-stamp window, Hermes-down -> Green, expected-stop negation).

## Check
1. §22 types vs the contract and fixtures: every absent-able field optional; unknown fields ignored; the fixture-coverage test
   really fails when a fixture is added.
2. Runner: absolute path, no shell, timeout kills the child (and its children?), no stdout/stderr content in logs or panics (P11).
3. `assess`/`tray_state` vs P17 (known gaps the PM found: absent `hermes` is not Amber yet? read `hermes_reachable()`; running
   + service not active gives reason ServerStopped; P5 only checks the CLI version). List every P17 item as met / not met.
4. `diff` vs D5 + P15 + P17: first poll silent, failed poll not a transition, one notice per Hermes transition, stamp window both
   ways (a future stamp is currently "fresh forever"), stamp deleted after use, CLI-missing never notifies.
5. Tray: never depends on tauri/gtk; menu vs P14 (label, which items when, Start direct, Stop -> window app); launcher single
   instance + reaper; poller thread lifecycle and shutdown on Quit; icon updated only on change; notifications never carry codes.
6. Strings: EN/ID parity, locale order `LC_ALL`, `LC_MESSAGES`, `LANG`; texts match `docs/desktop/design.md` keys where both
   exist (list mismatches).
7. Gate script: cannot pass on a missing step (e.g. `cargo test` running 0 tests, missing binary for ldd).
8. Code shape per PROJECT.md: small named functions, no duplication, one responsibility per module.

## Output
`docs/desktop/runs/DS3/review-report.md`: verdict APPROVED / APPROVED-WITH-FINDINGS / CHANGES-REQUIRED; findings as
BLOCKER/MAJOR/MINOR/NIT with `file:line` and a concrete fix; a P17 met/not-met table; a design.md-vs-code string mismatch list.
Read-only: never edit code, never run git write commands, never run the tray on the desktop, never touch the server service.
