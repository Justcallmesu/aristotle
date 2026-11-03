# T02b build report — Android SDK 36, current libs, T02 review fixes

Result: gate exit 0. Only `android/**` edited (plus this report). No commits.

## Versions (before -> after)
- compileSdk / targetSdk: 35 -> 36. minSdk 29 unchanged.
- Compose BOM: 2025.06.01 -> 2026.06.01 (Compose 1.11.4). Newest BOM 2026.09.00 (Compose 1.12.1) and 2026.08.00 require compileSdk 37, so I took the last one that works with 36 (tried 2026.09.00: checkAarMetadata failed on 9 compose artifacts).
- activity-compose: 1.10.1 -> 1.13.0 (works with 36).
- AGP 9.4.1, Gradle 9.8.2, Kotlin 2.4.21 unchanged. No bump needed (AGP 9.5.0 exists, not taken).

## Review findings
1. MAJOR compile/targetSdk 35 -> FIXED (36; BOM/activity bumped as above; remaining lint note: 37 available, needs another SDK install).
2. MAJOR sdkDownload -> FIXED: `android.builder.sdkDownload=false`. Decision entry (Q36) already exists in spec.
3. MINOR dark flash -> FIXED: `Theme.Aristotle` (`values/themes.xml` on `android:Theme.Material.Light.NoActionBar`, `values-night/themes.xml` on `android:Theme.Material.NoActionBar`), set in manifest. Compose theme untouched. Not seen on device.
4. MINOR wrapper checksum -> FIXED: `distributionSha256Sum=21bd5da2…9607`; matches `https://services.gradle.org/distributions/gradle-9.8.2-bin.zip.sha256` (fetched, identical). Wrapper accepted it in the build.
5. MINOR dataExtractionRules -> DEFERRED (T07/T08, per brief). Still the 1 lint `DataExtractionRules` warning.
6. MINOR cleartext/network security config -> DEFERRED (T07/T08/T09, per brief).
7. NIT unused ui-tooling-preview -> FIXED (removed from catalog and build file).
8. NIT app_name -> FIXED (`translatable="false"` in values; removed from values-in).
9. NIT mipmap-anydpi-v26 -> FIXED (renamed `mipmap-anydpi`).
10. NIT signing ignores -> DEFERRED (T21, per brief). I did add `keystore.properties`, `*.p12`, `app/release/` to `android/.gitignore`; it's a harmless one-liner, revert if you want T21 to own it.
11. NIT report warning count -> FIXED (below).

## sdkDownload verification
Temporarily set compileSdk=37 (not installed): build failed with "Failed to find target with hash string 'android-37.0'", with no download and no new SDK dirs. So AGP 9.4.1 honours the flag. compileSdk restored to 36. The build-tools folder had also been stable through the 36 build.

## Commands
- `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug` -> exit 0 (BUILD SUCCESSFUL, 4m48s). The first attempt with BOM 2026.09.00 failed (exit 1, compileSdk 37 required); that is the reason for the BOM choice.
- `ls ~/Android/Sdk/build-tools ~/Android/Sdk/platforms` -> 34.0.0 35.0.0 36.0.0 / android-35 android-36 (unchanged).
- `adb devices` -> no device.

## Lint
0 errors, 4 warnings: AndroidGradlePluginVersion (9.5.0 available), GradleDependency (BOM 2026.09.00 available), OldTargetApi/compileSdk-newer (37 available), DataExtractionRules (deferred). No suppressions added. Unit tests: not re-counted from the report; `testDebugUnitTest` passed (PairingStateTest, 3 cases).

## NOT VERIFIED
- Install/launch on the Poco X5 Pro, the light-mode launch flash being gone, the icon rename on device.
- Compose 1.12 / compileSdk 37 combination (needs another platform install; not approved).
- AGP 9.5.0.
