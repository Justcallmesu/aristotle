# DS8 re-review brief (deep, fresh context)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/desktop/spec.md` (D7, P11, P15, P17, **P20, P21**),
`docs/desktop/design.md`, the prior review `docs/desktop/runs/views-wave/review-report.md`, the brief and report
`docs/desktop/runs/DS8/{build-brief,build-report}.md`. Scope: the uncommitted tree (`git status --short`, `git diff`) on top of
c1047d9. DS8 is fix round 1 of max 2.

## Already proven by the PM
Gate: `docs/desktop/runs/DS8/pm-gate/gate-summary.txt`. Mutants: `docs/desktop/runs/DS8/pm-mutants.log` (switch off->on not
notified, every granted id notified, failed read treated as off, Start allowed while activating).

## Check
1. Each prior MAJOR/MINOR/NIT the brief listed: closed, correctly (cite file:line), nothing regressed.
2. **P20**: the tray notifies exactly once per off->on and per new granted id; first snapshot = baseline; a failed read neither
   notifies nor loses a change (poller keeps the old baseline: check that logic, including a failed read followed by a revoke + new
   grant); no code/token in the text (P11); interaction with the other notices (server stopped, Hermes) in the same tick; tray
   restart does not re-notify existing grants.
3. **P21**: tray `menu.rs` and the UI `canStart` agree for every service word; `dis_other` EN/ID matches design.md.
4. The refactor (shared time/format, CSS moves, helpers moved out of views, `PairPanels`, `fakeTransport.setReply`): no behaviour
   change except the intended ones; no dead files left; imports clean; no duplication left of what MINOR-5 named.
5. Tests meaningful; UI RED was shown by mutants (report table) — spot-check two.

## Output
`docs/desktop/runs/DS8/review-report.md`: verdict APPROVED / APPROVED-WITH-FINDINGS / CHANGES-REQUIRED; findings with severity,
file:line, fix. Read-only: never edit code, no git write commands, no app/tray launch, never run aristotle-server against the real
service. Tests allowed under `timeout 900` (CARGO_TARGET_DIR=desktop/target).
