route: std. Repo `/home/justcallmesu/project/personal/Aristotle`.

# TY6 — Claude plan limits (Q81e, Q82 D5)

Read first: `docs/v1-tickets/runs/TX1/report.md` (VERIFIED Hermes facts with file:line — your contract; re-read the cited Hermes
source at /home/justcallmesu/.hermes/hermes-agent for exact field shapes), `docs/spec.md` Q75-Q82, `docs/design/v2/design-notes.md`
(v2 component language), `data/SessionRepository.kt` (profiles.list, session.list), `data/chat/ControlFrames.kt`, the relay RPC client.

## Do
From `model.options` (already sent) read provider `anthropic` `usage.windows[{label, used_percent, resets_at, scope}]` (or
`usage.accounts[]` for a pool — show the first/active account and say so): "Current session", "Current week", "Opus week", "Sonnet week"
as thin meters (reuse the PC card's meter look, design-notes §5.7) with "resets in 2 h 10 m" / "resets Mon 09:00". Place it (a) at the top
of the model sheet under the search field, collapsible, and (b) as a "Claude limits" row in Settings › About? — no: put (b) as a tile in the
PC card's expanded view ONLY if trivial; otherwise just (a). Empty first call → "Loading limits…" and one automatic retry after 3 s; data
older than 15 min → hidden (Hermes drops it). No Fable row (D5). Pure tests: window parsing (fraction/percent per Hermes code), reset
formatting, stale/empty handling.

## Files
`ui/chat/ModelSheet.kt`, new `ui/chat/PlanLimits.kt`, `data/chat/**` parsing for model.options usage (add only), tests.

## Hard rules
Hard rules: no commits; never git checkout/restore/stash/reset. No adb; never connect to the real Hermes or server. Two other
workers build TY1/TY3/TY6 in parallel: edit ONLY your files; strings append-only (EN + ID); in `docs/protocol.md` add ONLY your own new
section (TY1 = §17, TY3 = §18, TY6 = §19) at the end — documenting the Hermes RPCs you use with the TX1 citations.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TY6` exits 0 (if it fails only in another worker's
files, say so). Report `docs/v1-tickets/runs/TY6/build-report.md` ≤12 lines.
