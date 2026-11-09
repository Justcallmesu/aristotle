# UP0 — spike: tap-less self-update on the Poco (HyperOS)

route: deep. Question to answer (Q133, P19): on the user's Poco (`adb devices`: `22101320G`, HyperOS, Android 15/API 35 ASSUMED — read it),
can an app update ITSELF through `PackageInstaller` with `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` without a tap, and which prompts
appear (a) on the first self-update of an adb-installed app, (b) on the second self-update (the app is now the installer of record)?

## Do
1. In your scratch dir (NOT the repo), a minimal throwaway app: package `io.github.justcallmesu.updspike`, one screen with a button "Update"
   that installs `files/next.apk` through a PackageInstaller session (`REQUEST_INSTALL_PACKAGES`, API 31+ `USER_ACTION_NOT_REQUIRED`,
   status receiver that logs every status incl. `STATUS_PENDING_USER_ACTION` and launches its confirm intent). Reuse the Gradle/AGP versions
   from the repo's `android/gradle/libs.versions.toml` (read-only) so it builds offline from the cache. Sign v1, v2, v3 with ONE throwaway key.
2. adb install v1; push v2 into the app's files dir (`run-as`); tap Update via `adb shell input tap` (find coords with `uiautomator dump`);
   screenshot (`adb exec-out screencap -p`) after each step; record every dialog (Install unknown apps settings, system confirm, HyperOS
   security scan, …) and whether it needed a tap. Then v2 -> v3 the same way. Check `dumpsys package` installer of record after each.
   If a dialog needs a human tap you cannot do safely with `input tap`, stop and report what the dialog says (screenshot).
3. Uninstall `io.github.justcallmesu.updspike` at the end.

## Hard rules
**Never touch the real app `io.github.justcallmesu.aristotle`** (no uninstall, no clear, no run-as, no launch) and nothing else on the phone
beyond the spike package and the "Install unknown apps" toggle FOR THE SPIKE PACKAGE. Never the repo's files (write only
`docs/release/runs/UP0/report.md` + screenshots under `docs/release/runs/UP0/`). Never the real signing key or `~/.config/aristotle`. No commits.
Avoid `bash -c`/heredocs/chained `$(...)`: script files + plain `bash`. Another worker runs Gradle in the repo: build only in your scratch project.

## Report
`docs/release/runs/UP0/report.md`, ≤ 20 lines: Android/HyperOS versions, each step's prompts (with screenshot names), installer of record
after each, VERDICT on Q133 (tap-less from the 2nd update: yes / no / only with X), and any API detail UP2 must copy.
