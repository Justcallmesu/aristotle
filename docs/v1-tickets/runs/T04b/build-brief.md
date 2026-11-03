# T04b build brief — Hermes connector: review fixes

route: std. Fix round for T04 (`docs/v1-tickets/runs/T04/review-report.md`, read it fully).

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/protocol.md` §1, §3, §8, then the code in
`server/internal/hermes/`. Hermes source (read-only) for discovery/profile behaviour: `/home/justcallmesu/.hermes/hermes-agent/`.

## Fix (each with a test unless noted)
1. **MAJOR** `Do` takes the target profile (e.g. `Do(ctx, profileID, req)`), mints the REST ticket for that profile as
   Hermes requires (cite the source line), and never relies on the launch profile implicitly. Test: request for profile
   `pm` mints a ticket bound to `pm` in the fake.
2. `Do` does not follow redirects (return the 3xx response to the caller).
3. `Do` strips `Authorization`, `Cookie` and any `X-Hermes-*` header the caller set before adding its own ticket.
4. Capabilities ack wait has its own timeout (default 10 s, overridable in Options) and the pre-ack frame buffer has a cap
   (e.g. 64 frames / 1 MiB) -> error if exceeded.
5. Close codes: expose a helper `CloseCode(err) (int, bool)` or sentinel errors so callers never import the websocket
   library; map 1013 (try again) and 4403 (refused). Fake Hermes test closes with each.
6. Socket discovery matches Hermes: direct `$HOME_DIR/gateway.sock` first, then the `gateway.sock.path` pointer (cite
   source); refuse a socket owned by another uid.
7. Document on `Conn.Read`/`Write` that cancelling their context closes the connection (no per-read timeouts).
8. NITs: control call honours ctx cancellation; `Do` closes the request body on early failure; fix the "HTTP 101 refused"
   message; fake buffers more than one pre-ack frame in a test.

## Hard rules
- Files you may edit: `server/internal/hermes/**`, `docs/v1-tickets/runs/T04b/build-report.md`. Not go.mod (no new deps),
  not other packages (T05b works there in parallel), not docs/protocol.md.
- Never touch the real `~/.hermes`. No commits.

## Verify
`cd server && gofmt -l . && go vet ./... && go test -race -count=1 ./internal/hermes/...` (full `./...` may fail in packages
T05b is editing; say so if it does).

## Report
`docs/v1-tickets/runs/T04b/build-report.md`, ≤30 lines: each finding -> FIXED (test name) / WONTFIX (why); API changes; commands + exit codes.
