# T08 build brief — pairing by QR (server shows the QR; the app scans it)

route: deep (pairing is the trust root). Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md` (Go: std lib first),
`docs/spec.md` Q7b, Q33, Q49; `docs/design.md` + `docs/design/mockups.html` pairing screens; `docs/protocol.md` §11 (pairing API,
fingerprint = SHA-256 of the public key, the address list); then `server/internal/app/{cli,controlcmds}.go`, `server/internal/pairing`,
`server/internal/tlscert`, and the app's pairing flow (`ui/pairing/*`, `data/{ServerClient,CredentialStore,PinnedTls,PcAddresses}.kt`).
Board row D39 in `docs/v1-tickets/tickets.md`.

## Goal
`aristotle-server pair` prints a QR in the terminal (and the manual code + fingerprint as today); the phone's "Scan QR" fills address(es),
code and fingerprint and pairs with no typing. The manual path stays exactly as it is (Q7b).

## Requirements
1. **Payload** (versioned, documented in protocol §11): `aristotle://pair?v=1&c=<code>&fp=<64 hex>&a=<url>&a=<url>...` with every
   current phone-facing HTTPS address (LAN + Tailscale, same list as `/v1/addresses`), max 8. No device token, no secret beyond the
   one-time 5-minute code. Server side builds it from the same data `pair` already prints.
2. **Server QR**: PM technical decision — use `rsc.io/qr` (BSD, tiny, stable) to encode; render with Unicode half blocks so it fits an
   80-column terminal; quiet zone; `--no-qr` flag. If you find the module unsuitable, stop and report instead of hand-rolling QR.
   Never print the QR or code to the server log (stdout of the `pair` command only).
3. **App scan**: PM technical decision — Google code scanner (`com.google.android.gms:play-services-code-scanner`, latest compatible):
   no CAMERA permission, QR only. If Play services are missing or the module can't install, show "Scanner unavailable — enter the code by
   hand" and open the manual form. Parse strictly: scheme/host, v=1, code format as the server issues it, fp = 64 hex, each `a` must pass
   the same address validator the app already uses (https, IP literal, port); reject anything else with a clear message. A scanned
   payload goes through the SAME pairing call and pin as the manual path (one code path after parsing); the fingerprint compare screen is
   skipped only because the fingerprint came from the QR — show it small for reference.
4. The scanned address list seeds `PcAddresses` (typed = the first address that pairs successfully).
5. D39: fix the stale `standDown` KDoc and give the 4409 backstop wait a `nextAttemptAtMillis` so the banner shows a countdown.
6. EN/ID strings.

## Tests
Go: payload builder (all addresses, max 8, escaping), QR render golden (small fixed input), `--no-qr`, nothing in the log. Android: parser
accepts the server's payload (shared fixture string in both test suites), rejects wrong scheme/version/fp/address/extra-long input;
scanned and manual paths call the same pairing function; D39.

## Hard rules
Files: `server/**`, `android/**`, `docs/protocol.md` §11, `docs/v1-tickets/runs/T08/build-report.md`. New deps: only the two named above.
No commits; never git checkout/restore/stash/reset. Do not install on the phone; do not touch the demo server on 8760; tests use temp
XDG dirs. No SDK installs beyond Gradle dependencies.

## Verify
`bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T08` exits 0 (both sides).

## Report
`docs/v1-tickets/runs/T08/build-report.md`, ≤30 lines, with a sample terminal QR rendering (fixed test payload, fake code).
