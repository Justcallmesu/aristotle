# T02b review — Android SDK 36 / libs / T02 fixes (fresh reviewer, read-only, gradle not run)

## Checked and correct
- SDK 36 consistent: `app/build.gradle.kts:8,13` compileSdk=targetSdk=36, minSdk 29. Installed: platforms android-35/36, build-tools 34/35/36 (unchanged).
- `gradle.properties:5` `android.builder.sdkDownload=false`. The worker's compileSdk=37 probe (failed, no download) is credible. Installed SDK dirs match the expected set.
- Wrapper checksum `21bd5da2…9607` equals the live `services.gradle.org/.../gradle-9.8.2-bin.zip.sha256` (I fetched it). The wrapper jar is untouched.
- Launch theme: `values/themes.xml` -> `android:Theme.Material.Light.NoActionBar`; `values-night/themes.xml` -> `android:Theme.Material.NoActionBar` (dark). Light/night mapping is right, and it matches the Compose theme's system-dark switch. Manifest uses `@style/Theme.Aristotle`. No AppCompat/MaterialComponents dependency is needed for a pure-Compose app.
- Icon rename resolves: manifest `@mipmap/ic_launcher` -> `mipmap-anydpi/ic_launcher.xml`, which references `@color/ic_launcher_background` and `@drawable/ic_launcher_foreground` (both exist). The old `-v26` dir is deleted, with no duplicate. Fine at minSdk 29.
- No lint suppressions: grep finds no `@Suppress`, `tools:ignore` or lint config under `android/`. The tooling-preview dependency is removed from both the catalog and the build file, and nothing else referenced it.
- `app_name` is `translatable="false"` and removed from `values-in`, so the two copies can't drift. `.gitignore` additions verified with `git check-ignore`.
- Lint (`app/build/reports/lint-results-debug.xml`, 10:16): 0 errors.

## Previous T02 findings
1 MAJOR SDK 35: FIXED. 2 MAJOR sdkDownload: FIXED in code. The decisions.md entry for the retroactive OK of build-tools 36.0.0 was not in scope (the brief forbids editing docs); the report says the Q36 entry exists, which I did not re-verify. 3 dark flash: FIXED (by theme semantics, not seen on device). 4 checksum: FIXED. 5 dataExtractionRules: DEFERRED to T07/T08 (legitimate per brief). 6 cleartext: DEFERRED (legitimate). 7 unused dep: FIXED. 8 app_name: FIXED. 9 mipmap rename: FIXED. 10 signing ignores: PARTIAL, by choice (T21 deferred, but the ignores were added; harmless). 11 warning count: PARTIAL, see finding 1.

## Findings
1. NIT — `build-report.md` "Lint" says 4 warnings. The XML has 5 issues: OldTargetApi, compileSdk-37 (`GradleDependency`), BOM 2026.09.00 (`GradleDependency`), AGP 9.5.0, DataExtractionRules. The worker merged two. Fix: say 5.
2. NIT — Brief said BOM "newest stable that supports 36". The BOM 2026.06.01 choice rests on the worker's failed 2026.09.00 attempt (not re-verified; 2026.08.00 was claimed to need 37 too and I can't confirm it was tried, since gradle was not run). The lint "newer BOM" warning is consistent with this. Fix: none needed; optionally record 2026.07.xx status if it exists.
3. NIT — The `-night` theme's platform `Theme.Material.NoActionBar` gives a dark window background and light status/nav icons. Compose edge-to-edge (`enableEdgeToEdge`) is not used yet, so this doesn't matter now. Revisit in the first real-UI ticket.

No BLOCKER or MAJOR issues. Not verified: on-device launch, flash gone, icon on device, Compose 1.12/SDK 37 combination.

VERDICT: PASS-WITH-FINDINGS
