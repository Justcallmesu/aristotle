# Views wave review brief (DS4, DS5, DS6, DS1d + DS3x/DS3y re-check) — deep, fresh context

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/desktop/spec.md` (D3, D7, D15, D16, D19, P3, P5,
P11, P13-P19), `docs/desktop/design.md` (binding), `docs/protocol.md` §22, `docs/desktop/runs/views-shared-brief.md`, the briefs and
reports in `docs/desktop/runs/{DS4,DS5,DS6,DS1d}/`, and the DS3x/DS3y reports. Scope: the uncommitted tree (`git status --short`,
`git diff`) plus commit `56ad86a` (DS3x+DS3y, not separately reviewed: re-check `logs.rs` stop/reaper, `service.rs` stamp-on-timeout,
`useCommand`, `NeedsServer`, `CommandFailed`, snapshot `seq`, `PendingIntent`).

## Already proven by the PM (hunt elsewhere)
Combined gate: `docs/desktop/runs/views-wave/pm-gate-desktop-2/gate-summary.txt` and the server gate (895 Go tests). PM mutants:
`docs/desktop/runs/views-wave/pm-mutants.log` (revoke sent twice, pair code kept after expiry, turn-off never confirms, Start on any
non-active word, mismatch not disabling buttons, QR built from different text). All KILLED except **V1 SURVIVED**: in
`views/devices/DevicesView.tsx` `confirmRevoke`, adding a second `revoke.run(...)` after `setAsking(null)` passed every test.
Decide: is it equivalent (does `useCommand.run` drop a call while one is in flight? then say so and whether that guard is tested
in `useCommand.test.tsx`), or does no test assert "revoke sent exactly once on confirm" (then MINOR+: add it, and check the same
gap in Stop/Restart/Terminal confirms). Note: the gate's `MIN_UI_TESTS` is still 73 while the suite has 225.

## Check (highest stakes first)
1. **D7 / D15 dialogs**: every destructive or access-widening action (stop, restart, revoke, terminal on, grant, turn off with open
   terminals) goes through `ConfirmDialog`; Cancel / Esc / scrim send nothing; focus on Cancel; no "don't ask again"; red button on
   D7. Look for a path that bypasses the dialog (keyboard shortcut, intent, double click while working).
2. **P11**: the pairing code, `qr_payload`, `qr_modules`, fingerprint never logged, never in global state / snapshot / localStorage,
   dropped on unmount and expiry; the dev-only shot/preview pages (`views/status/shots/`, `views/pair/preview/`,
   `views/terminal/shots.*`) are NOT in the production bundle (check `vite build` inputs and `dist/`) and contain no real codes.
3. **Truthfulness**: no view reports success before the server confirms (`{ok:false}` = failure; revoke row removed only by the next
   snapshot; service state from the next poll); stale replies after unmount ignored.
4. Service buttons agree with the tray menu rule (`desktop/tray/src/menu.rs`) for every service word; the `--confirm stop` / `--pair`
   intents fire once.
5. Logs: start/stop pairing on view change, unmount and window close; cap; text-only rendering; reconnect.
6. **DS1d**: `qr_modules` equals the encoding of `qr_payload` (all addresses up to `MaxPayloadAddresses`), absent when empty, plain
   `pair` output unchanged, §22 text matches the code, Rust/TS types optional.
7. design.md conformance per view (states, strings EN+ID, tokens; light/dark) — spot-check screenshots in `runs/DS{4,5,6}/shots/`.
   Builders deviated on: Logs-when-stopped (followed design.md over the brief), other service words show no reason, `qr_modules`
   invalid -> code without QR tile, date format. Judge each.
8. Shared-code gaps the builders reported (banner double spacing, no shared row/table/field CSS -> `kv-*`, `tm-*`, local classes;
   `data-theme` missing vs design.md §1; no granted count in the snapshot; `fakeTransport` cannot change replies): which must be fixed
   before release vs the DF sweep. Duplication across the three views' CSS/helpers.
9. Code shape per PROJECT.md (small named functions, no duplication); tests meaningful.

## Output
`docs/desktop/runs/views-wave/review-report.md`: verdict APPROVED / APPROVED-WITH-FINDINGS / CHANGES-REQUIRED; findings
BLOCKER/MAJOR/MINOR/NIT with file:line + fix; "user live checks still needed". Read-only: never edit code, no git write commands, no
app/tray launch, never run aristotle-server against the real service. You may run tests (`timeout 900`; CARGO_TARGET_DIR=desktop/target).
