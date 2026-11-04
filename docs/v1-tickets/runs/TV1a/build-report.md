# TV1a build report

Gate: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TV1a` → exit 0 (gofmt, vet, test; 341 tests pass; android SKIP).

- New `server/internal/version`: `Version` (default `0.0.0-dev`), `Commit` (default `unknown`) via ldflags; consts `ProtocolVersion=1`, `MinPhoneProtocol=1`; `Line()`.
- `cmd/aristotle-server/main.go` passes `version.Version`; the old `main.version` var is gone.
- `aristotle-server version` prints `aristotle-server <version> (<commit>) protocol <N>`.
- `/healthz` adds `"protocol": N` (`version` stays the server semver). `/v1/me` adds `"server":{version,protocol,min_phone_protocol}`.
- `scripts/build-server.sh [out]`: version from `git describe --tags` (leading `v` stripped), fallback `0.0.0-dev+<sha>`. Default output is `server/bin/aristotle-server`, which no `.gitignore` covers (there is none in the repo).
- Checked by hand: the script build printed `0.0.0-dev+6acf5ac (6acf5ac) protocol 1`. A manual ldflags build printed `9.9.9 (deadbee)`.
- Tests: `version_test.go` (defaults, line format), `TestHealthz` (protocol field), `TestMeServerBlock` (exact keys), `TestRunDispatch` (version output).
- Docs: `docs/protocol.md` new §0 (bump rules: breaking phone-facing change bumps, additive does not) and §11.2 (`/healthz`, `/v1/me`).
- Touched: `server/**`, `scripts/build-server.sh`, `docs/protocol.md`. Not touched: `android/`, `CHANGELOG.md`. No commits.
