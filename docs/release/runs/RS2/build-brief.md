# RS2 — licence notices (D45)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `6e39b75`. Read `PROJECT.md`, `docs/release/spec.md`, `docs/v1-tickets/tickets.md`
row D45, `docs/v1-tickets/runs/T19b/review-report.md` (the Termux AAR sha256 values), `android/app/build.gradle.kts`, `android/settings.gradle.kts`,
`gradle/libs.versions.toml` (if present), `server/go.mod`.

## Do
1. **Inventory:** every third-party dependency that ends up in the release APK (resolved `releaseRuntimeClasspath`) and in the server binary
   (`go list -m all` filtered to what is linked), with its licence and copyright line. Record HOW you resolved it (command) so it can be re-run.
2. **Generator, no new plugins:** a small script `scripts/licences.sh` (or a Gradle task in the app module using only Gradle's own API) that
   writes `android/app/src/main/assets/licences.json` (name, version, licence id, licence text key) + the full licence texts once each
   (`assets/licences/<id>.txt`), and `server/THIRD_PARTY_NOTICES.txt`. Apache-2.0 NOTICE files of deps that ship one must be included.
   A gate-able check (`scripts/licences.sh --check`) fails when the dependency set changed and the asset is stale.
3. **App screen:** Settings › About › "Open-source licences": list by name, tap -> licence text (plain text, scrollable). EN + ID strings.
   Pure parsing in `data/`, unit-tested.
4. **Termux supply chain (D45 part 2):** Gradle `exclusiveContent` so the Termux group resolves only from its repository, and
   `gradle/verification-metadata.xml` with the AAR sha256 values from the T19b review (verify them against the cached files; stop if they differ).
5. Mark D45 done in your report (the PM updates the board).

## Hard rules
Files: `android/**`, `gradle/**`, `scripts/licences.sh`, `server/THIRD_PARTY_NOTICES.txt`, `docs/release/runs/RS2/build-report.md`. Not yours:
other `server/**` (RS3 runs in parallel), other docs, `CHANGELOG.md` (put a "Doc text" Phone line in the report). **No new libraries or Gradle
plugins** (the Google oss-licenses plugin needs user approval: do not add it). No network beyond Gradle's normal dependency cache use. No adb.
No commits; never `git checkout/restore/stash/reset`. Avoid `bash -c`/heredocs/chained `$(...)`: script files + plain `bash <file>`.
Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/release/runs/RS2` exits 0, and `bash scripts/licences.sh --check`
exits 0.

## Stop and report if
A dependency's licence is unknown or not open source; a Termux sha256 differs from the review's value; verification metadata breaks the build.

## Report
`docs/release/runs/RS2/build-report.md`, ≤ 15 lines (inventory counts per licence, files, tests, gate line).
