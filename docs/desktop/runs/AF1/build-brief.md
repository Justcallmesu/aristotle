# AF1 brief — triage the flaky Android test `RunningViewModelTest` "pull to refresh reads at once, offline it retries the link instead"

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Load skill `flaky-test-triage` and follow it.
Seen 2026-11-19 in the PM's full gate (`docs/desktop/runs/DS7/pm-gate-full/gate-android.log`): 1 of 1490 failed,
`java.lang.AssertionError at RunningViewModelTest.kt:78` (the `eventually` timeout helper, 3 s, polling real time). Android code is
unchanged since aeeb7a0, so it passed many gates before: suspect wall-clock timing under load (the gate ran right after the server
tests; desktop builds run on the same machine). It blocks the 2.3.0 release (release.sh runs the full gate).

1. Reproduce: run only that class in a loop (`cd android && timeout 900 ./gradlew --no-daemon testDebugUnitTest --tests
   '*RunningViewModelTest*'`), also under CPU load (e.g. a parallel `yes > /dev/null` per core for the duration, killed afterwards);
   capture each failure into `docs/desktop/runs/AF1/failures/` (the HTML/XML report text for the test).
2. Prove the cause. Test-only timing (real-time waits, too-short timeouts, poll intervals racing the window) = fix the test (prefer
   virtual time / test dispatcher already used elsewhere in this test suite; else a condition-based wait with a generous bound).
   Product race in `RunningViewModel` = report and STOP (PM decides).
3. After the fix: 30 loops of the class under the same load, 0 failures; then once the full android gate
   (`cd android && timeout 1800 ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug`). Search the other android tests for
   the same pattern (same `eventually` helper / real-time timing) and list them; fix only those that share the proven cause.

Files: android test sources only (`android/app/src/test/**`). Hard rules: never git write commands; no installs; no adb/phone; kill
every process you start (including load generators). Another worker reviews `scripts/`, `server/`, `desktop/` read-only meanwhile.
Report: `docs/desktop/runs/AF1/build-report.md` (repro counts, failure text, proven cause, fix, after-fix counts).
