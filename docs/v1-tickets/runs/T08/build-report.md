# T08 build report: pairing by QR (+ D39)
Gates: `bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T08` exit 0 (gofmt/vet/test PASS, go-tests 271/0; android lint+unit+assemble PASS). No commits, no phone install, 8760 untouched.

**Server.** `pairing.Payload` builds `aristotle://pair?v=1&c=<code>&fp=<64 HEX>&a=<url>…` (QueryEscape, first 8 URLs, same list/order as `/v1/addresses`; refuses bad code/fp/no address). New `internal/termqr` (rsc.io/qr v0.2.0, level L, half blocks, 4-module quiet zone, black on bright-white SGR so it reads on dark and light themes, reset per line). `pair` prints the QR to its stdout first, then "Or type it in:" + the existing text; `--no-qr` drops it. Server log untouched (QR built in the CLI). Decision: if 8 addresses would exceed 80 columns (e.g. many IPv6), trailing addresses are dropped until it fits, with a note "carries the first N of M"; the pair response still sends the full list. Tests: shared fixture `server/internal/pairing/testdata/pair-payload-v1.txt`, order/escape/max-8/key-set, QR golden + lossless module round-trip, 80-column cap, `--no-qr`, no code/payload/blocks in the log. Out of suite: golden and fixture QR decoded with `zbarimg` to the exact payload.

**App.** `data/PairPayload.kt` strict parser: exact prefix, `v` checked first (→ Version), only keys v/c/fp/a, c as issued (`PairingCode.isIssued`), fp = 64 hex, each `a` = `https://<ip>:<port>` (no path/query/userinfo, explicit port) then `ServerAddress.parse`, 1–8 addresses, ≤4096 chars; one message per error (EN/ID). `ui/pair/QrScanner.kt`: Google code scanner 16.1.0, QR only, manifest `DEPENDENCIES=barcode_ui`; merged manifest has no CAMERA. Failure (no Play services/module) → "Scanner unavailable — enter the code by hand", focus moves to the address field; user cancel → nothing. `PairStep.Confirmed` now holds `endpoints` + `scanned`; the scan and the manual compare both go through one `pair()`/`pairFirst()` → `PairingApi.pair` with the pin. Scan tries addresses in order (403/unreachable/other cert → next; 401 stops); typed = lastGood = first that pairs; stored list = `PcAddresses.merge(paired + reported, scanned)`. No compare card; "Fingerprint from the QR code" shown small in full. Screen: tonal "Scan QR code" + "or type it in" divider over the unchanged manual form; `pair_lead` updated.
**D39.** `standDown` KDoc fixed (LinkManager, LinkState); the 4409 wait sets `nextAttemptAtMillis = since + supersededRetryMillis`, cleared when the round starts; the banner counts down (`offline_waiting` now takes seconds).
**Docs.** protocol §11.1/§11.3 updated, new §11.4 (payload, escaping, rendering, app acceptance rules).

Sample (`aristotle://pair?v=1&c=K7Q2M9XD`, fake code; `termqr/testdata/small.golden` without colours/quiet rows):
```
█▀▀▀▀▀█  █ ▄▄ ▄█▄ █▀▀▀▀▀█
█ ███ █ ▀▄█ █ ▀▀▄ █ ███ █
█ ▀▀▀ █  ▄████▄█▀ █ ▀▀▀ █
▀▀▀▀▀▀▀ █▄▀▄█▄█ ▀ ▀▀▀▀▀▀▀
▀█▀▄▀█▀██ ▀████ ▄▀█▄ ▄█▄▄
█▄▀▀▀▀▀ ▀▀  ▀▄█▄ ▄▄▀▀▀▀██
 ▀▀▀▀█▀  ▄   █▀▀█▄█  █  █
▀▄▄█▄▀▀▄▀█▀▄ █ ▄ ▀▄ █▀ ▀▀
▀   ▀▀▀ █▄ ▄▄█ ▄█▀▀▀█▀ ▄█
█▀▀▀▀▀█ █▄█ ▄██▀█ ▀ █ ▀▀█
█ ███ █ ▀▀▄  ▀█▄▀█▀██ ▄▀
█ ▀▀▀ █ █▄▄  ▄▄ █ ▄█▀▀ ▄▀
▀▀▀▀▀▀▀ ▀▀▀▀▀  ▀▀▀ ▀▀▀ ▀▀
```
Open: not tried on a device (the real scanner, module download, light/dark terminals). rsc.io/qr always uses mask 0 (valid; zbar reads it). design.md still says Tailscale has no fingerprint card (stale since Q49, outside my files).
