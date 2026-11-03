# T04 build report — Hermes connector

## Files
- new: `server/internal/hermes/{hermes.go,control.go,conn.go,rest.go,backoff.go,fakehermes_test.go,hermes_test.go}`
- edited: `server/go.mod`, `server/go.sum` (only dep: `github.com/coder/websocket v1.8.15`), `docs/protocol.md` §1.2 (D6)
- D6: a MISSING ticket subprotocol falls through to the web app (`run_api.py:428-429`, `reason=='none'`); only an
  ill-formed one (no `hermes-gateway-v1`, >1 ticket entry, empty ticket) closes 4403 (`web_server_boundary.py:160-171`, `run_api.py:446`).
- protocol.md and Hermes source agreed on everything the API uses.

## Public API (`package hermes`)
- `New(Options{Home, Logger, HTTPClient}) (*Connector, error)`; Home default `$HERMES_HOME` or `~/.hermes`; honours `gateway.sock.path`.
- `Identify(ctx) (Identity, error)`: runtime_protocol, api_origin, instance_id, state, served profiles. Also requires state `ready`, capability `session-authority-v1`, and a loopback http `api_origin` (tickets never leave loopback).
- `Dial(ctx) (*Conn, error)`: re-identifies, mints `interactive`/`scope:host` ticket for the launch profile, upgrades `/api/ws` (subprotocols `hermes-gateway-v1` + ticket, no Origin, no compression), sends `client.capabilities`, waits for ack (id 1). Frames received before the ack (gateway.ready) are returned by the first `Read`s.
- `Conn.Read/Write/Close` on raw bytes.
- `Do(ctx, *http.Request)`: re-identifies, rewrites to api_origin, drops Origin, fresh `native-http` ticket per request in `X-Hermes-Gateway-Ticket`.
- `Backoff(attempt)`: 500 ms doubling, cap 30 s. `SupportedProtocols = {1}`.
- Sentinels: `ErrHermesNotRunning`, `ErrUnsupportedProtocol` (wrapped with value), `ErrNotReady`, `ErrUpgradeRefused` (wrapped with HTTP status); `*ControlError` for control refusals. Tickets are never logged or put in errors.

## Tests (fake Hermes: unix control socket + httptest WS/REST, single-use tickets, exact params keys, Origin check)
TestIdentifyAndDialHappyPath (also capabilities is first client frame, mint = interactive/host/launch profile, gateway.ready kept),
TestTicketIsSingleUse, TestDialNeverSendsOrigin, TestUnsupportedProtocolFailsClosed (no ticket minted),
TestSocketMissingIsNotRunning, TestStaleSocketFileIsNotRunning, TestSocketPathFileIsHonoured,
TestRestartRedialsNewOrigin, TestDoMintsFreshTicketPerRequest (distinct tickets, Origin stripped, native-http, no scope),
TestBackoffMonotonicAndCapped.

## Commands (in `server/`)
- `gofmt -l .` → no files, exit 0
- `go vet ./...` → exit 0
- `go test -race -count=1 ./...` → exit 1: `internal/config` TestEnsureDirRefusesSymlink fails (T05's package, not mine; under TMPDIR scratch). app, httpapi, hermes ok.
- `go test -race -count=3 ./internal/hermes/...` → ok, exit 0

## NOT VERIFIED
- Never run against the real Hermes (forbidden here); fake mirrors protocol.md, not live behaviour.
- Mutation check not run by me (PM does it).
- Ack matching assumes Hermes echoes integer id 1 for client.capabilities (as the spike did).
- Control-socket answers over 1 MiB are rejected (limit is my choice).
- Hermes closing frames (1013 draining, 4403) surface as raw websocket close errors from `Read`, not mapped.
