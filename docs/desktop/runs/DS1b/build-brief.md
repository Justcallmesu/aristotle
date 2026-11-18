# DS1b build brief — DS1 review minors (server, before DS3 parses the contract)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). DS1 is committed (167aa4a). Input: the review
`docs/desktop/runs/DS1/review-report.md` (read it in full; it has file:line and fixes), the DS1 brief
`docs/desktop/runs/DS1/build-brief.md`, `docs/protocol.md` §22.

## Fix
1. **MINOR-1:** drop `unitArg`; Start/Stop/Restart use `UnitName` like every other systemctl call in
   `server/internal/service/service.go`. Update the tests' expected argv and §22.
2. **MINOR-2:** §22 marks every field that can be absent (status `result.port`, `listeners`, `hermes`; anything else `omitempty`
   in the shapes it lists) and states `service` can be `"unknown"`. Add one fixture for a status answer with those fields absent
   (an older server / server that has not finished its first scan) and a test that decodes it.
3. **MINOR-3:** one terminal marker constant: export it from `server/internal/terminal` and use it in the service package
   (move or parameterise `InRemoteTerminal` as the review suggests; no import cycle).
4. **NITs:** one helper for the URL list (`cli.go` duplicated loop); one shared status test stub helper instead of three; make
   `--json` behave the same on every command (`pair --name --json` must not swallow `--json` as the name: refuse a name that starts
   with `-`, or parse consistently; pick one and test it); per-command usage lines mention `--json`; remove the dead `_ = srv`.

## Invariants
Plain output unchanged (the golden tests stay green untouched). JSON shapes unchanged except doc clarifications. No new deps.

## Files you may create/edit
`server/internal/app/**`, `server/internal/service/**`, `server/internal/terminal/**` (the constant only), `docs/protocol.md` (§22
only). Other workers edit `spikes/**`, `docs/spikes/**`, `docs/design/desktop/**`: not yours.

## Tests / verify
New or adjusted tests for each item; see them RED first against a scratch copy of the old file (never git).
`cd server && gofmt -l . && go vet ./... && go test ./...` with real exit codes.

## Hard rules
Never `git add/commit/push/checkout/restore/stash/reset`. Never run systemctl or the real `aristotle-server` lifecycle commands,
never touch the running service, `~/.local/state/aristotle`, Hermes. No installs, no skill/memory edits.

## Report
`docs/desktop/runs/DS1b/build-report.md`: per item what changed (file:line), tests, RED evidence, gate output tail.
