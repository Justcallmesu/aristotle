# UP2c — focused re-review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `733573f`; UP1+UP2+UP2b+UP2c uncommitted in the tree. Scope: ONLY whether
`docs/release/runs/UP2b/review-report.md` MAJOR + MINOR + NITs are closed by UP2c (`runs/UP2c/{build-brief,build-report}.md`), and whether UP2c
broke anything around it. Files: `android/.../data/update/AppUpdates.kt`, `background/AppInstaller.kt`, `AppUpdatesTest.kt` (+ callers of the
changed `UpdateDevice` interface).

## Attack list
Session id persisted across process death and compared on every status; abandoning in `giveUp` vs a status already in flight; `mySessions`
cleanup at `commit()` abandoning a session that is the CURRENT one; the reviewer's probe (`~/.hermes/profiles/pm/cache/scratch/up2b/`) run
against the new code on a scratch copy; check-time recorded only after a 200/204; tests guard behaviour (one test is admitted to pass
before the fix: does anything else guard that NIT?).

## Rules
Read-only: write ONLY `docs/release/runs/UP2c/review-report.md`. Gradle only on a copy in your scratch dir. No adb; never read
`~/.config/aristotle`. No git checkout/restore/stash/reset/commit. Avoid `bash -c`/heredocs. Report: Verified, Findings, `VERDICT: ...`.
Under ~25 lines.
