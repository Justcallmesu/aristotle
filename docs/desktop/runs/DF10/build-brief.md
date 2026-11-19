# DF10 brief — triage the flaky `ServiceView.test.tsx:231`

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Load skill `flaky-test-triage` and follow it.
Report from the DS8b review: `desktop/ui/src/views/service/ServiceView.test.tsx:231` failed 2 of 12 runs on an unchanged copy, then
passed 8 in a row; failure text not captured (`docs/desktop/runs/DS8b/review-report.md`, FYI).

1. Reproduce: loop the file alone (`cd desktop/ui && timeout 900 npx vitest run src/views/service/ServiceView.test.tsx`) and the full
   suite, at least 50 runs each or until 3 failures; capture each failure's full text into `docs/desktop/runs/DF10/failures/`.
   Try under CPU load (e.g. two loops at once) if it does not reproduce.
2. Prove the cause (a race in the test vs a race in the product code). Product race = report it and STOP before fixing (PM decides).
   Test race (e.g. an assertion before an async state settles, timer leakage, shared fake state) = fix the test.
3. After the fix: 100 runs of the file and 30 of the full suite, 0 failures. Search the other view tests for the same pattern and
   fix it the same way (list each site).

Files you may edit: test files under `desktop/ui/src/**` (`*.test.ts(x)`, `src/test/**`). Product code only if the cause is a product
bug AND the PM approved (not in this run).
Hard rules: never git write commands; no installs; no app launch; no aristotle-server commands; kill every process you start.
Report: `docs/desktop/runs/DF10/build-report.md` (repro counts, failure text, proven cause, fix, after-fix loop counts).
