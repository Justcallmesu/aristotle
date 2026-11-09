# UP2b — re-review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `733573f`. Review the uncommitted diff (UP1 + UP2 + the fix round UP2b, all in the tree):
focus on what UP2b changed and whether `docs/release/runs/UP2/review-report.md` MAJOR 1-2 and the MINORs/NITs are closed. Inputs: that report,
`runs/UP2b/{build-brief,build-report}.md`, `docs/release/spec.md` (Q132-Q134, P17-P20), `runs/UP0/report.md` (Android 14 / API 34, HyperOS).

## Already checked by the PM (`docs/release/runs/pm-gate-up2b/pm-check.log`)
Full gate; mutants: install.sh accepting an unlisted APK, the permission renamed, the install timeout never giving up.

## Attack list
1. MAJOR-1: permission declared; any other API 34 condition for `USER_ACTION_NOT_REQUIRED` still missing (target SDK, `setAppPackageName`,
   installer of record / self-update rule)? Cite the Android source or docs you rely on, or mark ASSUMED.
2. MAJOR-2: the state machine around Installing/Confirming: a status arriving after the 2-min give-up, two sessions alive, the pending
   confirm intent kept across process death (or not — and what the row shows then), "Confirm install" when the confirm intent is stale.
3. `ACTION_MY_PACKAGE_REPLACED` restart of the foreground service on API 34: is it really an FGS-start exemption for the `specialUse`
   type this app uses (cite)? What happens if it is not (crash on update = MAJOR).
4. install.sh order change (app pair before the server binary): any new failure window; `apk_version` parsing of odd names.
5. Regressions of anything the first review verified (trust chain, no redirects, sha over the same bytes).

## Rules
Read-only: write ONLY `docs/release/runs/UP2b/review-report.md`. Gradle/Go only on copies in your scratch dir. No adb; never the real service,
HOME, `~/.local/share/aristotle`; never read `~/.config/aristotle`. No git checkout/restore/stash/reset/commit. Avoid `bash -c`/heredocs.
Report: Verified, Findings (with file:line), `VERDICT: ...`. Under ~35 lines.
