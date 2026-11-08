# RS2b — licence notices: bundled data + RS2 review minors

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, your input `docs/release/runs/RS2/review-report.md` (MAJOR-1 +
MINORs + NITs), `runs/RS2/{build-brief,build-report}.md`, `scripts/licences.sh`, `android/licences/licences.gradle.kts`,
`android/app/src/main/java/io/github/justcallmesu/aristotle/data/Licences.kt` and the licences screen.

## Do
1. **MAJOR-1:** OkHttp's bundled Public Suffix List (`PublicSuffixDatabase.list`, MPL-2.0) gets a fixed entry next to the fonts entry
   (name, source URL as MPL-2.0 requires, licence `MPL-2.0`, text `assets/licences/mpl-2.0.txt` = the official MPL-2.0 text). Then scan the
   BUILT release-classpath artifacts for any other bundled third-party data/assets (jar resources, AAR assets) and list each you find or
   say why it is not third-party; make `--check` fail if such a fixed entry's file disappears from the dependency set.
2. **`--check` in the gate:** add `bash scripts/licences.sh --check` to the android part of `docs/v1-tickets/gates.sh` (its own PASS/FAIL line).
3. The screen reads assets off the main thread once (not on every recomposition); test the pure part.
4. NITs from the report that are one-liners; list the ones you skip and why.

## Hard rules
Files: `android/licences/**`, `android/app/src/main/**` (licences screen, data, assets, strings), `android/app/src/test/**`, `scripts/licences.sh`,
`docs/v1-tickets/gates.sh` (the one added step only), `docs/release/runs/RS2b/build-report.md`. NOT `android/app/build.gradle.kts`,
`android/gradle.properties` (release signing, just committed), not `server/**`. No new libraries/plugins. No adb. Never read `~/.config/aristotle`.
No commits; never `git checkout/restore/stash/reset`. Avoid `bash -c`/heredocs/chained `$(...)`: script files + plain `bash <file>`.
Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/release/runs/RS2b` exits 0 (now including the licences check).

## Report
`docs/release/runs/RS2b/build-report.md`, ≤ 12 lines.
