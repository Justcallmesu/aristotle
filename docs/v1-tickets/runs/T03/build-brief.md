# T03 build brief — server skeleton + gate script

route: std

Repo root: `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md` (hard rules, code shape) and
`docs/spec.md` §2 and decisions Q7, Q26 first.

## Goal
The Go module for `aristotle-server` with a runnable `serve` and `version`, config/state locations, logging, a
loopback health endpoint, and the project gate script that every later ticket is checked with.

## Non-goals
Talking to Hermes (T04), listeners on Wi-Fi/Tailscale (T05), pairing, systemd install (T21).

## Requirements
- `server/go.mod`, module path `github.com/justcallmesu/aristotle/server`, Go 1.26 (installed: go1.26.5). Standard library only unless a dependency is clearly justified in the report.
- `server/cmd/aristotle-server/main.go`: thin; parses subcommand `serve` | `version` | `help` and delegates. Unknown subcommand -> usage on stderr, exit 2.
- `version` prints a version string set at build time via `-ldflags "-X …"`, default `dev`.
- `serve`: loads config, starts an HTTP server on `127.0.0.1:<port>` only (default port 8760; configurable), `GET /healthz` -> `200 {"status":"ok","version":"…"}`. Graceful shutdown on SIGINT/SIGTERM.
- Config: `$XDG_CONFIG_HOME/aristotle/config.json` (default `~/.config/aristotle/`); missing file = defaults; invalid JSON or unknown keys = clear error, exit 1. State dir `$XDG_STATE_HOME/aristotle/` (default `~/.local/state/aristotle/`), created with 0700 when `serve` starts.
- Config validation refuses any listen host other than loopback for now (`127.0.0.1`, `::1`) — spec §2 rule "never 0.0.0.0".
- Logging via `log/slog` (text handler), never logging request bodies or auth headers.
- Package layout: `internal/config`, `internal/httpapi` (router + handlers), `internal/app` (wires and runs). Small named functions.
- Tests (table-driven where natural): config defaults, invalid JSON, unknown key, non-loopback host refused, `/healthz` response via `httptest`, unknown subcommand exit code (test the dispatcher function, not os.Exit).
- `docs/v1-tickets/gates.sh <run-dir>`: bash, `set -euo pipefail`; runs from the repo root regardless of cwd; steps:
  1. server: `gofmt -l .` must print nothing, `go vet ./...`, `go test ./...` (in `server/`);
  2. android: only if `android/gradlew` exists, `./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug`;
  Each step's output goes to `<run-dir>/gate-<step>.log`; writes `<run-dir>/gate-summary.txt` with one line per step `PASS|FAIL|SKIP <step> (<exit code>)` and the Go test count; exits non-zero if any step fails. Must not modify any source file.
- Add `server/.gitignore` for build output (e.g. `/bin/`).

## Hard rules
- Files you may create: `server/**`, `docs/v1-tickets/gates.sh`, `docs/v1-tickets/runs/T03/**`. Nothing else (`android/` belongs to a parallel worker; do not create it).
- No commits or state-changing git commands. Do not leave `serve` running when you finish.

## Verify
- `bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T03` exits 0 (android step SKIP or PASS depending on whether the other worker's project exists yet — either is fine; report which).
- Manual: `go run ./cmd/aristotle-server serve` in background, `curl -s 127.0.0.1:8760/healthz`, then stop it; paste the output.

## Stop and report if
- A requirement here conflicts with Go conventions in a way that would make the code worse; explain instead of guessing.

## Report
`docs/v1-tickets/runs/T03/build-report.md`, at most ~40 lines: files created; commands with exit codes; test count; NOT-VERIFIED list; any dependency added and why.
