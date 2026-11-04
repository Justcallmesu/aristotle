# TH1b build report
Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TH1b` exits 0 (PASS android).
- **MAJOR-1:** `CredentialCodec.withoutAddress` remembers a deletion only if `remembersDeletion` (another stored address of the same kind). Deleting the only Tailscale address is not remembered, so a refresh re-adds it. Deleting one of two LAN addresses is remembered.
- **Dialog:** `AddressRow.remembered` picks the text. New keys `settings_address_delete_remembered` / `_may_return` (EN + ID, appended). The old `settings_address_delete_text` is no longer used; left in place (append-only).
- **NIT history future:** `ConnectionLog.prune` drops lines dated more than 1 day ahead (`MAX_AHEAD_MILLIS`).
- **NIT history after Unpair:** `ConnectionLog(paired = …)` drops events while no PC is paired (wired in `AppContainer`). `PcRecords.unpair` also clears again after the wipe.
- **NIT lastSuccessAt:** moved on at most once an hour per address (`LAST_SUCCESS_STEP_MILLIS`); an unchanged entry is not rewritten.
- **Tests:** `CredentialStoreTest` (two-LAN delete, only-Tailscale delete re-added, hourly write), `ConnectionLogTest` (future lines, no write when unpaired), `ConnectionRowsTest` (`remembered`). The old delete test now uses two LAN addresses.
- **Fix on the way:** `AppContainer.credentials` got an explicit type (recursive type inference after wiring `paired`).
- Not done: no adb run; `PcRecords` clear-after-wipe has no dedicated test.
