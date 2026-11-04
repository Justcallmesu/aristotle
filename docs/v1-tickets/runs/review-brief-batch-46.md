# Review brief — android batch: TS2b, TV1b, TU4b (tests), TV2, TQ2

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Diff: `git diff 280fd28 -- android CHANGELOG.md` (working tree
included; TS2b, TV1b, TU4b are committed, TV2 + TQ2 are not — also list untracked files with `git status --short`). Briefs and reports:
`docs/v1-tickets/runs/{TS2b,TV1b,TU4b,TV2,TQ2}/`; TS2 review findings being fixed: `runs/TS2/review-report.md`. Spec: `docs/spec.md`
Q59, Q61-Q64, Q46; `docs/design.md`. PM gate `runs/pm-gate-46` GREEN (739); PM mutations each failed tests (lock failed-check, compat
server-too-old, What's-new fresh install, quote delay). Edit nothing but your report; no gradle; no git state changes; no adb.

Check with file:line:
- TS2b: are the 3 TS2 MINORs truly closed (recents FLAG_SECURE API 29-32 via reference-counted owners — can any owner leak the flag on or
  clear another's; auth only when resumed; abandoned check still locks; multi-window host list) — any new way to bypass the lock?
- TV1b: compatibility rule, `/v1/me` parsing robustness (missing/garbage block), banner never blocks use; global `ServerVersions` + D48
  clear on unpair/re-pair (and AppContainer.forgetPc) — races with a late `/v1/me` from the old PC writing after the clear?
- TV2: Gradle `generateChangelog` task (inputs/outputs declared for up-to-date checks and config cache? fails clearly?), What's-new due
  rule, the Markdown renderer reused read-only, CHANGELOG accuracy vs the board (any claim that is not built, e.g. LAN auto-find phone
  side TL1 is NOT built yet; device-unverified claims are fine to list but nothing invented).
- TQ2: no flash under 300 ms, the 1.2 s hold covering the transcript (taps blocked?) — acceptable UX or MINOR; reduced motion; failure
  path; no repeat of the launch quote; nothing else in ChatScreen changed.
- strings EN/ID parity; design.md.
Only BLOCKER/MAJOR trigger a fix round. Report ≤30 lines at `docs/v1-tickets/runs/review-batch-46.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
