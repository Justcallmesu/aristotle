# T15 build brief — model + reasoning switch, slash commands (and a review pass over TQ1)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` §1 v1.4, Q7d, Q24; `docs/design.md`;
`docs/protocol.md` §2.1 (session.info: model, reasoning), §4 (`session.mutate` operations `model`, `reasoning {effort}`), §5, §7; then
`ui/chat/{ChatScreen,ChatViewModel,Composer*}.kt`, `data/chat/*`, `data/SessionFrames.kt`. Verify every method against the Hermes source
(`/home/justcallmesu/.hermes/hermes-agent/`, read-only) and the Desktop client (`apps/desktop/src/...` in that tree) and cite file:line.

## Goal
From a session's chat the user can see and change the model and the reasoning effort the same way the Desktop does, and use the Desktop's
slash commands from the composer.

## Requirements
1. **Model**: the chat top bar shows the current model (from session.info, merged by present keys). Tapping opens a sheet listing the models
   Hermes offers for this session/profile (the Desktop's source of the list — cite) grouped by provider; picking one applies it with the
   Desktop's method (likely `session.mutate model`, with the right revision/generation fences — cite how the Desktop gets them; a session the
   phone has open has its revision from session.info/resume). While a turn runs, follow the Desktop (applies next turn or refused — cite).
2. **Reasoning**: effort levels the model supports (cite where the Desktop reads them); hidden when the model has none.
3. **Slash commands**: typing `/` at the start of the composer shows the Desktop's command list (from Hermes if it serves one — cite;
   never a hard-coded list that can drift) with descriptions; picking inserts it; sending goes the way the Desktop sends a slash command
   (it may be a plain `prompt.submit` that Hermes interprets, or a dedicated method — cite). Commands that only make sense on the Desktop UI
   (open a local window, file picker) are hidden or shown disabled with a reason.
4. Conflicts (`revision_conflict`/`stale_generation`) refresh the session state and show "Changed elsewhere — try again"; never auto-retry
   a model change.
5. Review pass: read the committed TQ1 (`git show HEAD~0 -- android` is NOT it; TQ1 is commit "TQ1: Aristotle quote loading screen") and
   fix anything that contradicts `docs/design.md` or spec Q44-Q46 in the files you touch anyway; list the rest in your report.
6. EN/ID strings.

## Tests
Frame builders vs source; ViewModel: model list grouping, pick -> frame with fences, conflict handling (no auto-retry), reasoning hidden for
models without it, slash menu filter/insert, desktop-only commands disabled.

## Hard rules
Files: `android/**`, `docs/protocol.md` §4/§5 only to add or correct shapes you prove from the source,
`docs/v1-tickets/runs/T15/build-report.md`. No commits; never git checkout/restore/stash/reset. Do not install on the phone. Never connect
to the real Hermes.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T15` exits 0.

## Report
`docs/v1-tickets/runs/T15/build-report.md`, ≤30 lines, with a methods table (UI action -> method, source cite).
