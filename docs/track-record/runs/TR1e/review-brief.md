# TR1d + TR1e — re-review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `256b80e`. Review the uncommitted SERVER diff (`git diff HEAD -- server docs/protocol.md`
+ new `server/internal/stocks/trackfails{,_test}.go`): TR1d plus its fix round TR1e. `android/**` is another ticket (TR2b): out of scope.
Inputs: `docs/track-record/spec.md` P16-P16b (P16b amended), `runs/TR1d/{build-report,review-report}.md`, `runs/TR1e/{build-brief,build-report}.md`, §20.5.

## Already checked by the PM
`runs/pm-gate-tr1e` GREEN (772 Go), `-race` ok. Mutations: hard limit ignoring failure history -> 2 tests (`runs/pm-gate-tr2b/pm-check.log`,
server section). Earlier: no hard limit, failures not saved -> killed.

## Attack list
1. **Rollback (the TR1d MAJOR):** build c74df38's `server` in a scratch copy and point it at a state dir written by the new code (verdicts +
   failures files, after a give-up): the old binary must read the full history and serve it. Then the reverse: a TR1d-era verdicts file with
   `failures` is migrated once and the field dropped.
2. The failures file: unusable/newer-version handling, atomic write, a failed write (only logged: acceptable?), growth bounded, entry
   removal on score and on full give-up, `since` semantics vs P16b.
3. Hard limit now needs an earlier failure: the 7-weeks-away probe gives up nothing; the alternate-day failer still ends within 30+ days.
4. The changed `TestTrackJobRecent422Waits` expectations; tests that guard nothing; code shape (trackfails.go vs levels.go pattern).

## Rules
Read-only: write ONLY `docs/track-record/runs/TR1e/review-report.md`. Probes on scratch COPIES (`git worktree` is NOT allowed: use
`git show c74df38:<path>` / `git archive c74df38 server | tar -x -C <scratch>`). Never the real Yahoo, Hermes, port 8760 or state dir; never
Gradle. No git checkout/restore/stash/reset/commit. Avoid `bash -c`/heredocs. Report: Verified, Findings, `VERDICT: ...`. Under ~30 lines.
