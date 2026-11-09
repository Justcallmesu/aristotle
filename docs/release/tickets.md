# Release and installer — tickets

Spec: `docs/release/spec.md`. Gates/roles: `PROJECT.md`. Runs: `docs/release/runs/<id>/`.

| Id | Title | Depends | Side | Status |
|---|---|---|---|---|
| RS0 | **User:** create the release signing key (keystore + password outside the repo, backed up). PM gives the exact command; the PM never sees or types the password | - | user | DONE (2026-11-08: keystore + signing.properties, both 600; verified without reading secrets) |
| RS1 | Release build config: Gradle `release` signing read from a local file outside git (`~/.config/aristotle/signing.properties`), R8/minify decision, versionCode/versionName from one source; verify the signed APK (`apksigner verify --print-certs`) | RS0 | android | TODO |
| RS2 | Licence notices (D45): in-app "Open-source licences" screen + asset generated from the actual dependency list; server notices file; Gradle `exclusiveContent` for Termux + `verification-metadata.xml` | - | both | DONE (gate GREEN, 1 mutant killed; review on the commit) |
| RS3 | Server install fixes (D31): `install --force` restarts a running service, keeps a symlinked unit, `status` exits non-zero when inactive; + other T22 server defects that touch install | - | server | DONE (780 Go, 3 mutants killed; review on the commit) |
| RS1 (status) | Signing config + single version source built; **blocked on the user**: `storePassword` in signing.properties does not open the keystore (keytool fails the same way). R8 off. | RS0 | android | DONE (2026-11-08: real-key assembleRelease + apksigner verify OK, signer matches the keystore fingerprint) |
| RS2b | RS2 review: MAJOR-1 OkHttp's bundled Public Suffix List (MPL-2.0) missing from the notices; `--check` into gates.sh; screen reads assets off the main thread; NITs. RS3 minor (failed restart message) + RS4 notes (install.sh polls status; ship THIRD_PARTY_NOTICES.txt) go into RS4's brief | RS2 | android | DONE (gate GREEN 1422 + licences; mutation killed; not re-reviewed alone: the RS4 review covers it) |
| RS4 | Release bundle script `scripts/release.sh <version>`: signed APK + server binary + `install.sh` (install/update, restart, status, pairing QR) + `SHA256SUMS` + notes; tag `v<version>`; never pushes a release on its own | RS1, RS2, RS3 | scripts | DONE (gate GREEN, 2 mutants killed, review PASS-WITH-FINDINGS) |
| RS4b | RS4 review: MINOR-1 install.sh refuses inside the app's remote terminal (the restart kills it mid-update) + README note that an update closes phone terminals; MINOR-2 a failing `install --force --start` prints the rollback command + journal lines (test); executable bit on release.sh/install.sh; the three untested release.sh refusals. Deferred to step 2: MINOR-3 nested-jar asset scan, static/trimpath server binary | RS4 | scripts | DONE (script tests exit 0; mutants env-marker + cgroup killed; fixes of reviewed findings, no separate review) |
| RS5 | "Get the app" QR: design + security red-team (LAN only, self-signed HTTPS in a browser, checksum on both sides) before any build | RS4 | design | TODO |

## Log
- 2026-11-06 User asked for an installer after signing + licences. Q126-Q129 decided (step 1 personal bundle now; public later with stocks,
  Linux first, open source). RS0 waits for the user (signing key). RS2 and RS3 need nothing from the user.
- 2026-11-08 RS0 done by the user. RS2 built (stop condition: 8 Google-terms artifacts from the code scanner -> Q131 open for step 2) and RS3 built. PM gates GREEN (android 1420, server 780), mutations 4/4 killed. Committed; deep reviews run on the commits in parallel with RS1.
- 2026-11-08 RS1 built (gate GREEN; throwaway-key assembleRelease + apksigner OK) but the real key fails: storePassword in signing.properties does not open the keystore -> user. RS2/RS3 review PASS-WITH-FINDINGS: MAJOR-1 (MPL-2.0 Public Suffix List missing) -> RS2b; RS3 minor + RS4 notes -> RS4 brief.
- 2026-11-08 User fixed signing.properties (first try failed: file password differed from the keystore). Real-key release APK built and verified (signer 5af32f63...70bb, 1.0.0/10000, 35.4 MB). RS4 dispatched.
- 2026-11-08 RS4 built (deep). PM full gate GREEN (782 Go, android, licences, scripts); mutants killed: release.sh accepts any signer, install.sh skips checksums. No dist/ or tag in the real repo. Deep review dispatched (covers RS2b too).
- 2026-11-08 RS4 review PASS-WITH-FINDINGS (no BLOCKER/MAJOR). Committed. RS4b dispatched before the first real release (MINOR-1: running install.sh from the phone terminal kills it mid-update).
- 2026-11-08 RS4b done, committed. User chose version 2.0.0 ("2"). CHANGELOG [2.0.0] heading + aristotle.version=2.0.0; release.sh 2.0.0 run by the PM.
- 2026-11-09 2.0.0 bundle built (gate GREEN, signer pinned OK, 5e22b34). User ran install.sh: server 2.0.0 live, .prev kept. Phone: debug uninstalled, release 2.0.0 (20000) installed via adb after the HyperOS USB-install prompt (first try cancelled). NIT: THIRD_PARTY_NOTICES.txt in the bundle is mode 600 -> next release round.
