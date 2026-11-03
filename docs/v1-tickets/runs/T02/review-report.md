# T02 review — Android toolchain + app shell (fresh reviewer, read-only, gradle not run)

## Confirmed correct
- applicationId = namespace = `io.github.justcallmesu.aristotle` (Q33); label "Aristotle" from `@string/app_name`; minSdk 29 (Q27).
- All deps and plugins go through `gradle/libs.versions.toml`; Compose enabled; BOM used as a platform; M3 dynamic colour on S+ with light/dark fallback (`Theme.kt:29-36`).
- No hard-coded UI strings (`HomeScreen.kt` uses only `stringResource`). `values-in` is the right Android qualifier for `id`. "Belum dipasangkan" / "Sudah dipasangkan" read naturally.
- Adaptive icon has background + foreground + monochrome; the diamond (30..78 of 108) sits inside the 66 dp safe zone.
- `.gitignore` covers build/, .gradle/, .kotlin/, local.properties, *.keystore, *.jks, .idea/, *.iml; `git status --ignored` shows these ignored and `gradle-wrapper.jar` not ignored.
- `gradle-wrapper.jar` sha256 `3b8a2577…8e98` matches services.gradle.org's 9.8.2 wrapper checksum.
- Kotlin is consistent: the compose plugin pulls KGP 2.4.21 (POM checked), and AGP's built-in Kotlin uses it.
- Test: 3 cases on a pure function the screen really calls (null / blank / non-blank). Small, but it tests real logic, not the framework.
- Nothing blocks a foreground service or networking later: the manifest has no restrictive attributes, and no INTERNET permission yet (out of scope).
- The open question in the build report is settled: `java` on PATH is 26.0.1 and the default (`archlinux-java`), so the gate needs no JAVA_HOME.

## Findings
1. MAJOR — `app/build.gradle.kts:8,13`: compileSdk/targetSdk 35. Spec Q27 and tickets.md:49 say "target latest stable"; lint reports 37 is available (`OldTargetApi`, `GradleDependency`). The brief pinned 35 to avoid installing SDK platforms, but `decisions.md` has no entry for this deviation. It also forces old androidx: Compose BOM 2025.06.01 and activity 1.10.1, while lint reports 2026.09.00 and 1.13.0 (`libs.versions.toml:4-5`). Every later app ticket inherits this. Fix: the user either approves installing platform 36/37 (then bump compile/target and the BOM), or the PM records "stay on 35 for v1" in decisions.md and amends the Q27 acceptance criterion.
2. MAJOR — build-tools 36.0.0 auto-download (assessment only, not fixed). `~/Android/Sdk/build-tools/36.0.0` is dated 2026-11-03 09:48; no other SDK dir changed. AGP 9.x needs build-tools ≥36, and AGP 8.x needs Gradle 8.x, which can't run on JDK 26. So with only JDK 26, this download could not be avoided. The other routes (install JDK 21, borrow a bundled JBR) also need the user's approval. Keeping it is reasonable, but nothing stops it from happening again: the SDK licences are accepted, so bumping compileSdk (finding 1) would silently install a platform, against PROJECT.md:42. Fix: add `android.builder.sdkDownload=false` to `gradle.properties` so missing SDK parts fail the build instead; write the user's retroactive OK for 36.0.0 in decisions.md (tickets.md:136 only says "reported").
3. MINOR — `AndroidManifest.xml:10`: `@android:style/Theme.Material.NoActionBar` is a dark-only theme. In light system mode the window background and the Android 12+ splash are dark, then Compose draws a light screen, so you see a dark→light flash at launch. Fix: add a `themes.xml` based on `android:Theme.Material.Light.NoActionBar`, plus a `values-night` variant (or use core-splashscreen).
4. MINOR — `gradle/wrapper/gradle-wrapper.properties:3`: no `distributionSha256Sum`. The worker checked the hash by hand, but the wrapper won't check it on other machines. Fix: add `distributionSha256Sum=21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607` (the official 9.8.2-bin checksum, fetched by the reviewer).
5. MINOR (affects T08) — `AndroidManifest.xml:5`: `allowBackup="false"` without `dataExtractionRules` (lint `DataExtractionRules`). For targetSdk 31+, allowBackup no longer stops device-to-device transfer. T08 stores the device token, so add an all-excluding `data_extraction_rules.xml` there or now, so T08 doesn't assume allowBackup is enough.
6. MINOR (affects T08/T09) — spec line 45 says Tailscale uses plain HTTP. With targetSdk ≥28, cleartext is blocked by default, so T08/T09 need a `network_security_config` that allows cleartext only to the Tailscale endpoint. Not a T02 defect; worth adding to the T08/T09 briefs.
7. NIT — `libs.versions.toml:13`, `app/build.gradle.kts:28`: `ui-tooling-preview` is declared but there is no `@Preview` and no `debugImplementation ui-tooling`. Drop it or use it.
8. NIT — `values/strings.xml:3`: mark `app_name` `translatable="false"` and remove it from `values-in` so the two copies can't drift.
9. NIT — `res/mipmap-anydpi-v26/`: `-v26` is unnecessary with minSdk 29 (lint `ObsoleteSdkInt`). Rename to `mipmap-anydpi`.
10. NIT — `.gitignore`: add `keystore.properties`, `*.p12` and `app/release/` before T21 (release signing), so signing material can't slip in.
11. NIT — build report line 18 says "no errors" and doesn't mention the 7 lint warnings (all listed above). Reports should give the warning count.

## Not verified by reviewer
On-device install and launch, the dark→light flash in practice (finding 3 is from theme semantics), and whether `android.builder.sdkDownload` is honoured by AGP 9.4.1 (check it on the first build after adding it).

VERDICT: PASS-WITH-FINDINGS
