# T03b build report

## Findings (T03 review)
1. MAJOR state dir 0700 — FIXED. `config.EnsureStateDir(log)` -> `ensureDir`: MkdirAll, `Lstat`, refuse symlink / non-dir /
   dir owned by another uid, chmod to 0700 if mode differs (logged once via slog). Signature now takes `*slog.Logger` (nil ok); `app.go` passes its logger.
2. MINOR gate false green — FIXED. `gates.sh`: android step runs when `android/gradlew` is a file; non-executable -> `FAIL android (126)`. SKIP only if absent.
3. MINOR config keys — FIXED. `checkKeys` token-walks the top-level object: wrong case (`HOST`, `Port`) -> `key "HOST" must be written exactly as "host"`; duplicate -> `duplicate key "host"`; unknown keys keep `unknown field "x"`.
4. NIT empty config — FIXED ("invalid config: config file is empty").
5. NIT compile error summary — FIXED. Log matches `^FAIL ... [build|setup failed]` -> `go-tests: unknown (build failed)`.
6. NIT request log — FIXED. Logs `route` = mux pattern (`GET /healthz`), falling back to path; never the query.
- DEFERRED: second Ctrl-C swallowed during shutdown (#4); WS shutdown / hijacked conns (#7); `http.Server.ErrorLog` to slog (#7); `ConfigPath` error ignored in test and home-fallback test (#9).

## Files changed
`server/internal/config/config.go`, `config_test.go`, `server/internal/app/app.go`, `server/internal/httpapi/httpapi.go`, `httpapi_test.go`, `docs/v1-tickets/gates.sh`.

## Commands
- `cd server && gofmt -l .` -> no output, exit 0
- `go vet ./...` -> exit 0
- `go test -race -count=1 ./...` -> exit 0 (app, config, httpapi all ok); 30 PASS lines incl. subtests. New: 3 state-dir tests, 4 config-key/empty cases, 1 route-log test.
- Planted failure on scratch copy (`cache/scratch/t03b-gate/`): non-executable gradlew -> `FAIL android (126)`, exit 1. Syntax error in server -> `go-tests: unknown (build failed)`, exit 1.
- `bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T03b` -> server-gofmt/vet/test PASS, `go-tests passed=30 failed=0`, `FAIL android (1)`, exit 1.
  Android failure is `checkDebugAarMetadata` (9 AAR issues) while the parallel android worker was editing `libs.versions.toml` and running its own gradle; not caused by this ticket. I did not rerun it.

## NOT-VERIFIED
- Android gate green on the real tree (parallel worker mid-change).
- The "owned by another user" refusal has no test (needs a second uid / root); code path is `checkDirSafe`.
- Live binary run of the server with a 0755 state dir (covered by unit tests only).
- shellcheck not installed.
