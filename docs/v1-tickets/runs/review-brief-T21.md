# Review brief — T21 (systemd user service commands + D7)

Fresh reviewer. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/v1-tickets/runs/T21/build-brief.md` +
`build-report.md`. Diff: `git diff HEAD -- server` + untracked `server/internal/service/`. PM gate GREEN (`runs/pm-gate-13`, 254 Go tests).
Edit nothing but your report; never call real `systemctl` enable/start or `install`; no git state changes.
Check: unit file correctness and hardening (would systemd accept it; ExecStart quoting of paths with spaces; UMask; no HERMES_HOME;
restart policy), refuse-to-overwrite logic, uninstall/purge safety (never deletes outside the state dir; symlinks), D7 second-signal exit,
ErrorLog bridge, tests use the fake runner only. Only BLOCKER/MAJOR trigger a fix round. Report ≤25 lines at
`docs/v1-tickets/runs/T21/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
