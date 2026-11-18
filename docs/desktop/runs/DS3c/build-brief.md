# DS3c fix brief — tray menu: other service words show neither Start nor Stop…

Repo `/home/justcallmesu/project/personal/Aristotle`. One rule change in the uncommitted `desktop/tray/src/menu.rs`.

The PM's DS3b brief said "any other service word -> Stop… (recovery)". That was wrong: `docs/desktop/design.md` §4.1 "Which
buttons are on" turns every lifecycle button off in the window for any service word other than active / inactive / failed
(`activating`, `deactivating`, `reloading`, `unknown`, …), and the tray must match the window. The one recovery exception stays:
`server == "no-answer"` with `service == "active"` -> Stop….

## Change
- `lifecycle()` (`desktop/tray/src/menu.rs:63-72`): `Service::Other` -> no Start, no Stop…; update the doc comment.
- Test `other_service_words_show_stop_for_recovery` (`menu.rs:155`) becomes `other_service_words_show_neither`; keep
  `no_answer_with_active_service_keeps_stop_for_recovery` passing. See the renamed test fail on the current code first.
- If the test count changes, adjust `MIN_TESTS` in `docs/desktop/gates.sh` to the new total.

## Files you may edit
`desktop/tray/src/menu.rs`, `docs/desktop/gates.sh`. Nothing else.

## Verify
`bash docs/desktop/gates.sh docs/desktop/runs/DS3c/self-gate` all PASS; paste the summary.

## Hard rules
Never git write commands; no installs; never run aristotle-server against the real service; no tray launch; no skill/memory edits.

## Report
`docs/desktop/runs/DS3c/build-report.md` (diff summary, RED evidence, gate summary).
