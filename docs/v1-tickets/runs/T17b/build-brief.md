# T17b build brief — T17 fix round

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/T17/review-report.md` (all findings), then
the cited files (committed in HEAD) and `tools/delegate_tool.py:565-568,840-846` + `apps/desktop/.../delegate-model.ts` in
`/home/justcallmesu/.hermes/hermes-agent/` (read-only).

## Fix (each with a test that fails before the fix; keep a scratch copy of the old file to prove it, never use git to revert)
1. MAJOR-1: `Delegation.callOf` treats only `list`/`steer`/`stop` (and any other non-spawning action the source defines — cite) as control
   calls; `"action":"spawn"`, missing or empty action spawn subagents. Test with `"action":"spawn"`.
2. MAJOR-2: `subagent.list` (5 s) and `subagent.tail` (2 s) loops pause on lifecycle ON_STOP and resume with one immediate read on
   ON_START (process lifecycle for list, the subagent page's for tail). VM test with a fake lifecycle.
3. Tail loop with the link down: back off (2 s -> 30 s cap) instead of retrying every 2 s.
4. An interrupted child is not shown in red (design.md: red only for failures and Stop) — use the neutral done style with "Stopped".
5. Correct every `delegate-model.ts` line cite in `Delegation.kt` comments, `docs/protocol.md` §2.2 and `runs/T17/build-report.md`.

## Hard rules
Files: `android/**`, `docs/protocol.md` §2.2, `docs/v1-tickets/runs/T17/build-report.md` (cites only),
`docs/v1-tickets/runs/T17b/build-report.md`. No commits; never git checkout/restore/stash/reset. Do not install on the phone.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T17b` exits 0.

## Report
`docs/v1-tickets/runs/T17b/build-report.md`, ≤15 lines, listing which tests failed against the old code.
