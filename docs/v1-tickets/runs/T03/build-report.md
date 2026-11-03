# T03 build report

## Files created
- `server/go.mod` (module `github.com/justcallmesu/aristotle/server`, go 1.26), `server/.gitignore` (`/bin/`)
- `server/cmd/aristotle-server/main.go` (thin; `version` var for `-ldflags "-X main.version=…"`, default `dev`)
- `server/internal/config/{config,config_test}.go` — defaults, strict JSON (unknown keys / trailing data rejected), loopback-only + port validation, XDG config/state paths, state dir 0700
- `server/internal/httpapi/{httpapi,httpapi_test}.go` — `GET /healthz`, request logging (method+path only)
- `server/internal/app/{app,app_test}.go` — `Run` dispatcher (serve|version|help; unknown/none -> usage on stderr, 2), `Serve`, graceful shutdown on SIGINT/SIGTERM
- `docs/v1-tickets/gates.sh`, `docs/v1-tickets/runs/T03/` (this report + `gate-*.log`, `gate-summary.txt`)

## Commands
- `bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T03` -> exit 0. Summary:
  `PASS server-gofmt (0)`, `PASS server-vet (0)`, `PASS server-test (0)`, `go-tests passed=22 failed=0` (counts subtests too), `SKIP android (0)` (no `android/gradlew` yet).
- Manual (temp XDG dirs, built binary, SIGTERM via `timeout`): `curl -si 127.0.0.1:8760/healthz` ->
  `HTTP/1.1 200 OK` / `Content-Type: application/json` / `{"status":"ok","version":"dev"}`; `ss` showed `127.0.0.1:8760` only; serve exited 0 with "shutting down" log; state dir mode 700.
- `version` with `-X main.version=0.0.1` printed `0.0.1`; `bogus` -> usage on stderr; config `{"host":"0.0.0.0"}` and `{"x":1}` -> clear error, exit 1.
- No server process left running; temp binary/dirs removed.

## Notes
- Config keys: `host` (default `127.0.0.1`), `port` (default 8760).
- Gate script uses `go test -count=1 -v` (no cache, needed for the count). Empty `server/` is a SKIP; gofmt step is its own log (`gate-server-gofmt.log`). Steps are named `server-gofmt|server-vet|server-test|android`.
- Android gate checks `-x android/gradlew`.

## NOT VERIFIED
- Android gate PASS path (project absent at run time).
- SIGINT (only SIGTERM exercised manually; same handler).
- gates.sh FAIL path (gofmt/vet/test failing) not exercised.
- Config path with unset `XDG_*` (home fallback) not unit-tested.
- IPv6 `::1` binding not run live (only config parse tested).

## Dependencies
None (standard library only).
