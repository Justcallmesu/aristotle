# DFS + DFS2 review brief (deep, fresh context)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/desktop/spec.md` (P11, P15, P17, P18, P20-P23),
`docs/desktop/design.md` (§4.1 rows 4/4b, §5 strings, §6), the tickets DF7-DF9 in `docs/desktop/tickets.md` and their source
review reports (named in `docs/desktop/runs/DFS/build-brief.md`), the briefs/plans/reports in `docs/desktop/runs/DFS/` and
`docs/desktop/runs/DFS2/`. Scope: the whole uncommitted tree on top of b611b6f (`git diff`, `git status`): a cleanup sweep + P23.

## Already proven by the PM
Gates `runs/DFS/pm-gate`, `runs/DFS2/pm-gate`; mutants in `runs/DFS/pm-mutants.log` (last-good drops granted ids; second Start
not refused) and `runs/DFS2/pm-mutants.log` (read timeout 3->10 s; Hermes absent notifies; row 4b headline).

## Check (highest stakes first)
1. Process safety: `PR_SET_PDEATHSIG` spawner thread in `desktop/app/src/logs.rs` (thread lifetime, what happens when the spawner
   thread dies or is never started, signals vs the reaper, no leaked threads/zombies); runner `try_wait` error path; stamp
   compare-and-delete (P15) correctness under the tray/window race.
2. `Snapshot::keeping_last_good` (one place, used by poller and backend): never shows stale data as fresh when the server stopped;
   P20/P22 diff semantics unchanged (a failed read = no information, a later good read notifies once).
3. P23 (c) timeouts: only the three reads are 3 s; a slow but healthy machine can't flap to red through them; a timed-out read
   keeps last good data.
4. P23 (a)/(b): row 4b everywhere the window shows the state; tray unchanged; EN/ID strings match design.md.
5. Tray `starting.rs`: token released on every path (error, panic), Start menu item state.
6. Every DF item marked DONE is really done (spot-check 6), the "not doing" ones are justified, nothing regressed; tests meaningful;
   code shape per PROJECT.md.

## Output
`docs/desktop/runs/DFS2/review-report.md`: verdict + findings (severity, file:line, fix). Read-only: never edit code, no git write
commands, no app/tray launch, never run aristotle-server against the real service. Tests under `timeout 900`; mutants on a scratch
copy outside the repo.
