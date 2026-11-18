# DS1d build brief — `pair --json` gains `qr_modules` (P19)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Spec `docs/desktop/spec.md` P11 + **P19**; contract
`docs/protocol.md` §22 (pair row); code: `server/internal/app/cli.go` (`runPair`, `qrFor`, the JSON pair struct with `qr_payload`
/ `qr_error`), `server/internal/termqr/` (the encoder), `server/internal/pairing` (`Payload`, `MaxPayloadAddresses`).

## Goal
`pair --json` adds `qr_modules`: `[]string`, one string per QR row, each the same length, `"1"` = dark module, `"0"` = light, quiet
zone NOT included (the window adds its own margin). Built from exactly the `qr_payload` string the same object carries (so it
encodes all addresses up to `MaxPayloadAddresses`, unlike the terminal QR which trims to 80 columns). Absent (omitempty) when
`qr_payload` is empty; if encoding fails, `qr_modules` is absent and `qr_error` explains (never contains the code). Plain `pair`
output byte-identical (goldens). Expose the module matrix from `termqr` with a small accessor if it does not exist (no duplicate
encoder). Update §22 and the `pair.json` fixture.

## Tests
`qr_modules` decodes back to `qr_payload` (decode with the encoder's own inverse if available; otherwise assert size/version and
that re-encoding the payload yields identical rows), rows equal length, absent when payload empty, stderr empty, goldens unchanged.
RED first against a scratch copy (never git). Verify: `cd server && gofmt -l . && go vet ./... && go test ./...`.

## Also carry it through the desktop app (so the Pair view can use it)
Add the optional field end to end: the pair type in `desktop/core/src/types.rs` (`Option<Vec<String>>`, serde default), the app
DTO/IPC reply in `desktop/app/src/` (pairing/dto), and the TypeScript IPC type in `desktop/ui/src/ipc/types.ts` (optional
`qr_modules?: string[]`); a core test decoding the updated `pair.json` fixture. Run `bash docs/desktop/gates.sh
docs/desktop/runs/DS1d/self-gate` once at the end with `timeout 1800` (three view workers edit `desktop/ui/src/views/**` meanwhile;
a failure inside `views/` is "not mine", report it).

## Files you may create/edit
`server/internal/app/**`, `server/internal/termqr/**`, `docs/protocol.md` (§22 pair row), `desktop/core/src/types.rs` (+ its test),
`desktop/app/src/{pairing,dto}.rs`, `desktop/ui/src/ipc/types.ts`. View workers edit `desktop/ui/src/views/**` and the i18n json
files: not yours.
## Hard rules
Never git write commands; never run `aristotle-server pair` against the real service (it mints a real code); no installs; no
skill/memory edits.
## Report
`docs/desktop/runs/DS1d/build-report.md`.
