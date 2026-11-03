# Review brief — T12 (prompts from the phone) + re-check of T11b

Fresh reviewer; you did not write this. Security-relevant: this code approves commands and sends sudo passwords/secrets.
Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` Q10, Q41, **Q50, Q51** (user decisions:
never from lock screen; phone DOES send sudo/secret with safeguards); `docs/design.md`; `docs/protocol.md` §6 (edited by T12), §12;
`docs/v1-tickets/runs/T12/build-brief.md` (requirement 3 was overridden by Q51 mid-build) + `build-report.md`; `runs/T11b/build-report.md`.
Diff: `git diff HEAD -- android docs/protocol.md` + untracked android files. T11b is in commit `c2b07ff` (`git show c2b07ff -- android`).
PM gate: `runs/pm-gate-18`. Hermes source read-only: `/home/justcallmesu/.hermes/hermes-agent/`. Edit nothing but your report;
no git state changes; do not run gradle.

Decide with evidence (cite file:line):
1. Response frames for approval / clarify (single + batch with clarify.lock) / sudo / secret match the Hermes source exactly; an empty
   `value:""` really means skipped for sudo and for secret (not "empty password" sent to sudo). Wrong shape = MAJOR.
2. T12's §6 correction: on the gateway the phone uses, does ANY reply (incl. error / 4404) to a Hermes->client request settle it for all
   viewers? Is ignoring Desktop-only requests (terminal/preview reads) correct, and does §12's "decline with 4404" now contradict it?
   State exactly what §12 should say.
3. Q51 safeguards: the secret never reaches logs (incl. OkHttp/relay logging, exceptions, toString, crash handler from T09b), saved state,
   notifications, clipboard; FLAG_SECURE actually applied to the sheet's window (ModalBottomSheet is a separate window!) and the activity;
   keyboard no-learning flags (`IME_FLAG_NO_PERSONALIZED_LEARNING`, no autofill) really set. Also confirm the server relay never logs frame
   content (`server/internal/relay/`).
4. Q50: no notification action or exported component can answer a prompt; the "open session" intent from another app cannot trigger an
   answer; the unlock is required before the sheet is shown.
5. "Always allow" confirmation cannot be bypassed (double tap, recomposition); answering a withdrawn/stale prompt is handled.
6. T11b re-check: events.since/resume seam keeps its own lastSeq; old socket closed on switch; no regression of T11.
Only BLOCKER/MAJOR trigger a fix round. Report ≤40 lines at `docs/v1-tickets/runs/T12/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
