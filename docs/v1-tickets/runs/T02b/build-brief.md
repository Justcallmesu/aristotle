# T02b build brief — Android: SDK 36, current libraries, T02 review findings

route: std

Repo: `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` decisions Q27, Q33, Q36,
`docs/v1-tickets/runs/T02/build-report.md` and `docs/v1-tickets/runs/T02/review-report.md` (all findings), then the
real files under `android/`.

## Goal
The app shell on compileSdk/targetSdk 36 with current stable Compose/androidx, and the T02 review findings fixed, so later app tickets start from current libraries.

## Do
1. compileSdk and targetSdk 36 (platform `android-36` is now installed; build-tools 34/35/36 installed). minSdk stays 29.
2. Update the version catalog to the newest **stable** Compose BOM and androidx versions that support compileSdk 36 (and
   AGP 9.4.1 / Gradle 9.8.2 / Kotlin 2.4.21 — keep these unless a stable library needs a bump; justify any bump).
   If a library needs compileSdk 37, take its last version that works with 36.
3. `gradle.properties`: `android.builder.sdkDownload=false`. Verify on the build that AGP honours it (report how you verified; if it is ignored, say so).
4. `gradle/wrapper/gradle-wrapper.properties`: add `distributionSha256Sum` for Gradle 9.8.2 bin (official checksum; the T02 review report names it — verify against gradle.org).
5. Launch theme: no dark flash in light mode (DayNight-based platform theme, or `Theme.Material.Light.NoActionBar` + night variant). Compose theme unchanged otherwise.
6. NITs from the review that are one-liners (unused dependency, `app_name translatable="false"` if appropriate, icon folder naming) — fix them. Leave signing ignores for T21; leave backup/cleartext for T07 (do NOT add network security config or data extraction rules now).

## Hard rules
- Files you may edit/create: `android/**`, `docs/v1-tickets/runs/T02b/build-report.md`. Nothing else.
- No new SDK packages (step 3 must prevent auto-download; if the build tries to download one, stop and report).
- No commits, no git state changes.

## Verify (paste exit codes)
- `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug` exits 0; lint 0 errors, no new suppressions.
- `ls ~/Android/Sdk/build-tools ~/Android/Sdk/platforms` unchanged from: build-tools 34.0.0 35.0.0 36.0.0; platforms android-35 android-36.
- `~/Android/Sdk/platform-tools/adb devices`: if a device is attached, install and report; else "no device".

## Report
`docs/v1-tickets/runs/T02b/build-report.md`, ≤40 lines: versions before -> after; each review finding -> FIXED/DEFERRED(ticket)/WONTFIX(why); commands with exit codes; lint warning count; NOT-VERIFIED list.
