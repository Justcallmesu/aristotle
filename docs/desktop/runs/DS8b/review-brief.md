# DS8b review brief (deep, fresh context; last fix round — findings go to the DF board)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/desktop/spec.md` (P11, P20, **P22**),
`docs/desktop/design.md` (§4.2, §4.7, §4.8, strings `n_grant_*`), `docs/desktop/runs/DS8/review-report.md`,
`docs/desktop/runs/DS8b/{build-brief,build-report}.md`. Scope: the uncommitted tree on top of 1b4ee32 (`git diff`, `git status`).
PM proven: gate GREEN (`runs/DS8b/pm-gate/gate-summary.txt`, 166 Rust + 289 UI); mutants KILLED: grant notice with the D7 title,
failed grant read treated as an empty list. The builder did not see the Rust tests RED first (it reverted fixes by hand instead).

## Check
1. P22: grant while off / on / with enable in one tick / after tray restart; `{name}` fallback; EN+ID text equals design.md; P11.
2. `terminal_granted: Option`: every reader (tray diff, poller baseline across failed reads, app DTO, TS type, Devices last-good
   set, Terminal fallback, "no phone holds a grant", "(no longer paired)" only on known data). A failed read followed by revoke + new
   grant: exactly one notice. No path where `None` turns into "no grants" in the UI.
3. `{ok:false}` text now "error: failed" / "error: gagal" via `svc_*`: is that truthful and readable (design 4.2)? Suggest better
   if not (as a DF row, no new keys now).
4. Tests meaningful (would they fail if the fix were reverted?); nothing regressed from DS8.

## Output
`docs/desktop/runs/DS8b/review-report.md`: verdict + findings (severity, file:line, fix). Read-only: never edit code, no git write
commands, no app/tray launch, never run aristotle-server against the real service. Tests under `timeout 900`.
