# Desktop views — shared rules for DS4, DS5, DS6 (binding for each view brief)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Read in full: `docs/desktop/spec.md` (D3, D7, D15, D16,
D19, P3, P5, P11, P13-P18), `docs/desktop/design.md` (binding: §3 components, §4 states incl. 4.1 table and 4.2 action/error states,
the view's own section, the string table), `docs/protocol.md` §22, and the committed `desktop/ui/src` (shell, `ipc/`, `state/`,
`components/`: `ConfirmDialog`, state light, `NeedsServer`, `CommandFailed`, `useCommand` — `{ok:false}` counts as failed).
Load skills `antislop-ui`, `frontend-ui-engineering`, `make-interfaces-feel-better`.

## Rules
- Edit ONLY `desktop/ui/src/views/<your view folder(s)>/**` and append your view's NEW string keys to `desktop/ui/src/i18n/en.json`
  and `id.json` **only if** design.md lists them and they are missing (report every key you add; the PM merges if two views touch
  the files). Never edit shared components, `ipc/`, `state/`, the app crate, core, or `gates.sh`. If a shared piece is missing or
  wrong, STOP and report it (the PM fixes shared code once, for all views).
- Data only from the typed IPC client and the snapshot events; no polling in the view; every §22 absent-able field handled.
- Every state in design.md for your view, incl. loading, needs-server, command-failed, mismatch (actions disabled), not-installed.
- Every destructive action through the shared `ConfirmDialog` (focus on Cancel, Esc); Cancel sends nothing (test it).
- Strings only via i18n keys (EN + ID). Light/dark from tokens. Keyboard reachable; visible focus.
- Tests (Vitest + Testing Library) for every state and every confirm path with fake IPC (`src/test/fakes.tsx`); RED first where a test
  guards logic (keep a scratch copy, never git).
- Screenshots: render each state in light and dark via the test harness or a Storybook-like page you add under your view folder
  (no new dependency), saved to `docs/desktop/runs/<ticket>/shots/`. Do not launch the real app against the real server.
- Verify only your own tests: `cd desktop/ui && timeout 600 npx vitest run src/views/<folder>` plus `npm run lint` and
  `npm run typecheck`. The PM runs the full gate after the wave (three workers share the tree).

## Hard rules
Never `git add/commit/push/checkout/restore/stash/reset`. No installs, no new npm dependency (ask in the report if you think one is
needed). Never run aristotle-server commands. No skill/memory edits.

## Report
`docs/desktop/runs/<ticket>/build-report.md`: files, states covered (table: design.md state -> test name -> screenshot), keys added,
RED evidence, own-test/lint/typecheck output, shared-code issues found.
