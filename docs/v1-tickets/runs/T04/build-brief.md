# T04 build brief — Hermes connector package

route: std (precise protocol doc + fake server; gates and a mutation check back it)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` §2 + Q7/Q25/Q34, and
`docs/protocol.md` §1 (bootstrap), §3 (profiles), §8 (REST). The spike's working code is a reference for frame shapes:
`spikes/v0-attach/control.go`, `ws.go`, `rest.go` — read it, but write production code fresh (spikes are never imported).
Hermes source (read-only) when the doc is unclear: `/home/justcallmesu/.hermes/hermes-agent/`.

## Goal
One package, `server/internal/hermes`, that owns everything about reaching Hermes, so T06 (relay) and later REST tickets
call a small API and never touch sockets, tickets or protocol versions themselves.

## API (names may be refined; keep it this small)
- `type Connector` built from `Options{Home string /* default $HERMES_HOME or ~/.hermes */, Logger, Dialer...}`.
- `(*Connector) Identify(ctx) (Identity, error)` — `runtime_protocol`, `api_origin`, `instance_id`, served profiles.
- `(*Connector) Dial(ctx) (*Conn, error)` — mints a fresh `interactive` ticket (scope per protocol.md §3 so all served profiles are reachable), opens `<api_origin>/api/ws` with the documented subprotocols and NO Origin header, sends `client.capabilities {server_requests: true}` and waits for its ack, returns a `Conn` with `Read(ctx) ([]byte, error)`, `Write(ctx, []byte) error`, `Close()`. Frames are raw bytes (the relay passes them through).
- `(*Connector) Do(ctx, *http.Request) (*http.Response, error)` — REST helper: rewrites the request to `api_origin`, mints a single-use ticket per request and sets the header documented in §8.
- Fail closed: `runtime_protocol` not in `SupportedProtocols = {1}` -> `ErrUnsupportedProtocol` (wrapped with the value); socket missing -> `ErrHermesNotRunning`. These are sentinel errors T06 maps to phone-visible states.
- Re-identify on every Dial (the `api_origin` port changes when Hermes restarts — protocol.md §1).
- No reconnect loop inside the package: T06 decides when to redial. Provide `Backoff` (pure function: attempt -> delay, capped) for callers.

## Dependency (PM technical decision)
Go's std lib has no WebSocket client. Use `github.com/coder/websocket` (maintained successor of nhooyr.io/websocket,
context-aware, no transitive deps). No other dependency.

## Tests (fake Hermes in `internal/hermes/fakehermes_test.go` or a `hermestest` subpackage)
Fake = unix socket speaking the control protocol + `httptest` WS server checking subprotocol/ticket/Origin.
Cover: happy path (identify, ticket, dial, capabilities sent first); ticket single-use (second redeem refused);
Origin header never sent; protocol 2 -> `ErrUnsupportedProtocol`; socket missing -> `ErrHermesNotRunning`;
Hermes "restart" (new api_origin) -> next Dial re-identifies and succeeds; `Do` sets a fresh ticket per request;
Backoff monotonic and capped. Use `-race`.

## Also
- D6: correct `docs/protocol.md` §1.2 — a MISSING ticket subprotocol falls through to the web gate; only an ill-formed one closes 4403 (cite source). Edit §1 only.

## Hard rules
- Files you may create/edit: `server/internal/hermes/**`, `server/go.mod`, `server/go.sum`, `docs/protocol.md` (§1 only),
  `docs/v1-tickets/runs/T04/build-report.md`. NOT `server/cmd/**`, `server/internal/app/**`, `server/internal/config/**` (T05 owns them in parallel).
- Tests never touch the real `~/.hermes` socket. No connection to the real Hermes in this ticket.
- Never log tickets. No commits, no git state changes.

## Verify
`cd server && gofmt -l . && go vet ./... && go test -race -count=1 ./...` (exit codes in report).

## Stop and report if
protocol.md and Hermes source disagree on anything the API depends on.

## Report
`docs/v1-tickets/runs/T04/build-report.md`, ≤40 lines: files; public API summary; tests by name; commands + exit codes; NOT-VERIFIED list.
