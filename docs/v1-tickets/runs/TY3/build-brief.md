route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`.

# TY3 — Home: group by project + Desktop sort keys (Q81d, Q82 D3)

Read first: `docs/v1-tickets/runs/TX1/report.md` (VERIFIED Hermes facts with file:line — your contract; re-read the cited Hermes
source at /home/justcallmesu/.hermes/hermes-agent for exact field shapes), `docs/spec.md` Q75-Q82, `docs/design/v2/design-notes.md`
(v2 component language), `data/SessionRepository.kt` (profiles.list, session.list), `data/chat/ControlFrames.kt`, the relay RPC client.

## Do
Home C history (not the live cards) gains a "Group: Date | Project | Status" and "Sort: Updated | Created | Status | Tokens | Cost"
control (a compact `FilterChip` row or a sheet behind one icon in the history header — keep Home calm). Project grouping comes from
`projects.tree {profile, preview_limit: 3, session_limit: 300}` for the active profile; sections = projects in the Desktop's order rules
(TX1 §3: Home first, active explicit, explicit before auto, with sessions first, recency, label); a project header shows its name +
session count, collapsible; "Load more" per project via `projects.project_sessions`. Refetch on `projects.changed`. Sort keys and status
rank copied from the Desktop (cite). Cost sort hidden when every cost is 0 (Desktop rule). Choices (group, sort, collapsed projects,
phone-local project order) persist per profile on the phone. Keep pins (phone-local) on top as today. Keep date grouping as the default.
Pure tests: project order, status rank, each sort key, cost-sort visibility, persistence.

## Files
`ui/sessions/**` EXCEPT the ⋮ menu block (TY1 edits it), new `data/ProjectTree.kt`, `data/SessionRepository.kt` (add the calls only),
tests.

## Hard rules
Hard rules: no commits; never git checkout/restore/stash/reset. No adb; never connect to the real Hermes or server. Two other
workers build TY1/TY3/TY6 in parallel: edit ONLY your files; strings append-only (EN + ID); in `docs/protocol.md` add ONLY your own new
section (TY1 = §17, TY3 = §18, TY6 = §19) at the end — documenting the Hermes RPCs you use with the TX1 citations.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TY3` exits 0 (if it fails only in another worker's
files, say so). Report `docs/v1-tickets/runs/TY3/build-report.md` ≤12 lines.
