# DS1 review report — server `--json` + `start|stop|restart`

Reviewer: fresh-context deep review. Scope: `git diff -- server docs/protocol.md` plus the untracked `server/internal/app/{jsonout.go,
golden_test.go,json_test.go,testdata/}` and `server/internal/service/lifecycle_test.go`. Read-only: no code edited and no git write
commands run. The real service and `~/.local/state/aristotle` were not touched (probes ran with a fake `systemctl` on `PATH` and temp
`XDG_STATE_HOME`/`XDG_CONFIG_HOME`).

## Verdict: **APPROVED-WITH-FINDINGS**

No BLOCKER or MAJOR. The contract, plain-output invariance, status/porcelain sharing, P11 and P12 all hold. The findings are MINOR or NIT:
mostly doc precision for the Rust parser, plus small duplication.

## What I verified (evidence)

- **Gate (own run):** `gofmt -l .` printed nothing; `go vet ./...` exit 0; `go test -count=1 ./...` all ok; `go test -race -count=1
  ./internal/app ./internal/service` ok.
- **Goldens capture the OLD output (check 2):** I exported HEAD with `git archive HEAD server` into scratch, applied only the
  `callServer` func→var seam, copied in the new `golden_test.go`, and ran `go test -run Golden -v`. All 5 golden tests PASS on HEAD
  code. So the goldens encode pre-change behaviour and were not regenerated from the new code. The byte-exact porcelain tests
  (`status_test.go:50-80`) already existed at HEAD and still pass. `failCmd` (`jsonout.go:41`) writes `"error: "+err+"\n"`, the same
  bytes as the old `fmt.Fprintln(stderr, "error:", err)`.
- **Contract (check 1):** a single helper prints every result: `printJSON`/`emitJSON`/`failCmd` (`jsonout.go:23-49`). Probes against
  a built binary:
  - `version --json`: one object, exit 0.
  - `status --json` with the server down: `{"service":"inactive","enabled":"enabled","server":"not-running"}`, exit 1, same as
    `--porcelain`.
  - `start --json` when systemctl fails: `{"error":"systemctl --user start: exit status 5: …"}`, exit 1, stderr empty. systemctl's
    stderr is captured (`servicecmds.go:313-321`).
  - `devices list --json` and `pair --json` with no socket: `{"error":…}`, exit 1, stderr empty.
  - Usage errors (`devices list -json`, `logs --json`, `install --json`): exit 2, stdout empty, text on stderr.
  - No fixture contains `null`. `devices`, `granted`, `open`, `pair.addresses` and `status.result.addresses` are normalised with
    `nonNil` (`cli.go:192`, `terminalcmds.go:187-191`, `servicecmds.go:180`).
- **One decision for status (check 3):** `serverState` (`servicecmds.go:187-195`) is called by both `newStatusJSON`
  (`servicecmds.go:177-184`) and `printPorcelain` (`servicecmds.go:231`). The exit code comes from the one shared tail of
  `runStatus` (`servicecmds.go:162-165`). `TestJSONStatusMatchesPorcelain` covers the full 3×3 matrix.
- **P11 (check 4):** `pairing.Payload` errors are static strings (`pairing/payload.go:25,29,32`) and never include the code, so
  `qr_error` cannot leak it. In `--json` mode the code reaches only stdout. `TestJSONPairNeverLeaksTheCodeElsewhere` checks the real
  server log.
- **P12 (check 5):** `InRemoteTerminal` (`service/service.go:155-161`) matches `scripts/install.sh:60`:
  - `-n "${ARISTOTLE_TERMINAL:-}"` ↔ `getenv(...) != ""`.
  - `grep -q "$UNIT\.service"` with `UNIT=aristotle-server` (`install.sh:26`) ↔ `strings.Contains(data, UnitName)`.

  A set-but-empty marker is not refused in either. Refusal is limited to `verb != "start"` (`servicecmds.go:309`) and runs before
  any runner call. Probe: `ARISTOTLE_TERMINAL=t1 stop --json` gives `{"error":"refused: …"}`, exit 1, and systemctl is never called.

## Builder deviations: judgement

| # | Deviation | Judgement |
|---|---|---|
| a | `unitArg = "aristotle-server"` beside `UnitName` | Works (systemctl adds `.service`), but it is a second spelling of one unit in one file → MINOR-1. |
| b | `qr_error` | Acceptable. Optional, documented, static text without the code. It helps the app say why there is no QR. |
| c | `callServer` as a package `var` | Acceptable. It follows the package's existing seam pattern (`serviceRunner`, `executable`, `queryStatus`, `servicecmds.go:22-36`). No `t.Parallel` in `internal/app`, every swap is restored with `t.Cleanup`, and `-race` is clean. |
| d | `TestStatusBadArgs` edited | Correct and required: `--json` alone is now valid. The replacement cases `--json --porcelain` and `--json x` keep the usage path covered (`status_test.go:92`). |
| e | `[]` instead of `null` | Correct for typed parsers. One gap remains in the status result → MINOR-2. |

## Findings

**MINOR-1: two spellings of one unit name.**
- Where: `server/internal/service/service.go:137-147`. `Start/Stop/Restart` use `unitArg = "aristotle-server"`, while every other
  systemctl call in the same file uses `UnitName` (`:203,207,214,248,265`), including the existing `restart` in `Install` (`:207`).
- Fix: delete `unitArg` and call `m.ctl("start"|"stop"|"restart", UnitName)`. Update the expected argv in
  `service/lifecycle_test.go:67,75` and `app/json_test.go:412,427,438,526`, and the "systemctl --user <verb> aristotle-server" text in
  `docs/protocol.md` §22.
- Why: P9 says "the unit it already owns". Either spelling is equivalent for systemctl, but one constant keeps a future rename in one
  place.

**MINOR-2: §22 does not mark the optional `status` result fields.**
- Where: `docs/protocol.md:1693`. It lists `result` as "(version, addresses, port, listeners, hermes)" with no `?`. But `port`,
  `listeners` and `hermes` are `omitempty` (`server/internal/app/controlcmds.go:127-129`): they are absent on an older running server
  or when nothing is bound. `hermes.reason` and `listeners[].subnet` are also omitempty. The terminal row in the same table does mark
  `warning?`, `problem?` and `device?`.
- Effect: the only status fixture (`testdata/json/status.json`) has every field present. A Rust struct written from it with
  non-`Option` `listeners`/`hermes` would fail to parse in those cases.
- Fix (doc only; keep the control shape, as the brief says): write `port?`, `listeners?` (absent, not `[]`, when empty), `hermes?`
  (`{state, reason?}`). Optionally add a fixture for an old-server status (`{"version":"0.9","addresses":[]}`) so DS3 tests the
  `Option` path.

**MINOR-3: the marker variable name now has a second Go copy.**
- Where: `server/internal/service/service.go:149-150` (`terminalMarkerEnv = "ARISTOTLE_TERMINAL"`) duplicates `terminal.markerEnv`
  (`server/internal/terminal/proc.go:15`). If the terminal package renames its marker, the P12 guard silently stops matching the
  env check.
- Fix: export `terminal.MarkerEnv` and use it. `service` must not import `terminal` (it would pull in websocket and relay), so either
  move `InRemoteTerminal` into `internal/app`, where both are reachable, or have `runLifecycle` pass the name in.
- Also: add one test that checks the two constants are equal if they stay separate.

**NIT-1: the URL-list loop is duplicated.**
- Where: `server/internal/app/cli.go:211-214` (`newPairJSON`) repeats `cli.go:79-82` (`printQR`).
- Fix: extract `func addrURLs(a []pairAddr) []string` and call it from both.

**NIT-2: the status stub helper exists three times in the tests.**
- Where: `json_test.go:331-344` (`runStatusJSON`), `golden_test.go:178-193` (`runIt`) and the existing `runStatusWith` in
  `status_test.go` all install the same `useFakes` + `queryStatus` stub.
- Fix: one helper returning `(code, stdout, stderr)`, used by all three.

**NIT-3: `pair`'s `--json` is a real flag; the others are a stripped token.**
- Where: `cli.go:33` vs `stripJSON` (`jsonout.go:17-20`).
- Effects:
  - `pair -json` and `pair --json=true` work, but `devices list -json` and `terminal status --json=true` are usage errors (probed).
  - `pair --name --json` takes `--json` as the name and prints plain text with the QR code (probed: plain `error:` on stderr).
  - So §22's "`--json` may sit anywhere after the command name" is not quite true for `pair`.
- Impact: harmless for the app (P4 uses fixed argv).
- Fix: say in §22 that the app passes `--json` as a separate last argument and only in the `--json` spelling.

**NIT-4: per-command usage lines omit `--json`.**
- Where: `cli.go:38` (`pair [--name NAME] [--no-qr]`), `servicecmds.go:144` (`status [--porcelain]`), `devicesUsage`,
  `terminalUsage`. The top-level help does list it (`app.go:41-42`).
- Keeping them unchanged is defensible under "plain output byte for byte" (and the goldens pin them). Note it as a deliberate choice
  rather than fix it now.

**NIT-5: `service` can also be `"unknown"`.**
- Where: `State` returns `"unknown"` when systemctl prints nothing (`service/service.go:266-268`), so `status --json` `service` and
  `enabled`, and `start|stop|restart --json` `service`, can be `"unknown"`, not only a systemctl word.
- Fix: one clause in §22 so the app maps it (red).

**NIT-6: dead line in a test.**
- Where: `json_test.go:286-287`: `srv, logs := startTestServer(t); _ = srv`.
- Fix: `_, logs := startTestServer(t)`.

## Not findings (checked, fine)
- Plain-mode `start|stop|restart` text is new, so it is outside byte-for-byte. systemctl stdout is discarded and its stderr folded
  into the error (`servicecmds.go:313`).
- `status --json --porcelain` → exit 2 (`servicecmds.go:143`). `version --json extra` is accepted, consistent with plain `version`
  ignoring extra args at HEAD.
- `qr_payload` uses every address, up to `MaxPayloadAddresses` (`pairing/payload.go:39`). Plain `printQR` still trims to 80 columns.
  That difference was intended by the brief.
- Forward note for the desktop tickets (not DS1): the cgroup check is a substring match. If the Tauri app's XDG desktop entry ever
  makes systemd put the app in a unit whose name contains the literal `aristotle-server.service`, `stop` from the app would be
  refused. systemd escapes `-` as `\x2d` in `app-…` scope names, so this is unlikely, but DS7 should check `/proc/<app pid>/cgroup`
  once.
