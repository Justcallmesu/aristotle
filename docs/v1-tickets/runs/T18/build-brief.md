# T18 build brief — Hermes cron jobs: view and control from the phone

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` §1 v1.4, Q7d; `docs/design.md`;
`docs/protocol.md` §1, §4, §5, §11, §12; then `ui/sessions/*`, `ui/settings/*`, `data/{SessionRepository,ProfileLink,ProfileSelection}.kt`,
`ui/AristotleApp.kt` (navigation). Find exactly how the Hermes Desktop lists and controls cron jobs (Hermes source and `apps/desktop/`
under `/home/justcallmesu/.hermes/hermes-agent/`, read-only): `/api/ws` methods (e.g. `cron.*`) or REST only. Cite file:line.

## Goal
The user sees Hermes' scheduled jobs for the selected profile and can do what the Desktop does with them: pause/resume, run now, see the
last runs and open a run's session, edit schedule/prompt and create/delete — only those the Desktop offers.

## Requirements
1. Transport: the Desktop's `/api/ws` methods through the relay. If cron is REST-only, STOP after documenting the endpoints (cite) and
   report — do not add a REST proxy to aristotle-server in this ticket (that is a security/design decision for the PM).
2. Screen "Scheduled" reachable from the sessions top bar (or the design.md nav pattern): list per profile (name, schedule in words +
   next run in local time, enabled state, last result), detail page (prompt, schedule, deliver target, recent runs -> open run's session in
   the chat).
3. Controls exactly as the Desktop: toggle, run now, edit, create, delete (delete confirms if the Desktop confirms). All behind the 600 ms
   arm; conflicts/refusals shown, never auto-retried. `cron.changed`-style events refresh the list (debounced like T14b).
4. Schedule editing: accept what Hermes accepts (cron expression / interval / one-shot — cite the parser); validate with Hermes' own
   validation (dry-run method if it exists) rather than a phone-side reimplementation.
5. EN/ID strings; design.md.

## Tests
Frame builders vs source; list/detail mapping; schedule display; controls + arm; refresh debounce; refusal handling.

## Hard rules
Files: `android/**`, `docs/protocol.md` (new cron section, shapes from source), `docs/v1-tickets/runs/T18/build-report.md`.
No commits; never git checkout/restore/stash/reset. Do not install on the phone; never connect to the real Hermes.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T18` exits 0.

## Report
`docs/v1-tickets/runs/T18/build-report.md`, ≤30 lines with a methods table (source cites).
