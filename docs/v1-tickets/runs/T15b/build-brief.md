# T15b build brief — T15 + TQ1 minors

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/T15/review-report.md` (all findings),
`docs/spec.md` Q10, Q46, `docs/design.md`, then the cited files (committed in HEAD).

## Fix (each with a test that fails before the fix; keep a scratch copy of the old file to prove it, never use git to revert)
1. M1: a slash command typed by hand (or before the catalog loads) is checked against the catalog's `desktop` field and the existing
   hand-kept list before sending; desktop-only/blocked ones are not sent (show the reason). If the catalog has not loaded, wait for it
   (short spinner) rather than send unchecked. `/busy` must never be sent (Q10: the phone never changes `busy_input_mode`).
2. M2: the "Switch anyway" dialog closes when session.info reports a model change from elsewhere; its confirm button uses the 600 ms arm.
3. M3: a skill command's submission id is created before the first send and reused on Retry and on a re-send of the restored text.
4. NITs: a confirm answer without a token is not treated as applied (show an error); a blocked command keeps the draft.
5. TQ1 NITs: quote icon tile colour from the theme (correct in dark mode); radius from a design.md token; reduced motion through the shared
   helper; fade uses a design.md motion curve; fix the `ComposableNaming` lint warning at `AristotleApp.kt:76`.

## Hard rules
Files: `android/**`, `docs/v1-tickets/runs/T15b/build-report.md`. No commits; never git checkout/restore/stash/reset. Do not install on
the phone.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T15b` exits 0.

## Report
`docs/v1-tickets/runs/T15b/build-report.md`, ≤20 lines, listing which tests failed against the old code.
