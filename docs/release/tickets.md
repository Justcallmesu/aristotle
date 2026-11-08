# Release and installer — tickets

Spec: `docs/release/spec.md`. Gates/roles: `PROJECT.md`. Runs: `docs/release/runs/<id>/`.

| Id | Title | Depends | Side | Status |
|---|---|---|---|---|
| RS0 | **User:** create the release signing key (keystore + password outside the repo, backed up). PM gives the exact command; the PM never sees or types the password | - | user | WAITING (user) |
| RS1 | Release build config: Gradle `release` signing read from a local file outside git (`~/.config/aristotle/signing.properties`), R8/minify decision, versionCode/versionName from one source; verify the signed APK (`apksigner verify --print-certs`) | RS0 | android | TODO |
| RS2 | Licence notices (D45): in-app "Open-source licences" screen + asset generated from the actual dependency list; server notices file; Gradle `exclusiveContent` for Termux + `verification-metadata.xml` | - | both | TODO |
| RS3 | Server install fixes (D31): `install --force` restarts a running service, keeps a symlinked unit, `status` exits non-zero when inactive; + other T22 server defects that touch install | - | server | TODO |
| RS4 | Release bundle script `scripts/release.sh <version>`: signed APK + server binary + `install.sh` (install/update, restart, status, pairing QR) + `SHA256SUMS` + notes; tag `v<version>`; never pushes a release on its own | RS1, RS2, RS3 | scripts | TODO |
| RS5 | "Get the app" QR: design + security red-team (LAN only, self-signed HTTPS in a browser, checksum on both sides) before any build | RS4 | design | TODO |

## Log
- 2026-11-06 User asked for an installer after signing + licences. Q126-Q129 decided (step 1 personal bundle now; public later with stocks,
  Linux first, open source). RS0 waits for the user (signing key). RS2 and RS3 need nothing from the user.
