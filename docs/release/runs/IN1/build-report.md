# IN1 build report

## Files changed
- `server/internal/app/controlcmds.go`: `statusResult` gains `port`, `listeners[{kind,iface,ip,subnet}]`, `hermes{state,reason}` (all `omitempty`; `addresses` and `httpapi.Address` unchanged). New `identifier` interface (only `Identify`), `probeHermes` (timeout const `hermesProbeTimeout` = 2 s), `server.status()`, `statusListeners()`.
- `server/internal/app/serve.go`: two fields, `hermesProbe` (set to the relay's own connector) and `hermesTimeout` (test seam).
- `server/internal/app/servicecmds.go`: `runStatus` split into `printStatus` / `printPorcelain` / `printHermesLine`; `--porcelain` flag; usage `status [--porcelain]`.
- `server/internal/app/app.go`: help line for `status`.
- New `server/internal/app/status_test.go`.
- `docs/protocol.md`: not changed (it does not document the control `status` result).

## Tests added (status_test.go)
TestStatusPorcelainRunning, TestStatusPorcelainNotRunning (not-running + no-answer), TestStatusPorcelainOldServer, TestStatusHermesErrorStillExitsZero, TestStatusBadArgs, TestStatusPlainHermesLine (reachable / not running / error with sanitize / old server omits line), TestProbeHermes (reachable, not-running, error, hanging; each records exactly one `identify` call, 50 ms seam), TestControlStatusCarriesPortListenersHermes (real server, stub connector), TestControlStatusHangingHermesAnswersWithinTimeout.

## RED before
The original `controlcmds.go`, `servicecmds.go` and `serve.go` were restored from the scratch copy (`cache/scratch/in1/`, no git). `go test ./internal/app -run 'Status|Probe'` then failed to build: `undefined: hermesStatus`, `unknown field Port/Hermes/Listeners in statusResult`, `undefined: statusListener`, `undefined: identifier`. Exit 1. The new files were put back afterwards.

## Verify
`cd server && gofmt -l . ; go vet ./... ; go test ./...`
- gofmt printed nothing, exit 0.
- go vet exit 0.
- go test: all packages ok, exit 0.

## Notes and deviations
- No deviation from the brief. No stop condition hit: the relay's connector is a local in `newServer`, so one small field was enough. `Identify` is read-only (one `identify` verb on the control socket).
- Porcelain values are sanitized and have spaces turned into `_`, so each line stays parseable. The `hermes` line carries the state only; the reason appears only in plain `status`.
- Reasons are cut at 120 characters. A timeout reads `no answer within 2s`.
- Control `status` is still allowed from terminal processes, as before. It now also reveals Hermes reachability, which is not secret.
- I did not touch `scripts/`, the root `install.sh`, the real service or `~/.hermes`.
