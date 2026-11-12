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

## Complete installer (2026-11-12)
Trigger: first install on a second PC (`cachyos-asus-1`, 2026-11-12) needed three manual fixes after `install.sh` reported success:
the Hermes gateway was not running (only the Desktop app; server log `dial unix ~/.hermes/gateway.sock: no such file`), ufw dropped
LAN traffic to 8760/tcp (Tailscale passes: tailscaled's own rules), and the operator had to copy the bundle by hand. Gaps G1-G6 in
`docs/release/tickets.md` (IN rows).

| Id | Decision | Source |
|---|---|---|
| Q135 | A root `install.sh` in the repo, run **on the target PC** from a checkout. No remote/ssh mode. Consequence: `dist/` is git-ignored and `release.sh` needs the signing key, so on any PC but the dev laptop the user copies a bundle and passes `--bundle <dir>`. | User 2026-11-12 (clarify, option "Local only"; PM recommended "Both"). |
| Q136 | The installer opens the firewall **only from the LAN subnet(s) the server serves at install time** (8760/tcp or the configured port, plus 5353/udp for discovery). Tailscale gets no rule. A new network = re-run the installer; rules for older subnets are left in place (listed in the output). | User 2026-11-12 (clarify, option A). |
| Q137 | When Hermes is not reachable, the installer installs and starts the Hermes gateway as a user service (`hermes gateway install`) and says so; `--no-gateway` skips it (then it only reports). | User 2026-11-12 (clarify, option A). |
| Q138 | The root installer installs the newest signed bundle in `dist/aristotle-<x.y.z>/` (version order), or the one given with `--bundle <dir>`; it refuses when there is none. Never builds from source. | User 2026-11-12 (clarify, option A). |
| P21 | (PM technical) The new steps live in the **bundle's** `scripts/install.sh` (every install path gets them); the root `install.sh` only picks the bundle and execs its `install.sh` with the remaining arguments. | One place for install logic; a downloaded public bundle needs no repo. |
| P22 | (PM technical) `aristotle-server status --porcelain`: stable line format for scripts, one fact per line, space-separated: `service <is-active>`, `enabled <is-enabled>`, `server running\|not-running\|no-answer`, `version <v>`, `port <n>`, `lan <iface> <ip> <subnet-cidr>` (one per LAN listener), `tailscale <iface> <ip>` (one per Tailscale listener), `hermes reachable\|not-running\|error`. Lines after `server` only when the server answers. Exit code identical to plain `status`. Plain `status` gains `Hermes:   reachable` / `Hermes:   not running (<reason>)`. The Hermes fact comes from the running server (the same connector and home it relays with, short timeout), so it is the truth the phone sees. `/v1/addresses` (phone API) is unchanged. | The server owns the port (config), the LAN subnet selection (`lan_interfaces`) and the Hermes home; a script recomputing them would drift. |
| P23 | (PM technical) Firewall step: ufw (when `ufw status` says active) or firewalld (`firewall-cmd --state` running); rules idempotent (no duplicate on re-run). `sudo` only here, interactive; no TTY or `sudo -n` refused -> print the exact commands, warn, continue. Any other or unknown firewall -> print the commands for ufw/firewalld and a generic note. `--no-firewall` prints only. A failed firewall step never fails the install (the service is already up). | sudo is the one privileged step; failing the whole install over it would leave the user with less than today. |
| P24 | (PM technical) Without `--no-gateway`, a missing `hermes` CLI is refused **before anything is touched** (next to the checksum check). The gateway step runs after the server is up: if `status --porcelain` says `hermes reachable`, nothing. Else (and no `--no-gateway`) install/start the gateway for the **same Hermes home the service dials** (the unit has no `HERMES_HOME`, so the installer runs `hermes` without an inherited `HERMES_HOME`), then wait (bounded) until the server reports `hermes reachable`. Still unreachable -> exit 1 with the reason and the gateway journal tail. | The server's own check is the acceptance test, not the gateway's CLI output. |
| Q139 | A user who exports a non-default `HERMES_HOME` on purpose: keep the built behaviour. The installer still makes the **default** home (`~/.hermes`, `hermes -p default`) reachable, because that is what the service dials; such a setup ends up with an extra gateway on `~/.hermes`. No refusal. | User 2026-11-12 "A" (review IN2-R10). |
| P26 | (PM technical, user-approved fix scope 2026-11-12) The gateway wait has its own budget `ARISTOTLE_GATEWAY_TIMEOUT` (default 120 s; a freshly started gateway reports ready only after platforms/cron start, measured 14-43 s) and prints a "waiting for Hermes" line. The root installer picks the newest bundle by version FIRST; if that bundle's `install.sh` is missing or not executable it fails loudly (never silently installs an older one). No 3rd review round: PM gates + mutation, then the 2.2.0 install on `cachyos-asus-1` is the check. | User 2026-11-12 (clarify "Fix now"). |
| P25 | (PM technical) Linger: if `loginctl show-user "$USER" -p Linger` is not `yes`, run `loginctl enable-linger "$USER"`; failure warns only (replaces the printed advice). | |

## Step 1 — personal release bundle (what gets built now)
One bundle per version, `dist/aristotle-<version>/`: signed release APK, server binary (linux-amd64), `install.sh` (installs or updates the
user service, restarts it, prints status and the pairing QR), `SHA256SUMS`, release notes from CHANGELOG. Plus "Get the app": the PC shows a
QR the phone scans to download the signed APK from the PC (design + security review first: self-signed HTTPS in a phone browser, LAN only,
checksum shown on both sides).

## Step 2 — public (later, own decision round)
Licence pick (Q130), history audit, Hermes version compatibility range + a clear "unsupported Hermes" error, Yahoo/OJK check, stronger stock
disclaimers, security review aimed at strangers' PCs (remote terminal, approvals), README/install docs, GitHub Releases pipeline.
