# Release and installer — spec

Status: **decided Q126-Q129 (2026-11-06); step 1 (personal release bundle) planned, not built.** Supersedes `docs/spec.md` Q11
("sideloaded APK, personal use") and the "Play Store / public" out-of-scope line **for step 2 only**; step 1 keeps Q11.

## Verified current state (2026-11-06, HEAD e93fb9b)
- The server already installs itself as a systemd user service: `aristotle-server install` / `status` (`server/internal/app/servicecmds.go`,
  `server/internal/service/service.go`); build script `scripts/build-server.sh`. Linux only.
- Open install defects on the T22 list: D31 (`install --force` does not restart a running service; a symlinked unit is replaced by a file;
  `status` exits 0 when inactive). Licence notices missing: D45 (Apache-2.0 deps: Termux, Compose, OkHttp, markdown renderer, Play services
  code scanner; server: rsc.io/qr) + Gradle `exclusiveContent`/verification metadata for the Termux AARs.
- The app is a debug build (`versionName=1.0.0` on the phone); no release signing config. The repo `Justcallmesu/aristotle` is private.

## Decisions
| Id | Decision | Source |
|---|---|---|
| Q126 | Two steps: **step 1** = a release bundle for the user's own use now (same mechanics as a public one); **step 2** = "go public", decided after real use. | User 2026-11-06 "A". |
| Q127 | A public build will include stocks (Yahoo data, AI verdicts) with stronger disclaimers. **Risks to re-check before step 2:** Yahoo ToS forbids automated collection (Q16 was accepted for personal use only); OJK rules on publishing AI buy/sell calls (ASSUMED, not researched). | User 2026-11-06 "B" (PM recommended A: stocks out of the public build). |
| Q128 | Public installer: Linux only at first (what exists); Windows/macOS later. | User 2026-11-06 "A". |
| Q129 | Open source on a public GitHub repo with releases (licence MIT or Apache-2.0: open, Q130 at step 2). **Before the repo goes public:** audit the whole git history for secrets and personal data (run logs hold LAN/Tailscale addresses, paths, device names). | User 2026-11-06 "B". |
| Q11 | Superseded for step 2 by Q126-Q129; still true for step 1. | |
| Q131 | **Open for step 2:** the QR scanner (`play-services-code-scanner`) pulls 8 Google artifacts under Google terms (Android SDK licence, ML Kit ToS), not open source. Fine for step 1 (listed in the notices, RS2). For the public open-source release (Q129): keep it (F-Droid and some users refuse proprietary deps), or replace it with an open scanner (e.g. ZXing, Apache-2.0; adds a camera permission flow). PM recommendation for step 2: replace. | RS2 stop condition, 2026-11-08. |

## Release signing key (public facts only)
Alias `aristotle`, `CN=Aristotle`, created 2026-11-08, file `~/.config/aristotle/aristotle-release.jks` (outside git, backed up by the
user). Certificate SHA-256: `5a:f3:2f:63:9d:8c:98:e1:2c:18:e0:f0:93:e8:da:c2:6b:90:9d:0f:bd:4c:59:3d:cf:a1:20:24:5f:de:70:bb`. Every release
APK must carry exactly this signer (`release.sh` checks it). Passwords live only in `~/.config/aristotle/signing.properties`; in that file
a `\` must be written `\\` (Java properties escaping).

## In-app updater (2026-11-09; replaces RS5)
| Id | Decision | Source |
|---|---|---|
| Q132 | The phone shows "Update x.y.z available" (Settings › About row + a notification); the user taps Update; it downloads and opens the install. No background download. | User 2026-11-09 "A". |
| Q133 | Install without a tap when Android/HyperOS allows it (from the second self-update on); otherwise one tap. | User 2026-11-09 "A" (UP0 checks HyperOS). |
| Q134 | RS5 (QR download page) dropped; a brand-new phone gets its first APK by USB or a copied file. | User 2026-11-09 "A". |
| P17 | (PM technical) The PC serves the APK of the installed release: `install.sh` copies the bundle's APK + its sha256 to `~/.local/share/aristotle/app/` (latest only). `GET /v1/app/update` -> `{version, version_code, sha256, size}` or 204 when none; `GET /v1/app/apk` streams it. Same auth and certificate pin as every `/v1` endpoint (§11.2-§11.3), LAN and Tailscale. | Reuses the paired channel: no browser, no self-signed HTTPS prompt, no new port. |
| P18 | (PM technical) The phone checks on connect and at most every 6 h while connected; offers only `version_code` > its own. Before installing it verifies the sha256 AND that the APK's signer equals the app's own signer (refuses otherwise, says why). Download to app cache, deleted after install or failure. | Android also enforces same-signer on update; checking first gives a clear message instead of a system error. |
| P19 | (PM technical) Install through `PackageInstaller` sessions with `REQUEST_INSTALL_PACKAGES`; `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` on API 31+ (Q133). The first update asks the user to allow "Install unknown apps" for Aristotle once. | Session API is the only path to tap-less updates. |
| P20 | (PM technical) A server rollback (`.prev`) can leave the phone newer than the server: the phone keeps working by the protocol version rules (§0); the updater never offers a lower `version_code` (Android refuses downgrades). | |

**Bootstrap:** 2.0.0 has no updater, so the first version with it (2.1.0) is installed by USB once; every update after that comes over the air.

## Step 1 — personal release bundle (what gets built now)
One bundle per version, `dist/aristotle-<version>/`: signed release APK, server binary (linux-amd64), `install.sh` (installs or updates the
user service, restarts it, prints status and the pairing QR), `SHA256SUMS`, release notes from CHANGELOG. Plus "Get the app": the PC shows a
QR the phone scans to download the signed APK from the PC (design + security review first: self-signed HTTPS in a phone browser, LAN only,
checksum shown on both sides).

## Step 2 — public (later, own decision round)
Licence pick (Q130), history audit, Hermes version compatibility range + a clear "unsupported Hermes" error, Yahoo/OJK check, stronger stock
disclaimers, security review aimed at strangers' PCs (remote terminal, approvals), README/install docs, GitHub Releases pipeline.
