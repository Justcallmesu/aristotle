# RS1 — release build signed with the user's key

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/release/spec.md`, `android/app/build.gradle.kts`,
`android/gradle.properties`, `CHANGELOG.md` (version). RS2 (licence notices) has landed in the tree before you start; build on it.

## Facts (VERIFIED by the PM, do not re-check by reading secrets)
`~/.config/aristotle/signing.properties` exists (mode 600) with keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`; `storeFile` points
at `~/.config/aristotle/aristotle-release.jks` (mode 600), alias `aristotle`. **Never print, log, copy or cat the passwords or the
properties file; never put any of it in the repo, a report, a test fixture or Gradle output.**

## Do
1. `release` signing config read from that file at configuration time (path overridable by the Gradle property / env var
   `ARISTOTLE_SIGNING`); when the file is absent, `assembleRelease` fails with a clear message and every other task (debug, tests, lint) still
   works. No secret in `build.gradle.kts`, `gradle.properties` or the build output.
2. Version from one source: `versionName`/`versionCode` derived from one place (e.g. `gradle.properties` `aristotle.version=1.1.0`, code =
   major*10000+minor*100+patch); the What's-new/CHANGELOG reader keeps working.
3. Release build type: decide R8/minify (on with the needed keep rules, or off for this personal step) — test that the release APK starts:
   a unit test cannot, so run `assembleRelease` and `apksigner verify --print-certs` on it and record the certificate SHA-256 in the report
   (the fingerprint is public, not a secret).
4. Debug build unchanged (same applicationId, the user's current install keeps working).

## Hard rules
Files: `android/**` (build files + whatever R8 keep rules need), `docs/release/runs/RS1/build-report.md`. No new libraries/plugins. No adb (the
PM installs). No commits; never `git checkout/restore/stash/reset`. Avoid `bash -c`/heredocs/chained `$(...)`: script files + plain `bash`.
Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/release/runs/RS1` exits 0 AND `cd android && ./gradlew
--no-daemon assembleRelease` succeeds; `apksigner verify` (from `~/Android/Sdk/build-tools/*/`) passes.

## Report
`docs/release/runs/RS1/build-report.md`, ≤ 12 lines: what changed, R8 choice + why, APK size, signer cert SHA-256, gate line.
