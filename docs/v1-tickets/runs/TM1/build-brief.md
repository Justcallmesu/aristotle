# TM1 — batch-46 review minors

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/review-batch-46.md` (MINORs + NITs with
file:line), `docs/design.md` §1/§4 (surface roles), then the files below.

## Do
1. AppLock/FreshAuth: cap an auth check that never calls back (2 min, then treat as failed: time away counts, nothing sent); rewrite the
   misnamed "never ends" test so it really never ends the check.
2. `ServerVersions` keyed per PC (by the PC record id): `/v1/me` writes under the PC it was asked for; About and the banner read the
   active PC's entry; unpair/re-pair clear only that PC's entry — so a late reply from the old PC or the clear-on-re-pair race cannot
   show the wrong version. Tests for both races.
3. Colours: compatibility banner `surfaceContainerHighest` with an error-coloured icon (the PC offline banner pattern); What's-new card
   `surfaceContainerLow`. Add both elements to design.md §4 in one line each.
4. Opening What's new from About dismisses the sessions card. Other NITs from the report if each is a one-liner.

## Files
`data/AppLock.kt`, `data/FreshAuth.kt`, `ui/lock/**`, `data/Compatibility.kt`, the `/v1/me` call site that writes ServerVersions
(only that write), `data/PcRecords.kt` (the clear call only), `ui/sessions/CompatibilityBanner.kt`, `ui/sessions/WhatsNewCard.kt`,
`ui/settings/SettingsScreen.kt` (About part), `ui/settings/WhatsNewScreen.kt`, `data/WhatsNew.kt`, `docs/design.md` §4, tests.
Not yours: `data/{PcAddresses,LinkManager,NetworkWatch}.kt`, `ui/settings/SettingsConnection.kt`, `AndroidManifest.xml`, `server/**`
(TL1 works on them now). No new strings expected; if needed append-only.

## Hard rules
No commits; never git checkout/restore/stash/reset. No adb.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TM1` exits 0. Report ≤15 lines.
