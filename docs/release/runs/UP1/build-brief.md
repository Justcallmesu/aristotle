# UP1 — server: serve the app update (P17)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/release/spec.md` (In-app updater: Q132-Q134, P17-P20),
`docs/protocol.md` §0, §11 (listeners, endpoints, auth, pin), an existing simple authenticated GET handler and its test as the pattern
(e.g. `/v1/pc-status`, §16 and its handler), `scripts/install.sh`, `scripts/test/install_test.sh`, `docs/release/README.md`.

## Do
1. `GET /v1/app/update` -> 200 `{version, version_code, sha256, size}` for the APK in the app dir, 204 when there is none; `GET /v1/app/apk`
   streams it (`Content-Length`, `application/vnd.android.package-archive`, `ETag` = sha256; `Range` support only if the existing HTTP
   stack gives it for free via `http.ServeContent` — say which). Same auth as every `/v1` endpoint, both listeners. The app dir is
   `$XDG_DATA_HOME/aristotle/app/` (default `~/.local/share/aristotle/app/`; follow how the server already resolves its state dir) holding
   `aristotle.apk` + `aristotle.json` (`version`, `version_code`, `sha256`, `size`), written by install.sh. The server re-reads the json on
   each request (cheap) and checks the APK size matches; a missing/corrupt pair -> 204 + one log line.
2. `install.sh`: copy the bundle's APK + a generated `aristotle.json` into the app dir atomically (temp + rename, json last), after the
   checksum check, before the restart; `version_code` computed the same way as Gradle (major*10000+minor*100+patch). Tests in
   `install_test.sh` (temp HOME).
3. `docs/protocol.md` new §21 "App update" (endpoints, json, codes) and §11.2 table rows; README: "phones update themselves from 2.1.0 on".

## Hard rules
Files: `server/**`, `scripts/install.sh`, `scripts/test/**`, `docs/protocol.md`, `docs/release/README.md`, `docs/release/runs/UP1/build-report.md`.
Not `android/**` (UP2 in parallel). Fakes only: never the real service, state dir, HOME, `~/.local/share/aristotle`, port 8760, systemctl.
No commits; never `git checkout/restore/stash/reset`. Avoid `bash -c`/heredocs/chained `$(...)`: script files + plain `bash`. RED first.
Gate after your LAST edit: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/release/runs/UP1` exits 0 AND `bash scripts/test/run.sh` exits 0.

## Report
`docs/release/runs/UP1/build-report.md`, ≤ 12 lines, including the exact JSON example and status codes (UP2 codes against it).
