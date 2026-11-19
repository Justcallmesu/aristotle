# DFS2 brief — sweep decisions P23 (a)-(c)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Context: the DFS sweep (uncommitted, in the tree; report
`docs/desktop/runs/DFS/build-report.md`) left items for the PM; decisions are spec **P23** in `docs/desktop/spec.md`; design.md
§4.1 has a new row 4b and strings `st_changing` / `sub_changing`; §6 menu/tooltip wording updated.

1. **P23 (a)**: row 4b in the window's state derivation (Status headline, footer, Service state row): `server == not-running` with a
   service word other than `inactive`/`failed`/absent → `st_changing` / `sub_changing` (`{word}` raw), light err. Add both keys to
   `en.json`/`id.json` exactly as design.md. Tray stays red; its tooltip unchanged. Tests per surface + the strings test passes.
2. **P23 (b)**: the tray sends no Hermes notice when the new snapshot has `hermes` absent (`core/src/diff.rs` `hermes_went_down`).
   Test: reachable → absent = no notice; reachable → not-running = notice.
3. **P23 (c)**: 3 s timeout for `devices list`, `terminal status`, `version` reads (poller and app backend); `status` keeps 10 s.
   A timed-out read = failed read (last good data kept, P20 `None` rules). Test with a fake CLI that sleeps.

Each behaviour change with a test seen failing first (scratch copy or reverted hunk; never git).
Files you may edit: `desktop/**` (not target/node_modules), `docs/desktop/gates.sh` (floors only). Not design.md/spec.md.
Verify: `timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DFS2/self-gate` all PASS; paste the summary verbatim.
Hard rules: never git write commands; no installs/new deps; no app/tray launch against the real server; never run aristotle-server
against the real service; no skill/memory edits; kill every process you start.
Report: `docs/desktop/runs/DFS2/build-report.md`.
