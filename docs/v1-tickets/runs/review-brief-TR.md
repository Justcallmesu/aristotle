# Review brief — v2 redesign batch TR2-TR6 (+ TH1b folded in)

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Contract: `docs/design/v2/mockups.html` (open it in a browser
tool if available) + `docs/design/v2/design-notes.md`; `docs/spec.md` Q41, Q50, Q51, Q67-Q69, Q72-Q76; `docs/design.md`. Briefs and
reports: `docs/v1-tickets/runs/TR{2,3,4,5,6}/`. Diff: `git diff HEAD -- android` + untracked (TR1 is committed at 7d7cb28; TH1+TH1b at
935264f — also read `git show 935264f -- android/app/src/main/java/io/github/justcallmesu/aristotle/data/CredentialCodec.kt` for the
TH1b deletion rule, unreviewed). PM gate `runs/pm-gate-54` GREEN (895, one clean run after the parallel collisions); PM mutations: slider
commits every step -> 2 fail; model search case-sensitive -> 1 fail; busy extras always shown -> 2 fail. Edit nothing but your report; no
gradle; no git state changes; no adb. Nothing is checked on a device yet.

Check with file:line:
- **Behaviour preserved (highest stakes):** prompt sheets: Q41 order, 600 ms arm, FLAG_SECURE owners, fresh-auth gates for Always allow /
  sudo / secret, nothing sent on cancel; composer: send/queue/steer/stop semantics, submission_id retry, drafts, 4409; Home: search, pins,
  paging, pull-to-refresh, rename, profile fallback, offline Retry; Settings: unpair/re-pair/delete confirm paths unchanged; model
  sheet: one pickReasoning per gesture, sending state.
- **Q75 "N steps"**: every run folds incl. one item; running spinner live-only; failed count; real-turn fixtures; a reasoning-only run.
- **Board fidelity**: list the visible deviations that matter (sizes, shapes, colours roles, Q67 touch targets 48 dp, Q76 label beside),
  and judge the workers' stated deviations.
- Unused strings/resources left behind (lint warnings) — list for cleanup.
- TH1b: deletion remembered only when another address of the same kind stays.
Only BLOCKER/MAJOR trigger a fix round. Report ≤35 lines at `docs/v1-tickets/runs/review-TR.md`, last line `VERDICT: PASS |
PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
