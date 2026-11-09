# UP1 + UP2 — review brief (fresh reviewer, deep; security-relevant: the phone installs downloaded code)

Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `733573f`. Review the uncommitted diff (`git status`): UP1 (server `/v1/app/update`,
`/v1/app/apk`, `config.AppDir`, `scripts/install.sh` copy, protocol §21) and UP2 (phone updater: `data/update/**`, `background/AppInstaller.kt`,
`OpenUpdate.kt`, `ui/settings/UpdateRow.kt`, manifest, `ServerClient`, `Notifier`/`NotifyChannels`, Settings, strings). Inputs:
`docs/release/spec.md` (In-app updater: Q132-Q134, P17-P20), `runs/UP1/{build-brief,build-report}.md`, `runs/UP2/{build-brief,build-report}.md`,
`runs/UP0/report.md` (device: Android 14 / API 34, HyperOS).

## Already checked by the PM (`docs/release/runs/pm-gate-up/pm-check.log`)
Full gate; mutants: server size mismatch ignored, sha256 format unchecked; phone offers an equal version, accepts another signer.

## Attack list
1. **Trust chain:** can anything other than the paired PC make the phone install an APK (unpinned client, redirect following, a 200 from
   a captive portal, Tailscale vs LAN)? Is the sha256 checked over the SAME bytes that are handed to PackageInstaller (TOCTOU between verify
   and session write; a file in cache another component could swap)? Signer comparison: multiple signers, key rotation (`signingInfo`
   history vs current), `getPackageArchiveInfo` flags on API 34. Downgrade and same-version refusal.
2. **Install flow on API 34:** `USER_ACTION_NOT_REQUIRED` requirements (installer of record, `setAppPackageName`?), `STATUS_PENDING_USER_ACTION`
   while backgrounded, the unknown-sources round trip, PendingIntent mutability flags, receiver not exported, what the user sees on each
   failure; the app being killed mid-install; the "Updated to x.y.z" after restart.
3. **Server:** path handling (no traversal, fixed names), auth on both listeners, a json/APK pair being replaced mid-request (install.sh
   ordering: APK first, json last — can a request see the new json with the old APK?), large file streaming memory, log spam on 204.
4. **Check cadence:** on connect + 6 h; no storm on reconnect loops; notification once per version; works with Hermes down.
5. Tests guard behaviour, not stubs; code shape (one repository, no duplicated HTTP client setup); strings EN/ID.

## Rules
Read-only: write ONLY `docs/release/runs/UP2/review-report.md` (one report for both). Gradle/Go only on copies in your scratch dir. No adb;
never the real service, HOME, `~/.local/share/aristotle`, `~/.config/aristotle` (never read it). No git checkout/restore/stash/reset/commit.
Avoid `bash -c`/heredocs. Report: Verified, Findings (BLOCKER/MAJOR/MINOR/NIT with file:line), `VERDICT: ...`. Under ~40 lines.
