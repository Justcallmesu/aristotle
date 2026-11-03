# T02 build brief — Android toolchain + app shell

route: std

Repo root: `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md` (hard rules, code shape) and
`docs/spec.md` decisions Q6, Q9, Q11, Q27, Q28, Q33 first.

## Goal
A minimal Android project in `android/` that builds with a single gate command and installs on the user's
Poco X5 Pro (Android 14). It proves the toolchain and fixes the project shape every later app ticket extends.

## Non-goals
Any networking, pairing, chat, real icon (T20), DI framework, navigation library, CI.

## Requirements
- Gradle Kotlin DSL, Gradle wrapper committed-ready (`gradlew`, `gradle/wrapper/*`), version catalog `gradle/libs.versions.toml`.
- One module `app`. `applicationId` and root package `io.github.justcallmesu.aristotle` (Q33). App label "Aristotle".
- `minSdk 29`; `compileSdk`/`targetSdk 35` (the installed platform is `~/Android/Sdk/platforms/android-35`; build-tools 34.0.0 and 35.0.0 are installed). Do not install other SDK platforms.
- Jetpack Compose + Material 3, Kotlin with the Compose compiler plugin. Material You dynamic colour on Android 12+, with a fallback scheme.
- One `MainActivity` showing a simple screen: app name, and a line "Not paired yet" — all text from resources.
- `res/values/strings.xml` (English) and `res/values-in/strings.xml` (Indonesian; "Belum dipasangkan" or a better natural phrase). No hard-coded UI strings.
- Placeholder adaptive icon (foreground vector + background colour + `monochrome` layer) — simple shape; T20 replaces it.
- Package layout ready for later tickets: `ui/` (theme, screens), `data/` (empty placeholder only if Gradle needs it; do not create empty files just for show).
- One trivial JVM unit test so `testDebugUnitTest` runs something real (e.g. a pure function used by the screen); no test of framework behaviour.
- `android/local.properties` pointing to `/home/justcallmesu/Android/Sdk` is allowed but must be git-ignored; add `android/.gitignore` (build/, .gradle/, local.properties, *.keystore, .idea/ etc.).

## Toolchain risk (check FIRST)
Only JDK on the machine: `/usr/lib/jvm/java-26-openjdk` (OpenJDK 26.0.1). A Gradle 8.14.3 distribution is cached in `~/.gradle/wrapper/dists`.
Pick the newest released Gradle + Android Gradle Plugin + Kotlin combination that officially supports running on JDK 26 (check release notes online). If no released combination runs on JDK 26, STOP and report the exact versions you checked and the error; do not install a JDK, do not use a JDK from another tool's bundle without saying so.

## Hard rules
- Files you may create: `android/**` and `docs/v1-tickets/runs/T02/build-report.md`. Nothing else.
- Downloading the Gradle wrapper distribution and Maven dependencies is allowed. Installing system packages, JDKs or SDK packages is not.
- No commits or state-changing git commands. No signing keys.

## Verify (run them; paste exit codes)
- `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug` exits 0.
- `ls android/app/build/outputs/apk/debug/app-debug.apk`.
- `~/Android/Sdk/platform-tools/adb devices` — if a device is listed, `adb install -r` the APK and report the result; if none, say "no device attached" (the user will install it).

## Stop and report if
- JDK 26 is unsupported (above), or lint reports errors you can only fix by suppressing rules.

## Report
`docs/v1-tickets/runs/T02/build-report.md`, at most ~40 lines: chosen versions (Gradle, AGP, Kotlin, Compose BOM) with the source that says they support JDK 26; files created; commands with exit codes; test count; NOT-VERIFIED list.
