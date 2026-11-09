# UP2 — phone: in-app updater (Q132, Q133, P18, P19)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/release/spec.md` (In-app updater: Q132-Q134, P17-P20),
`docs/protocol.md` §11 (how the phone calls `/v1` with auth + pin), the phone's existing pinned HTTP client and one repository that does a
GET with it (pattern: the PC-status or track-record repository + its tests), Settings › About screen, the notification channels code
(alerts), `AndroidManifest.xml`, the FileProvider setup, strings EN/ID.

## Contract (UP1 builds it in parallel; code against exactly this)
`GET /v1/app/update` -> 200 `{"version":"2.1.1","version_code":20101,"sha256":"<64 hex>","size":35375245}` or 204 (none).
`GET /v1/app/apk` -> the APK bytes (`Content-Length`, ETag = sha256). Both with the same auth and pin as every `/v1` call.

## Do
1. **Check (P18):** on connect and at most every 6 h while connected; an update exists only when `version_code` > `BuildConfig.VERSION_CODE`.
   Pure rule + tests (equal, lower, higher, 204, malformed json, server older than the phone).
2. **Show (Q132):** Settings › About row "Update 2.1.1 available" with an Update button (else "Up to date · 2.1.0", plus "Check now");
   one notification per new version on a new "App updates" channel (low importance, no sound), tap -> the About row. EN + ID strings.
3. **Download:** only on tap, to the app cache, with progress; streaming sha256; size cap = the announced size (refuse more); delete on
   failure/cancel; works over the pinned client on LAN and Tailscale.
4. **Verify before install (P18):** sha256 equals the announced value AND the archive's signing certificate equals the installed app's
   (`PackageManager.getPackageArchiveInfo(... GET_SIGNING_CERTIFICATES)` vs own `signingInfo`); package name equals ours; refuse with a clear
   message otherwise. Pure comparison logic unit-tested.
5. **Install (P19, Q133):** `PackageInstaller` session; API 31+ `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`; `REQUEST_INSTALL_PACKAGES`
   in the manifest; when `canRequestPackageInstalls()` is false, explain and open `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for our package; handle
   `STATUS_PENDING_USER_ACTION` by launching the confirm intent; report success/failure in the About row. The app restarts after an update:
   nothing to resume.
6. A UP0 spike (HyperOS behaviour) runs in parallel; its report `docs/release/runs/UP0/report.md` may land while you work: read it before
   you finish and follow any API detail it gives.

## Hard rules
Files: `android/**`, `docs/release/runs/UP2/build-report.md`. Not `server/**`, `scripts/**` (UP1). No new libraries. No adb (UP0 owns the phone).
Never read `~/.config/aristotle`. No commits; never `git checkout/restore/stash/reset`. RED first where the API exists. Gradle `--tests` filters
match CLASS names. Avoid `bash -c`/heredocs/chained `$(...)`: script files + plain `bash`.
Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/release/runs/UP2` exits 0 (includes the licences check).

## Report
`docs/release/runs/UP2/build-report.md`, ≤ 15 lines + a CHANGELOG "Doc text" Phone line.
