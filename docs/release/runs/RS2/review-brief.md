# RS2 + RS3 — review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`. Review two COMMITTED changes: `git show 62e9096` (RS3, server install fixes, D31) and
`git show f393954` (RS2, licence notices, D45). They were committed after the PM gate so RS1 (release signing, edits android build files)
could start; RS1 is uncommitted in the tree while you work: ignore it. Inputs: `docs/release/spec.md` (Q126-Q131),
`docs/release/runs/RS2/{build-brief,build-report}.md`, `runs/RS3/{build-brief,build-report}.md`, `docs/v1-tickets/tickets.md` rows D31, D45.

## Already checked by the PM (`docs/release/runs/pm-gate-rs2/pm-check.log`)
Server gate GREEN (780 Go); android gate GREEN (1420); `scripts/licences.sh --check` 0. Mutants killed: --force never restarts; status 0 when
only one of active/answering; symlink with other content accepted; licence-kind lookup broken.

## Attack list
RS3: restart only when active (never start a stopped one by surprise); a failed restart after the unit was rewritten (state left behind,
message); symlink refusal vs `--force`, dangling link, a symlinked DIRECTORY; `status` exit codes vs `docs/protocol.md` §11.1 and any script or
doc that runs `status` and expects 0.
RS2: completeness: is anything shipped in the release APK missing from `licences.json` (compare against the resolved release classpath; fonts,
assets, copied code)? Apache-2.0 obligations (NOTICE files, licence text shipped); the two pinned licences (Termux "Other", listenablefuture)
— are the pins right? `--check` really detects a dependency change (not just a hash of its own output); verification-metadata: does the
regex trust make the Termux pin meaningful; does `exclusiveContent` break offline/cached builds? Screen: EN/ID strings, 1.3x text,
back navigation. Code shape: the generator in `android/licences/licences.gradle.kts` applied from the root project — why, and is it fragile?

## Rules
Read-only: write ONLY `docs/release/runs/RS2/review-report.md` (one report covering both). Gradle and Go only on copies in your scratch dir
(`git archive f393954 | tar -x -C <scratch>`). No adb; never systemctl or `aristotle-server install/start/stop`. No git
checkout/restore/stash/reset/commit. Avoid `bash -c`/heredocs. Report: Verified, Findings (per ticket), `VERDICT: ...`. Under ~35 lines.
