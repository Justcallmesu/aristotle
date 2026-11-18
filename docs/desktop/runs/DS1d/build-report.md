# DS1d build report — `pair --json` gains `qr_modules` (P19)

## Outcome
Done end to end: Go server, protocol §22, fixture, Rust core type (carried through the IPC reply), TS type.

## Changes
- `server/internal/termqr/termqr.go`: `(*Code).Modules() []string` (square, `1` dark / `0` light, no quiet zone). The only encoder is still `termqr.Encode`.
- `server/internal/app/cli.go`: `pairJSON.QRModules []string json:"qr_modules,omitempty"`, filled from the same `qr_payload` text (all addresses up to `MaxPayloadAddresses`, no 80-column trim). Encode failure → modules absent, `qr_error = "QR code: <err>"` (no code in it). Plain `pair` untouched (goldens pass). `app.go`: help text.
- `docs/protocol.md` §22 pair row; `server/internal/app/testdata/json/pair.json` regenerated (only that fixture changed).
- `desktop/core/src/types.rs`: `Pair.qr_modules: Option<Vec<String>>` (`serde(default, skip_serializing_if = "Option::is_none")`, so the IPC reply omits it and the TS side sees `undefined`); `Debug` hides it. `desktop/app/src/ipc.rs` returns `Pair` directly, so `dto.rs` needed no change.
- `desktop/ui/src/ipc/types.ts`: `qr_modules?: string[]`.
- Tests: `termqr_test.go` (matrix equals `Black`, 25×25), `json_test.go` (modules equal a fresh encode of the payload with 9 addresses → 8 encoded, equal-length 0/1 rows, absent when payload empty, stderr empty, `qr_modules` key in pair fixture), `desktop/core/tests/fixtures.rs` (decode updated fixture, Debug hides, absent case), `desktop/app/src/pairing.rs` (IPC reply carries modules).
- Decode check: rsc.io/qr has no decoder, so the inverse check is row-for-row equality with a re-encode of `qr_payload`.

## Verification
- RED: scratch copy `~/.hermes/profiles/pm/cache/scratch/ds1d` with the new tests and old code: `go test ./internal/app ./internal/termqr` exit 1, build failed (`Modules`/`QRModules` undefined).
- GREEN: `cd server && gofmt -l . && go vet ./... && go test ./...` → gofmt empty, vet 0, all packages ok.
- `timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DS1d/self-gate` → exit 1. PASS: ui-ci, lint, typecheck, ui-build, desktop fmt/clippy/test (148 passed, 0 failed), test-count, test-binaries, tray build/ldd, app build/binary. FAIL, not mine:
  - `ui-test` (2 of 225 failed): `src/views/logs/logs.test.tsx` "scrolling up pauses…" (views/, in-flight view worker) and `src/components/components.test.tsx` "shell > updates the footer from a snapshot event" (not in my whitelist; nothing I changed touches it, likely in-flight i18n/view edits).
  - `ui-test-count (0<73)` is a consequence of the same ui-test failure (count parsed as 0).
  - An earlier gate run (before view workers settled) also showed `TerminalView.test.tsx` TS errors; they were gone in the second run.
- No git write commands; `aristotle-server pair` never run against the real service (stubbed control in tests only).
