# T03b build brief — server: state dir permissions, gate false-green, strict config keys

route: std

Repo: `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/v1-tickets/runs/T03/review-report.md`
(all findings), then the real files under `server/` and `docs/v1-tickets/gates.sh`.

## Goal
Fix the T03 review MAJOR and the two MINORs before T05 stores keys and tokens in the state dir.

## Do
1. **MAJOR — state dir 0700** (`server/internal/config/config.go` `EnsureStateDir`, ~line 98): after `MkdirAll`, enforce
   mode 0700. Decide: chmod an existing dir owned by the current user to 0700 (log it once), and refuse (error) if the dir
   is owned by another user or is a symlink. Tests: new dir -> 0700; existing 0755 dir -> becomes 0700; symlink -> error.
2. **MINOR — gate false green** (`docs/v1-tickets/gates.sh` ~line 48): if `android/gradlew` exists but is not executable,
   the android step is FAIL (not SKIP). SKIP only when `android/gradlew` does not exist.
3. **MINOR — config keys**: JSON keys are exact-case (`host`, `port`, …); a differently-cased or duplicate key is an error
   naming the key. Tests for `HOST`, `Port`, duplicate `host`.
4. NITs that are one-liners and safe: empty config file -> clear error ("config file is empty"); compile error makes the gate summary not claim `failed=0` (e.g. report `go-tests: unknown (build failed)`); request log logs the route pattern or path without query string. Leave second-Ctrl-C, WS shutdown and net/http logger for later tickets (list them in the report as deferred).

## Hard rules
- Files you may edit/create: `server/**`, `docs/v1-tickets/gates.sh`, `docs/v1-tickets/runs/T03b/**`. Nothing else (`android/` and `docs/protocol.md` are parallel workers).
- Standard library only. Small named functions. No commits, no git state changes. Leave no process running.

## Verify (paste exit codes)
- `cd server && gofmt -l . && go vet ./... && go test -race -count=1 ./...`.
- `bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T03b` — the android step may run (another worker may be mid-build in `android/`; if it fails because of that, rerun the server steps only and say so).
- Planted-failure check of gates.sh on a scratch COPY of the repo in `/home/justcallmesu/.hermes/profiles/pm/cache/scratch/t03b-gate/`: non-executable gradlew -> FAIL, exit 1.

## Report
`docs/v1-tickets/runs/T03b/build-report.md`, ≤40 lines: each finding -> FIXED/DEFERRED; files changed; commands with exit codes; test count; NOT-VERIFIED list.
