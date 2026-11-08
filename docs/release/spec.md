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

## Step 1 — personal release bundle (what gets built now)
One bundle per version, `dist/aristotle-<version>/`: signed release APK, server binary (linux-amd64), `install.sh` (installs or updates the
user service, restarts it, prints status and the pairing QR), `SHA256SUMS`, release notes from CHANGELOG. Plus "Get the app": the PC shows a
QR the phone scans to download the signed APK from the PC (design + security review first: self-signed HTTPS in a phone browser, LAN only,
checksum shown on both sides).

## Step 2 — public (later, own decision round)
Licence pick (Q130), history audit, Hermes version compatibility range + a clear "unsupported Hermes" error, Yahoo/OJK check, stronger stock
disclaimers, security review aimed at strangers' PCs (remote terminal, approvals), README/install docs, GitHub Releases pipeline.
