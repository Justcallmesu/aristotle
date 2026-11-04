# Review brief — TB1 (chat status stack, process.list polling, process.kill behind a confirm dialog) + TR minors

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q77, `docs/v1-tickets/runs/TB1/build-brief.md`
+ `build-report.md`, `runs/review-TR.md` (minors 1-5). Hermes source (read-only): `/home/justcallmesu/.hermes/hermes-agent`
(`tui_gateway/contracts/tools_commands.py:88-151`, `tui_gateway/methods_tools.py:281-295`, `tools/process_registry.py` list_sessions /
kill_process, `apps/desktop/src/store/composer-status.ts`). Diff: `git diff HEAD -- android CHANGELOG.md docs` + untracked. PM gate
`runs/pm-gate-55` GREEN (927); PM mutation (Stop without the arm delay) fails 2. Edit nothing but your report; no gradle; no git state
changes; no adb.

Check with file:line: process.kill can only be sent from the dialog's Stop after the arm (no path from a stale dialog after the row's
process changed / session switched / reconnect; double tap; recomposition); the process id field (`session_id` in the row) is right per
the Hermes source; kill result mapping; polling only while visible+live+online, stops on 4001/4044 and leave, no leak of coroutines, no
polling in the background service, load (5 s while visible — acceptable?); subagent rows match T17 data and open the right page; the stack
can't hide the composer or prompt sheets (40% cap, IME); TR minors 1-5 really fixed; the deleted resources aren't referenced anywhere;
EN/ID. Only BLOCKER/MAJOR trigger a fix round. Report ≤25 lines at `docs/v1-tickets/runs/TB1/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
