# UP2b — updater fix round (review CHANGES REQUESTED)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `733573f`; UP1 + UP2 are uncommitted in the tree and are your base. Read
`PROJECT.md`, your input `docs/release/runs/UP2/review-report.md` fully (MAJOR 1-2, MINORs, NITs with file:line), `runs/UP2/build-report.md`,
`runs/UP1/build-report.md`, `docs/release/spec.md` (Q132-Q134, P17-P20), `runs/UP0/report.md` (device: Android 14 / API 34, HyperOS).

## Do
1. **MAJOR-1:** declare `android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION` (manifest); a test that reads the merged manifest (or the
   source manifest, pattern: existing manifest tests if any) and fails without it. Note in the report the API 34 conditions from the
   Android docs that still apply (target SDK, self-update) — cite the doc page you used; if you cannot reach it, say so (ASSUMED).
2. **MAJOR-2:** the row never sticks on "Installing": a pending confirmation is kept and re-launched when the app comes to the front
   (`STATUS_PENDING_USER_ACTION` while backgrounded), and an install with no status after a timeout (e.g. 2 min) or after the user left the
   confirm screen returns the row to "Update" with a short message; the Update button is never blocked forever. Tests for the state rules.
3. MINORs: one download at a time (double tap); "Asking your PC…" cleared on disconnect; a minimum gap between checks under reconnect
   loops (e.g. 10 min, rule + test); a checksum mismatch drops the offer and re-checks instead of retrying the stale one; the "restarts"
   string tells the truth — after an update, relaunch the link service from `ACTION_MY_PACKAGE_REPLACED` if Android 14 allows starting it
   there (verify against the FGS start rules; if not allowed, post a "Updated to x.y.z — tap to reconnect" notification instead and word
   the string accordingly).
4. Server/script MINOR: `install.sh` requires the APK to be listed in `SHA256SUMS` (refuse before touching anything otherwise), and copies
   the app pair before replacing the server binary, so a later failure cannot leave a new server with no APK pair (or explain why the
   current order is safe). Tests in `install_test.sh`.
5. NITs: the APK's versionCode must equal the announced `version_code` (refuse otherwise); rename the over-claiming test.

## Hard rules
Files: `android/**`, `scripts/install.sh`, `scripts/test/**`, `docs/release/runs/UP2b/build-report.md`. Not `server/**` unless a MINOR truly needs
it (then say why). No new libraries. No adb. Never read `~/.config/aristotle`; never the real HOME/service. No commits; never `git
checkout/restore/stash/reset`. RED first. Avoid `bash -c`/heredocs/chained `$(...)`: script files + plain `bash`.
Gate after your LAST edit: `bash docs/v1-tickets/gates.sh docs/release/runs/UP2b` (full) exits 0.

## Report
`docs/release/runs/UP2b/build-report.md`, ≤ 15 lines + the CHANGELOG "Doc text" Phone line (updated if the wording changed).
