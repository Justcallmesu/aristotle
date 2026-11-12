# IN1 build brief — `aristotle-server status --porcelain` + Hermes reachability

Repo: `/home/justcallmesu/project/personal/Aristotle` (Go module in `server/`). Read `PROJECT.md` first (hard rules, code shape).
Spec: `docs/release/spec.md` section "Complete installer (2026-11-12)", decisions **P22** (yours) and P21/P24 (context: a shell
installer will parse your output).

## Goal
1. The control-socket `status` answer (`server/internal/app/controlcmds.go:50-52`, type `statusResult` at `:122`) also carries:
   the listen **port**, for each LAN listener its **iface, ip and subnet CIDR** (the listen manager already knows it:
   `server/internal/listen/addr.go:99` `Endpoint.Subnet`, `Manager.Active()` at `server/internal/listen/manager.go:218`), and
   **Hermes reachability**: `reachable`, `not-running` (the connector returns `hermes.ErrHermesNotRunning`,
   `server/internal/hermes/hermes.go:24`) or `error` + a short reason.
2. Reachability uses the **same connector / Hermes home the relay uses** and only the `identify` verb
   (`Connector.Identify`, `server/internal/hermes/control.go:61`): never mint a ticket, never open `/api/ws`, never create a session.
   Bounded by a short timeout (2 s, a named constant) so `status` cannot hang.
3. `aristotle-server status` (`server/internal/app/servicecmds.go:134-164`) prints one more line after the addresses:
   `Hermes:   reachable` or `Hermes:   not running (<reason>)` / `Hermes:   error (<reason>)`. Reason sanitized like the other
   fields (`sanitize`), no tokens, no prompt text.
4. `aristotle-server status --porcelain` prints the P22 format exactly, one fact per line, single spaces:
   ```
   service <is-active value>
   enabled <is-enabled value>
   server running|not-running|no-answer
   version <v>                       # only when server running
   port <n>                          # only when server running
   lan <iface> <ip> <subnet-cidr>    # one per LAN listener, only when running
   tailscale <iface> <ip>            # one per Tailscale listener, only when running
   hermes reachable|not-running|error   # only when running
   ```
   IPv6 addresses printed without brackets. Exit code: the same rule as plain `status` (`:162-165`), independent of Hermes.
   Any other argument -> usage error (exit 2), as today.

## Non-goals
- `/v1/addresses` and every phone-facing JSON stay byte-identical (do not add `subnet` to `httpapi.Address` if that type is
  serialized to the phone; add a control-only type instead).
- No change to install.sh, the systemd unit, the relay, pairing or discovery. No new dependency.

## Invariants
- Old CLI talking to a new server and new CLI talking to an old server (fields missing) must not crash: missing fields print
  nothing in porcelain except what is known (`hermes` line omitted when the server did not send it).
- The server never binds anything new; `status` stays read-only.

## Files you may create/edit
`server/internal/app/` (controlcmds.go, servicecmds.go, cli.go usage text if status usage lives there, their `_test.go`),
`server/internal/listen/` only if an accessor is truly missing, `server/internal/hermes/` only if a small helper is needed (prefer
calling `Identify`), `docs/protocol.md` ONLY if it documents the control `status` result (grep first; if it does, update that part).
Other workers are editing `scripts/` and the repo root right now: **not yours, do not touch, do not run their tests.**
Write your report to `docs/release/runs/IN1/build-report.md`.

## Acceptance tests (Go, table-driven where it fits; follow `server/internal/app/servicecmds_test.go` patterns, the `queryStatus` seam)
- porcelain: running server with 1 LAN + 1 Tailscale (v4 and v6) + hermes reachable -> exact expected text.
- porcelain: server not running -> only `service`, `enabled`, `server not-running`; exit 1.
- porcelain: old server answer without the new fields -> no `port`/`hermes` lines, no crash.
- plain status shows `Hermes:` line for reachable / not running / error.
- control handler: a fake/stub connector returning `ErrHermesNotRunning` -> `not-running`; a hanging one -> `error` within the
  timeout (use a short test timeout seam, not a 2 s sleep).
- a test proving the reachability check calls only identify (the stub records calls; no ticket mint).
Run the new tests against the OLD code first (keep a copy of the original files in your scratch dir, never git) and report them RED.

## Verify
`cd server && gofmt -l . && go vet ./... && go test ./...` (gofmt prints nothing, exit 0). Report the real exit codes, not filtered output.

## Hard rules
Never `git checkout/restore/stash/reset/commit/push`; to undo your own edit restore from your scratch copy.
Never start, install or restart a real service (`aristotle-server install`, `systemctl --user ...` writes, `loginctl`), never touch
`~/.hermes` or the running Hermes/aristotle-server. Tests use stubs/seams only. Do not edit Hermes config or state.

## Stop and ask if
- The connector the relay uses cannot be reached from the control handler without restructuring `serve.go` beyond a small field.
- `Identify` has side effects (it mints, writes, or logs secrets) — then report instead of using it.
