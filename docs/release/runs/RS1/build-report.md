# RS1 build report — BLOCKED on the real key (config done and verified with a throwaway key)

- Changed `android/app/build.gradle.kts` and `android/gradle.properties` only. Release `signingConfig` is read at configuration time from `~/.config/aristotle/signing.properties` (override: Gradle property or env `ARISTOTLE_SIGNING`). No secret in the repo or build output.
- If the file is absent, `assembleRelease`/`packageRelease`/`bundleRelease` fail with "Release signing file not found: <path>…"; `testDebugUnitTest` with a bogus path still passes (checked).
- Version has one source, `aristotle.version=1.0.0` in `gradle.properties`; versionCode = major*10000+minor*100+patch (10000, unchanged). I kept 1.0.0 because `WhatsNewCardTest` needs a released CHANGELOG entry and only `[1.0.0]` exists. Bumping to 1.1.0 means a `## [1.1.0] - date` heading first.
- R8/minify OFF (and no resource shrinking): the app uses reflection-heavy libraries (serialization, markdown renderer, termux) and a shrunk build cannot be start-tested without adb. Debug is unchanged (same applicationId).
- **BLOCKER:** with the real key, `assembleRelease` fails at `packageRelease`: "keystore password was incorrect". `keytool -list` on `aristotle-release.jks` using `storePassword` from the file (read via env, never printed) also fails. The file has no CRLF, BOM, trailing whitespace or odd characters. So `storePassword` does not match the keystore, and the user must fix it (not touched).
- Throwaway-key check (temp keystore in the scratch dir, not the user's key): `assembleRelease` succeeded, APK 35,368,144 bytes (~33.7 MiB), `apksigner verify` exit 0. That APK was deleted so it can't be installed by mistake.
- Signer cert SHA-256 of the real key: not available until the password is fixed. (Throwaway cert, for the record only: d60b7480…6bdadb.)
- Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/release/runs/RS1` → PASS android (exit 0). Real-key `assembleRelease`: FAIL (above).
