# DS1 review brief (deep, fresh context)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/desktop/spec.md` (D10, P9, P11, P12),
the build brief `docs/desktop/runs/DS1/build-brief.md` and the report `docs/desktop/runs/DS1/build-report.md`.
Scope: the uncommitted working-tree diff under `server/` and `docs/protocol.md` (`git diff -- server docs/protocol.md` plus the
untracked files `server/internal/app/{jsonout.go,golden_test.go,json_test.go,testdata/}`, `server/internal/service/lifecycle_test.go`).
Ignore `spikes/`, `docs/spikes/`, `docs/desktop/runs/DS2/` (another ticket).

## Already proven by the PM (hunt elsewhere)
- PM gate GREEN: gofmt, vet, `go test ./...` 883 passed (baseline 805).
- Mutants KILLED: qr_payload emptied (`TestJSONFixtures/pair.json`, `TestJSONPairHasQRPayloadForEveryAddress`); remote-terminal
  refusal disabled (`TestStopAndRestartRefusedInsideARemoteTerminal`); stop mapped to start (`TestStartStopRestartRunSystemctlUser`,
  `TestLifecycleRunsSystemctlWithExactArgs`, ...).

## Check
1. Contract: every command's `--json` shape matches the brief and `docs/protocol.md` §22; one JSON object on stdout only; error
   path `{"error"}` with the plain command's exit code; usage errors. Is the contract stable enough for a Rust parser (field types,
   optional vs absent, `[]` not `null`)?
2. Plain output really unchanged: are the golden tests capturing the OLD output (compare with `git show HEAD:<file>` behaviour),
   or were goldens generated from the new code?
3. `status --json` and `status --porcelain` share one decision (`serverState`); exit codes identical in every state.
4. P11: the pairing code never reaches stderr, logs, error text or `qr_error`.
5. P12 detection mirrors `scripts/install.sh:58-61`; refusal applies to stop/restart only.
6. Builder deviations to judge: (a) `unitArg = "aristotle-server"` beside the existing `UnitName = "aristotle-server.service"`
   (two spellings of one unit: duplication? should Start/Stop/Restart use `UnitName`?); (b) new `qr_error` field (builder addition);
   (c) `callServer` turned into a package `var` as a test seam (acceptable? race-safe in parallel tests?); (d) edited existing test
   `TestStatusBadArgs`; (e) `[]` instead of `null`.
7. Code shape per PROJECT.md: small named functions, no duplication, one JSON helper.

## Output
`docs/desktop/runs/DS1/review-report.md`: verdict APPROVED / APPROVED-WITH-FINDINGS / CHANGES-REQUIRED; findings as
BLOCKER/MAJOR/MINOR/NIT with `file:line` and a concrete fix. Read-only: never edit code, never run git write commands, never touch
the running service.
