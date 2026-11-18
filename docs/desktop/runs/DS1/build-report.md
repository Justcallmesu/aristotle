# DS1 build report — server `--json` + `start|stop|restart`

Status: built, server gate green (own run). No stop condition hit: no plain output changed, no control result carries a secret
besides the pairing code (checked `statusResult`, `terminalStatusResult`, `terminal.Open`, `devices.Public`: none), `printPorcelain`'s
logic was reused without changing its output (golden + equivalence test).

## Files changed
- `server/internal/app/jsonout.go` (new): `stripJSON`, `printJSON`, `failCmd` (plain `error: x` on stderr / `{"error":x}` on stdout, exit 1), `emitJSON`. The one helper every command uses.
- `server/internal/app/app.go`: `version --json` (`runVersion`), dispatch `start|stop|restart`, usage text (new commands, `--json`).
- `server/internal/app/cli.go`: `callServer` became a `var` (test seam, same body); `--json` for `pair` (`pairJSON` = `pairStartResult` + `qr_payload`), `devices list|revoke|grant-terminal|ungrant-terminal`; `nonNil`.
- `server/internal/app/terminalcmds.go`: `--json` for `terminal enable|disable|status`, grant/ungrant (`jsonTerminal`).
- `server/internal/app/servicecmds.go`: `status --json` (`statusJSON`, `serverState` shared with `printPorcelain`), `runLifecycle` for start/stop/restart, seams `getenv`, `cgroupFile`.
- `server/internal/service/service.go`: `Manager.Start/Stop/Restart`, `InRemoteTerminal(getenv, cgroupFile)`.
- Tests: `app/golden_test.go`, `app/json_test.go`, `service/lifecycle_test.go` (new); `app/status_test.go` (one edit, see deviations).
- Fixtures `server/internal/app/testdata/json/*.json` (16 files, asserted byte for byte by the tests; `UPDATE_JSON_FIXTURES=1` rewrites).
- `docs/protocol.md`: new section 22 "CLI JSON output (desktop app)".

## JSON examples (fixtures; fake data, code is a test value)
```
version        {"version":"1.2.3","commit":"unknown","protocol":1}
status (up)    {"service":"active","enabled":"enabled","server":"running","result":{"version":"1.2.3","addresses":[{"url":"https://192.168.1.5:8760","kind":"lan","iface":"wlan0"},...],"port":8760,"listeners":[...],"hermes":{"state":"reachable"}}}
status (down)  {"service":"inactive","enabled":"enabled","server":"not-running"}          exit 1
devices list   {"devices":[{"id":"dev_a1","name":"pixel","created_at":"2026-11-01T10:00:00Z","last_seen_at":"2026-11-18T09:30:00Z"},...]}
devices revoke {"id":"dev_a1","name":"pixel","created_at":"...","last_seen_at":"..."}
terminal status/enable/disable, grant/ungrant
               {"enabled":true,"granted":[{"id":"dev_a1","name":"pixel"}],"open":[{"id":"term_0001","device_id":"dev_a1","listener":"lan","since":"...","pid":4242}],"changed":true,"warning"?:"...","problem"?:"...","device"?:{...}}
pair           {"code":"K7Q2M9XD","expires_at":"...","fingerprint":"7E54 ...","addresses":[{"url":"...","kind":"lan","iface":"wlan0","default_route":true}],"qr_payload":"aristotle://pair?v=1&c=K7Q2M9XD&fp=...&a=...","qr_error"?:"..."}
start/stop/restart  {"ok":true,"service":"active"}   (stop: "inactive")
any failure    {"error":"no device with id dev_x"}   exit 1
```

## Tests added (all in `package app` unless noted)
- Golden plain output (written first, passing on the old code): `TestPlainOutputGolden` (17 subtests: devices list/empty/error, revoke/error, usage, grant ×3, ungrant/error, terminal status ×2/enable/disable/error/usage), `TestPlainVersionGolden`, `TestPlainStatusGolden` (running, not running, no answer, usage), `TestPlainPairingGolden` (no-QR text whole; QR variant by sha256), `TestPlainPairErrorGolden`.
- JSON: `TestJSONFixtures`, `TestJSONVersionFields`, `TestJSONDevicesListIsAnObject`, `TestJSONTerminalListsAreArrays`, `TestJSONTerminalWarningStaysOutOfStderr`, `TestJSONErrorPathEveryCommand` (8 commands), `TestJSONErrorWhenServerNotRunning`, `TestJSONUsageErrorStaysOnStderr`, `TestJSONPairHasQRPayloadForEveryAddress` (17 addresses, payload == `pairing.Payload`, 8 encoded, stderr empty), `TestJSONPairWithoutAddressesExplainsTheMissingPayload`, `TestJSONPairNeverLeaksTheCodeElsewhere` (real test server: code not in stderr nor server log), `TestJSONRevokeEndToEnd` (real control socket), `TestJSONStatusRunning`, `TestJSONStatusServerDown` (not-running, no-answer, exit 1, no `result`), `TestJSONStatusMatchesPorcelain` (3×3 matrix: same exit code and server word as `--porcelain`).
- Lifecycle: `TestLifecycleRunsSystemctlWithExactArgs`, `TestLifecycleJSON`, `TestLifecycleFailure`, `TestStopAndRestartRefusedInsideARemoteTerminal` (env marker and cgroup naming the unit, plain and JSON, runner never called; `start` allowed both ways), `TestUsageListsTheNewCommandsAndFlag`.
- `service`: `TestStartStopRestartRunSystemctlUser`, `TestStopReturnsTheRunnerError`, `TestInRemoteTerminal` (5 cases).

## RED first
- Old state (seam only + golden tests) saved to `$TMPDIR/ds1old` (scratch, no git). New tests copied onto it in `$TMPDIR/ds1red`:
  without any new code the package does not compile (`undefined: pairJSON`, `cgroupFile`). With a no-behaviour shim (the three missing symbols only) it compiles and
  **20 test functions FAIL** (`TestJSON*`, `TestLifecycle*`, `TestStopAndRestart...`, `TestUsage...`), while `-run Golden` is **ok** on that old code.
- Goldens were also green on the old code before any production change (`go test -count=3 ./internal/app` ok).

## Gate (own run, real exit codes, not piped)
```
cd server && gofmt -l .      -> no output, exit 0
cd server && go vet ./...    -> exit 0
cd server && go test ./...   -> all packages ok (app 3.7s, service 0.03s), exit 0
go test -count=1 -v ./...: 885 RUN lines (incl. subtests), 883 PASS, 2 SKIP (helper-process tests); 0 FAIL
```
(The baseline "805" is the PM's count method; mine counts subtests, so the numbers are not comparable.)

## Deviations from the brief
1. **Unit argument.** Brief says `systemctl --user <verb> aristotle-server`; followed literally (`unitArg = "aristotle-server"`). Existing `install`/`State` calls use `aristotle-server.service`. Both name the same unit; PM may want one spelling.
2. **`pair --json` extra field `qr_error`** (omitted when empty). When `pairing.Payload` cannot build a payload (no listener up, bad fingerprint) the code is still issued, so the command succeeds with `qr_payload:""` and `qr_error:"<static text>"` (plain `pair` also still prints the code in that case). It never contains the code.
3. **`callServer` is now a `var`** (identical body) to stub the control socket in golden tests without a server.
4. **Existing test edited:** `TestStatusBadArgs` listed `{"--json"}` as a bad argument; it is now a valid flag. Replaced by `{"--json","--porcelain"}` and `{"--json","x"}`. `status --json --porcelain` is a usage error (exit 2).
5. `devices list --json` and the terminal results normalise nil lists to `[]` (a `null` would break typed parsers); `status --json` `result.addresses` likewise.
6. `stop`/`restart` error text on a failed systemctl includes systemctl's stderr (captured, so nothing leaks onto stdout/stderr in `--json` mode). The plain-mode text of the new commands (`Service stop done; it is now inactive.`) is new, so not subject to byte-for-byte.
7. `--json` is accepted anywhere after the command name (`devices --json list` works); `--json` before the command name is an unknown command.
8. Protocol doc section is numbered 22 (next free number).

## Doc text added (docs/protocol.md section 22)
Rules (one object + newline; `{"error"}` + same exit code; usage errors stay text on stderr; snake_case, RFC 3339; plain output unchanged), a per-command table of the shapes, and the start/stop/restart + remote-terminal refusal paragraph. See the file.

## Tips for the PM / DS3
- `status --json` exit code is 1 when the service is not `active` or the server did not answer; DS3 must read the JSON on stdout even then.
- `commit` in `version --json` is `"unknown"` for non-release builds (ldflags).
- Mutants worth running: drop `QRPayload` assignment in `newPairJSON`; make `runStatus` return 0 under `asJSON`; map `stop` to `Start` in `lifecycle`. Each is covered by a test above (`TestJSONPairHasQRPayload...`, `TestJSONStatusServerDown`, `TestLifecycleRunsSystemctlWithExactArgs`); I did not run them myself.
- Not done by me: PM gate script, real `systemctl` (never run against the real unit), any git operation.
