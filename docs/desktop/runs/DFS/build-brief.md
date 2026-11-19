# DFS brief — NIT sweep DF7 + DF8 + DF9 (before DS7)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). The rows DF7, DF8, DF9 in `docs/desktop/tickets.md` list
the items; their sources (read each before fixing):
- DF7: `docs/desktop/runs/DS3/re-review-report.md` (NITs a-h) and `docs/desktop/runs/DS3/review-report.md` (NIT-3/5/6/7/9).
- DF8: `docs/desktop/runs/DS3w/review-report.md` (NITs 1, 4, 5, 6, 8, 9).
- DF9: `docs/desktop/runs/DS8b/review-report.md` (MINOR-1, check-3 `{ok:false}` text, NIT-1, 2, 4, 5, 6).
- Extra: `docs/desktop/runs/DS8/review-report.md` "outside DS8": Service shows "Server stopped / The service is inactive" while the
  service is `activating` (fix the headline per design.md §4.1 for other service words; if design.md has no string for it, report it).
Spec `docs/desktop/spec.md`, binding design `docs/desktop/design.md`.

## Rules
- First write `docs/desktop/runs/DFS/plan.md`: one line per item: id, file:line verified NOW (code moved since the reviews), fix,
  or "already fixed by <commit/file:line>" / "not doing: <reason>" (needs a PM decision = list it, do not decide).
- Behaviour changes get a test seen failing first (scratch copy or a reverted hunk, never git). Comment/rename-only items need none.
- `{ok:false}`: the code block shows `service: <raw word>` (like the `warning:`/`problem:` blocks), no "error: failed" text; no new
  i18n key.
- DF8-1 (UI re-derives the §4.1 table): only if it fits cleanly; the UI must map core's reason, not re-implement the table. If it
  grows past ~200 changed lines, stop that item and report.
- Do not change design.md/spec.md; report any mismatch.

## Files you may edit
`desktop/**` (not `desktop/target`, `node_modules`), `docs/desktop/gates.sh` (floors = new totals only).

## Verify
`timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DFS/self-gate` all PASS; paste the summary verbatim.

## Hard rules
Never `git add/commit/push/checkout/restore/stash/reset`. No installs/new deps. No app/tray launch against the real server; never run
aristotle-server against the real service. No skill/memory edits. Kill every process you start.

## Report
`docs/desktop/runs/DFS/build-report.md`: the plan table with the final status per item (file:line, test name), gate summary,
items needing a PM decision.
