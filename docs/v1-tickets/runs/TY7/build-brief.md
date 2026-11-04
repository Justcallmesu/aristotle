# TY7 — Claude limits row on Home (Q83) + review-TY minors

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` **Q83**, Q78/Q79, `docs/v1-tickets/runs/review-TY.md`
(MINORs), `docs/v1-tickets/runs/TY6/build-report.md`, `docs/design/v2/design-notes.md` §5.7 (meter look), then `ui/chat/PlanLimits.kt`,
`data/chat/PlanUsage.kt`, `ui/sessions/{SessionsScreen,PcStatusCard,SessionsViewModel}.kt`, `data/PcStatusPoller.kt` (poll pattern),
`ui/running/**`, `data/RunningWork.kt`. Hermes (read-only): `/home/justcallmesu/.hermes/hermes-agent` — `model.options` params
(`tui_gateway/contracts/config_free_tier_control.py:451-455`, session_id optional) and the Desktop's `lineageAliases`
(`apps/desktop/src/store/live-work-roster.ts`).

## Do
1. **Home row (Q83):** under the PC status card, one compact row: four thin labelled bars "Session · Week · Opus · Sonnet" (reuse the
   PlanLimits meter, compact variant); tap expands to the full list with "resets in…"; red at ≥ 90%; hidden when there is no Anthropic
   usage data; never "Loading…" text on Home (just absent until data). Data: `model.options {profile}` with NO session_id. Refresh: on
   Home becoming visible (but not more often than every 5 min) and at most every 5 min while visible; pull-to-refresh forces one read.
   Pure tests for the refresh rule with a fake clock. EN + ID strings.
2. Review-TY minors: (a) Running: match compressed sessions' old ids like the Desktop's lineageAliases (cite it) so one work item is not
   shown twice; (b) a per-profile timeout of one tick (10 s) so a hung profile can't hold the snapshot; (c) `sessions.changed`-triggered
   reads at most every 10 s; (d) back from Running opened via "See all" returns to that chat; (e) TY6: no "Loading limits…" for a login
   without usage data — after one empty retry, remember "none" for that sheet session; closing search doesn't retry again; (f) nits.

## Hard rules
Files: `android/**` (not `ui/theme/**`, `ui/components/**`), `docs/protocol.md` §19 (one line on the Home read), `CHANGELOG.md`
[Unreleased] one Phone line, `docs/v1-tickets/runs/TY7/build-report.md`. strings append-only. No commits; never git checkout/restore/
stash/reset. No adb; never connect to the real Hermes.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TY7` exits 0. Report ≤12 lines.
