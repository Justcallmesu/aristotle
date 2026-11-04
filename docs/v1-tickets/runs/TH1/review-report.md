# TH1 review: data and security (fresh, deep; read-only, no gradle/adb)
Paths are under `android/app/src/main/java/.../aristotle/` unless noted. CC = data/CredentialCodec.kt, CS = data/CredentialStore.kt, CL = data/ConnectionLog.kt, LM = data/LinkManager.kt, LD = data/LanDiscovery.kt.

## Migration: holds on a real format-1 entry
- **Token and encryption unchanged.** `upgrade` (CC:118-125) only copies the stored entry: `token_sealed`, `fingerprint`, `pc_id` and `device_id` (the AAD inputs, CC:234) are kept as they are, with no cipher call. Each address gets `source: "earlier"`, the entry gets `format: 2`, and no other field changes. The new `StoredAddress` keeps the old `url`/`kind` names (CC:15-20 vs PcAddresses.kt:7), so a TL1e entry parses. The test `aFormat1EntryReadsAsEarlierAndIsUpgradedWithoutLoss` uses the real TL1e shape (with `iface`) and checks the token byte for byte.
- **Failures never wipe through the upgrade.** The upgraded text is written only after `decode` returns Ok (CS:160-165). If the Keystore is busy, the entry is reported Unavailable and kept unwritten (CS:167). If the entry does not parse, `upgrade` returns null and `decode` decides as before (CC:119, D28). The write is one `commit=true` (CS:36). A kill before the write leaves format 1, which is upgraded again at the next launch, so the migration is idempotent (CC:120).
- **Downgrade keeps the pairing.** A T09-TL1e APK reads format 2 because of `ignoreUnknownKeys` and the same `url`/`kind` names (HEAD CC:17). Its next `withLink` rewrites the entry without `format`, `source`, `last_success_at` and `deleted`. The pairing survives; that metadata and the remembered deletions are lost (NIT-3). A T07c-era APK would wipe the entry, but that was already true of format 1, so TH1 adds nothing there.
- **`deleted` is bounded:** at most 8 (`takeLast(MAX_STORED)`, CC:188). Unpair (`wipeActive`) and re-pair (`encode` writes a fresh entry, CC:76) both clear it.

## Delete
- **Holds:**
  - The last address is refused in the store (CC:180, `held.size <= 1`) and in the UI (ui/settings/SettingsModel.kt:39, plus the reason line). The PM mutation catches this.
  - Deleting the address in use: `forgetAddress` runs, then `addressRemoved`, which wakes the link as a Switch with no backoff and no stand-down (AppContainer.kt deleteAddress; LM `isRemoved`). A trial on that address is cancelled, and `removed` is reset each session.
  - A relay that races the delete cannot re-store the address (CC:140).
- **MAJOR-1: a deleted Tailscale address is blocked until re-pair, and the dialog says otherwise.**
  - Server-list refreshes never re-add a deleted address (CC:149), and only a `found` address clears a deletion (CC:139). Discovery only ever finds LAN addresses (LD:321, 506, 525).
  - So if the user deletes the tailnet address while a LAN address remains (the typed one included, if it was Tailscale), the phone can never reach the PC away from home again. Getting it back means pairing again with the PC's QR or code.
  - The confirm text (res/values/strings.xml:659, values-in:647) promises "finding the PC on this Wi-Fi … brings it back", which is false for this kind of address. This is the brief's "only reachable address blocked forever" case.
  - Fix (small): do not remember deletions of Tailscale addresses (let `/v1/addresses` bring them back), or let a deletion lapse when no other address of that kind is stored. Then word the dialog per kind. Add a test.

## History log: holds
- **No secrets:** `LogEntry` holds only `displayAddress` (host:port), a network kind and enums (CL:55-79); there is no server text, token, pin or session (test `nothingSecretCanBeStored`).
- **Bounded:** 200 entries and 7 days, pruned on every write and on load (CL:141-144). The queue holds 64 and drops the oldest when full; `trySend` never blocks (CL:106, 130). The file is written on IO only (CL:117, 133). A crash loop adds at most 200 lines.
- **Private:** it is its own MODE_PRIVATE prefs file. `allowBackup=false`, and `sharedpref` is excluded under both `<cloud-backup>` and `<device-transfer>` (res/xml/data_extraction_rules.xml), as well as in `backup_rules.xml`.
- **Cleared on Unpair:** via PcRecords.kt:32. A clear also drops queued events through its generation counter.
- **Behind the app lock:** the history page is `PairedPage.SettingsHistory` (ui/AristotleApp.kt:178, 312), which is composed only when unlocked.
- **NIT-1:** a revoke or "Pair again" (`forgetPc`) keeps the old PC's history (a deliberate choice). Events recorded between `clearHistory` and `wipeActive` can leave a stray line (PcRecords.kt:32-33). Entries dated in the future (the clock moved back) never age out; only the 200 cap removes them (CL:143).

## TL1e minors, UI, docs: closed
- **MINOR-B closed:** `CheckedSocket` checks after `connect(addr, timeout)`, before any byte, then closes and throws (DirectSocketFactory.kt:53-63). It is wired for unbound routes only (LD `ScanGate.unbound`), compares bytes (`LocalAddressCheck`), and both the probe and the check go through it (AndroidLanDiscovery.kt:166-171). The PM mutation catches it.
- **MINOR-C closed:** `reaches` reads the routes again for each host and reaches nothing if they are gone (LD:55-61; called at LD:510 and 530).
- **RTN_UNREACHABLE closed:** it counts as covering (AndroidLanDiscovery.kt:121), and protocol.md §15 is updated.
- **UI and docs:** 36 strings were appended in EN and ID, with matching keys. The destructive dialog follows Unpair's error-coloured outline. design.md has no Settings-rows section to update.
- **NIT-2:** `remember` now rewrites the credential entry on every connect (LM `refreshAddresses`). Each rewrite is atomic, but there are more writes of the sealed blob.
- **NIT-3:** nothing tests the downgrade read (an old `StoredPc` parsing format 2).

Not verified: the real phone's entry (no adb), the on-device look of the UI, real VPN local addresses.
VERDICT: CHANGES-REQUIRED
