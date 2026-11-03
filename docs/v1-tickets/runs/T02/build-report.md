# T02 build report — Android toolchain + app shell

Result: gate passes on JDK 26. One rule breach, unavoidable: AGP auto-downloaded SDK build-tools 36.0.0 (see Issues).

## Versions (JDK 26 = /usr/lib/jvm/java-26-openjdk, 26.0.1)
- Gradle 9.8.2. Gradle compatibility matrix (docs.gradle.org/current/userguide/compatibility.html): JDK 26 runs Gradle 9.4.0+, JDK 27 from 9.8.0. 8.14.x tops out at JDK 24, so the cached 8.14.3 cannot be used.
- AGP 9.4.1 (stable on Google Maven; 9.5.0 also exists, not tried). Release notes: min Gradle 9.6.0, JDK 17+, max API 37. AGP 8.x needs Gradle 8.x and is ruled out.
- Kotlin 2.4.21 (compose compiler plugin `org.jetbrains.kotlin.plugin.compose`); AGP 9 has built-in Kotlin, so no `kotlin.android` plugin.
- Compose BOM 2025.06.01, activity-compose 1.10.1: deliberately older, because newer androidx needs compileSdk 36+ and only platform 35 is installed.
- Note: no source states AGP 9.4 is tested on JDK 26 (it lists JDK 17 minimum only). It works in practice.

## Files (all under android/)
`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `.gitignore`, `local.properties` (git-ignored, verified), `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `res/values{,-in}/strings.xml`, `res/values/ic_launcher_background.xml`, `res/drawable/ic_launcher_foreground.xml`, `res/mipmap-anydpi-v26/ic_launcher.xml` (background, foreground, monochrome), `MainActivity.kt`, `ui/theme/Theme.kt` (dynamic colour on 12+, fallback scheme), `ui/home/HomeScreen.kt`, `data/PairingState.kt`, `app/src/test/.../data/PairingStateTest.kt`.
Wrapper was generated with a Gradle 9.8.2 distribution unzipped in the scratch dir (sha256 checked); nothing installed.

## Commands
- `cd android && JAVA_HOME=/usr/lib/jvm/java-26-openjdk ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug` → exit 0 (final run, after dropping `buildToolsVersion`). First clean run took 7m22s (dependency download).
- Lint: no errors in the report. No rules suppressed.
- Tests: 3 run, 0 failures, 0 skipped (`PairingStateTest`).
- `ls android/app/build/outputs/apk/debug/app-debug.apk` → present, 24 MB.
- `adb devices` → no device attached (user installs).

## Issues
- AGP 9.4.1 requires build-tools 36.0.0 and ignores a lower `buildToolsVersion`, so it auto-downloaded 36.0.0 into `~/Android/Sdk/build-tools/`. That is an SDK package install, which the brief forbids. I did not remove it (that would be another SDK change, and the next build would fetch it again). PM decides: keep, or accept that an older AGP cannot run on JDK 26.
- The Gradle invocation needs `JAVA_HOME=/usr/lib/jvm/java-26-openjdk` unless it is the default JDK. I did not check `java -version` on PATH.

## NOT VERIFIED
- App launch and rendering on the Poco X5 Pro (no device); Indonesian string display; dynamic colour at runtime; icon look.
- Gate run with a different JDK. AGP 9.5.0.
- `PROJECT.md` still says Gradle gate "created by T02": PM updates it.
