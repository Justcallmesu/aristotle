# DS1b build report — DS1 review minors

Gate (own run, `cd server`): `gofmt -l .` → no files, exit 0; `go vet ./...` exit 0; `go test -count=1 ./...` exit 0 (all packages `ok`,
incl. app 7.4s, service, terminal). No git write commands, no systemctl, no service/state/Hermes touched.

## Per item

**MINOR-1 — one unit spelling.** `service/service.go:137-145` `unitArg` deleted; Start/Stop/Restart call `m.ctl(verb, UnitName)`.
Expected argv now `systemctl --user <verb> aristotle-server.service` in `service/lifecycle_test.go` and `app/json_test.go`
(`TestLifecycleRunsSystemctlWithExactArgs`, `TestLifecycleJSON`, `TestLifecycleFailure`, start-allowed case). `docs/protocol.md` §22 updated.

**MINOR-2 — optional status fields.** §22 `status` row now spells out `result`: `addresses` always an array; `port?`, `listeners?` (absent,
not `[]`), `hermes?` `{state, reason?}`, `listeners[].subnet?`; says `?` means key absent, never `null`; names the old-server case. New fixture
`app/testdata/json/status_sparse.json` (`{"version":"0.9","addresses":[]}` under the status wrapper) and `TestJSONStatusWithOptionalFieldsAbsent`
(`json_test.go`) which checks it against the output and decodes it into a struct with pointer/optional port, listeners, hermes (all nil, keys
absent). NIT-5 `"unknown"` clause added to §22 plus `TestJSONStatusServiceUnknown`.

**MINOR-3 — one marker constant.** `terminal.MarkerEnv` exported (`terminal/proc.go:15`; use sites `proc.go:131`, `sys_linux.go:175` renamed).
`service.InRemoteTerminal(markerEnv, getenv, cgroupFile)` now takes the name (`service/service.go:150`), the private
`terminalMarkerEnv` is gone, no import cycle (service still does not import terminal). `app/servicecmds.go:310` passes `terminal.MarkerEnv`.
Tests: `TestInRemoteTerminalUsesTheGivenMarkerName` (service), `TestLifecycleGuardUsesTheTerminalMarker` (app, uses `terminal.MarkerEnv`; the
`useTerminalEnv` helper also uses it, so a rename cannot desync).

**NITs.**
- NIT-1: `addrURLs` helper (`app/cli.go:105`), used by `printQR` and `newPairJSON`.
- NIT-2: one stub `runStatusCmd` (`app/status_test.go`) returning `(code, out, errOut)`; `runStatusWith`, `runStatusJSON` and the golden `runIt` are
  thin wrappers over it.
- NIT-3 (chosen: parse consistently): `runPair` now does `stripJSON` first like every command (`cli.go:31`, `--json` flag removed), so
  `pair --name --json` leaves `--name` without a value → exit 2, stdout empty. It also refuses a name starting with `-` (`cli.go:37`, exit 2).
  `-json` / `--json=true` are usage errors everywhere now (before, only `pair` accepted them). §22 states this. Tests `TestPairJSONIsNotTakenAsTheName`,
  `TestPairJSONAnywhere`.
- NIT-4: usage lines mention `--json`: pair (`cli.go`), status (`servicecmds.go`), `devicesUsage`, `terminalUsage`. Test `TestUsageLinesMentionJSON`.
- NIT-6: dead `_ = srv` removed (`json_test.go`).

## Deviation to check
Two golden tests pin usage text as literals, and NIT-4 changes that text, so I edited exactly two expected strings in `golden_test.go`
(`status [--porcelain | --json]`, `pair [--name NAME] [--no-qr] [--json]`). Everything else in the goldens is untouched; the plain stdout goldens pass
unchanged. If you want those usage lines left alone, revert that bit of NIT-4.
The only non-whitelisted-looking edit: renaming the constant's use in `terminal/sys_linux.go` (required by the export).

## RED evidence
Scratch copy of the old code at `~/.hermes/profiles/pm/cache/scratch/ds1b/old` (made before editing), new test files copied in, with a one-line shim
(`terminal.MarkerEnv`, ignored marker arg) so the old code compiles:
- service: `TestStartStopRestartRunSystemctlUser` (`ran [systemctl --user start aristotle-server]`), `TestStopReturnsTheRunnerError`,
  `TestInRemoteTerminal` (env marker false, want true), `TestInRemoteTerminalUsesTheGivenMarkerName` FAIL. Without the shim: compile error
  (`too many arguments in call to InRemoteTerminal`).
- app: `TestLifecycleRunsSystemctlWithExactArgs`, `TestLifecycleJSON`, `TestLifecycleFailure`, `TestStopAndRestartRefusedInsideARemoteTerminal` (old
  `aristotle-server` argv); `TestPairJSONIsNotTakenAsTheName` (`pair --name --json` exit 0, plain output with the QR text, and `-json` accepted);
  `TestUsageLinesMentionJSON`; the two golden usage tests. All FAIL.
- Not RED against old code, by nature: `TestJSONStatusWithOptionalFieldsAbsent` and `TestJSONStatusServiceUnknown` (they pin existing behaviour that §22
  now documents), `TestLifecycleGuardUsesTheTerminalMarker` (the shim made the constant exist; it guards future renames).

## Files
Edited: `server/internal/service/service.go`, `service/lifecycle_test.go`, `server/internal/terminal/proc.go`, `terminal/sys_linux.go`,
`server/internal/app/{cli.go,servicecmds.go,terminalcmds.go,json_test.go,golden_test.go,status_test.go}`, `docs/protocol.md` (§22).
Created: `server/internal/app/testdata/json/status_sparse.json`, this report.
